#!/usr/bin/env python3
"""Servicio de sincronización por tribuna (nube -> local).

Patrón Outbox + polling con cursor local:

  1. POLL: lee de cdc_outbox (nube) las filas de SU tribuna con id mayor
     al último cursor guardado en local, y las aplica con UPSERT en la
     tabla 'invitados'. Avanza el cursor. Baja latencia.

  2. RECONCILE (periódico): trae el snapshot completo de la tribuna
     (visitantexevento JOIN suites) y lo aplica con UPSERT. Es la red de
     seguridad que garantiza convergencia aunque el outbox pierda algún
     evento, y también hace el bootstrap inicial.

Solo realiza INSERT/UPDATE (UPSERT). Nunca borra registros en local.

Cada máquina corre una instancia con TRIBUNA_ID distinto.
Config por variables de entorno (ver .env.example).
"""
from __future__ import annotations

import logging
import os
import signal
import sys
import time

import psycopg

log = logging.getLogger("sync_tribuna")

# --------------------------------------------------------------------------
# Configuración
# --------------------------------------------------------------------------


def _require(name: str) -> str:
    val = os.environ.get(name)
    if not val:
        log.error("Falta la variable de entorno obligatoria: %s", name)
        sys.exit(2)
    return val


def _int_env(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None:
        return default
    # Tolera comentarios en línea y espacios sobrantes (p. ej. "5   # seg"),
    # que algunos gestores de entorno como systemd NO eliminan del valor.
    raw = raw.split("#", 1)[0].strip()
    if raw == "":
        return default
    try:
        return int(raw)
    except ValueError:
        log.error("La variable %s debe ser un entero (valor recibido: %r)",
                  name, raw)
        sys.exit(2)


REMOTE_DSN = (
    f"host={_require('REMOTE_DB_HOST')} "
    f"port={os.environ.get('REMOTE_DB_PORT', '5432')} "
    f"dbname={_require('REMOTE_DB_NAME')} "
    f"user={_require('REMOTE_DB_USERNAME')} "
    f"password={_require('REMOTE_DB_PASSWORD')} "
    f"connect_timeout=10 "
    f"application_name=edc_sync_tribuna"
)

LOCAL_DSN = (
    f"host={os.environ.get('LOCAL_DB_HOST', 'localhost')} "
    f"port={os.environ.get('LOCAL_DB_PORT', '5432')} "
    f"dbname={_require('LOCAL_DB_NAME')} "
    f"user={_require('LOCAL_DB_USERNAME')} "
    f"password={_require('LOCAL_DB_PASSWORD')} "
    f"connect_timeout=10"
)

TRIBUNA_ID = _int_env("TRIBUNA_ID", -1)
if TRIBUNA_ID < 0:
    log.error("Debes configurar TRIBUNA_ID (1=OCC, 2=ORI, 3=NOR, 4=SUR)")
    sys.exit(2)

CANAL = f"tribuna:{TRIBUNA_ID}"
POLL_INTERVAL = _int_env("POLL_INTERVAL", 5)          # segundos entre polls
RECONCILE_INTERVAL = _int_env("RECONCILE_INTERVAL", 300)  # segundos; 0 = off
BATCH_SIZE = _int_env("BATCH_SIZE", 500)
RETRY_BACKOFF = _int_env("RETRY_BACKOFF", 10)         # segundos tras un error

# 'estado' y 'obsingreso' son de dominio totalmente LOCAL (los marca el
# servicio de puerta): NO se leen ni se escriben desde la nube. Las altas
# nuevas nacen en NULL (columnas omitidas en el INSERT) y las filas
# existentes nunca se tocan en esas columnas (ni en poll ni en reconcile).
# NOTA: el texto SQL no debe contener '%' salvo los marcadores %s.
UPSERT_SQL = """
    INSERT INTO invitados
        (id_visitante, id_evento, id_suite, sincronizado)
    VALUES (%s, %s, %s, %s)
    ON CONFLICT (id_visitante, id_evento) DO UPDATE SET
        id_suite     = EXCLUDED.id_suite,
        sincronizado = EXCLUDED.sincronizado
"""

# --------------------------------------------------------------------------
# Estado de parada limpia
# --------------------------------------------------------------------------

_running = True


def _handle_stop(signum, _frame):
    global _running
    log.info("Señal %s recibida; terminando tras el ciclo actual...", signum)
    _running = False


# --------------------------------------------------------------------------
# Cursor local
# --------------------------------------------------------------------------


def get_cursor(local: psycopg.Connection) -> int:
    with local.cursor() as cur:
        cur.execute(
            "SELECT last_outbox_id FROM sync_control WHERE canal = %s",
            (CANAL,),
        )
        row = cur.fetchone()
        if row is None:
            cur.execute(
                "INSERT INTO sync_control (canal, last_outbox_id) "
                "VALUES (%s, 0) ON CONFLICT (canal) DO NOTHING",
                (CANAL,),
            )
            local.commit()
            return 0
        return row[0]


def set_cursor(local: psycopg.Connection, cur, last_id: int) -> None:
    cur.execute(
        "UPDATE sync_control SET last_outbox_id = %s, updated_at = now() "
        "WHERE canal = %s",
        (last_id, CANAL),
    )


# --------------------------------------------------------------------------
# Poll del outbox
# --------------------------------------------------------------------------


def poll_outbox(remote: psycopg.Connection, local: psycopg.Connection) -> int:
    """Aplica los cambios pendientes del outbox. Devuelve cuántos aplicó."""
    total = 0
    while _running:
        last_id = get_cursor(local)
        with remote.cursor() as rcur:
            rcur.execute(
                """
                SELECT id, id_visitante, id_evento, id_suite, sincronizado
                  FROM cdc_outbox
                 WHERE tribuna = %s AND id > %s
                 ORDER BY id
                 LIMIT %s
                """,
                (TRIBUNA_ID, last_id, BATCH_SIZE),
            )
            rows = rcur.fetchall()

        if not rows:
            break

        # IMPORTANTE (orden de durabilidad): primero aplicamos en local y
        # AVANZAMOS el cursor en la MISMA transacción local. Como el UPSERT
        # es idempotente, si algo falla antes del commit se reprocesa sin
        # daño (entrega "al menos una vez").
        with local.cursor() as lcur:
            for r in rows:
                _id, id_vis, id_evt, id_suite, sync = r
                lcur.execute(
                    UPSERT_SQL,
                    (id_vis, id_evt, id_suite, sync),
                )
            set_cursor(local, lcur, rows[-1][0])
        local.commit()

        total += len(rows)
        if len(rows) < BATCH_SIZE:
            break

    if total:
        log.info("Poll: %d cambios aplicados (tribuna %s)", total, TRIBUNA_ID)
    return total


# --------------------------------------------------------------------------
# Reconciliación por snapshot
# --------------------------------------------------------------------------


def reconcile(remote: psycopg.Connection, local: psycopg.Connection) -> int:
    """Trae el estado completo de la tribuna y lo aplica con UPSERT.

    Además PODA las filas locales que ya no están en el snapshot de la
    tribuna (bajas o reasignaciones a otra tribuna en la nube), pero SOLO
    aquellas que aún no han ingresado (estado IS NULL). Las filas con
    'estado' marcado se conservan siempre: son historial de ingreso de
    dominio local y nunca se borran.

    SALVAGUARDA: si el snapshot de la nube viene vacío no se poda nada
    (un fallo transitorio no debe vaciar la tabla local).
    """
    with remote.cursor() as rcur:
        rcur.execute(
            """
            SELECT ve.id_visitante, ve.id_evento, ve.id_suite,
                   ve.sincronizado
              FROM visitantexevento ve
              JOIN suites  s ON s.id_suite = ve.id_suite
              JOIN eventos e ON e.id       = ve.id_evento
             WHERE s.tribuna = %s
               AND lower(e.estado) = 'activo'
            """,
            (TRIBUNA_ID,),
        )
        rows = rcur.fetchall()

    pruned = 0
    with local.cursor() as lcur:
        # 1) UPSERT del snapshot completo.
        lcur.executemany(UPSERT_SQL, rows)

        # 2) Poda de fantasmas. Solo si el snapshot NO viene vacío, para no
        #    borrar la tabla local ante una respuesta vacía por error.
        if rows:
            lcur.execute(
                "CREATE TEMP TABLE _snap_keys "
                "(id_visitante text, id_evento integer) ON COMMIT DROP"
            )
            lcur.executemany(
                "INSERT INTO _snap_keys (id_visitante, id_evento) "
                "VALUES (%s, %s)",
                [(r[0], r[1]) for r in rows],
            )
            lcur.execute(
                """
                DELETE FROM invitados i
                 WHERE i.estado IS NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM _snap_keys k
                        WHERE k.id_visitante = i.id_visitante
                          AND k.id_evento    = i.id_evento
                   )
                """
            )
            pruned = lcur.rowcount
    local.commit()
    log.info(
        "Reconcile: %d filas de la tribuna %s sincronizadas (podadas %d "
        "no ingresadas)", len(rows), TRIBUNA_ID, pruned)
    return len(rows)


# --------------------------------------------------------------------------
# Bucle principal
# --------------------------------------------------------------------------


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    signal.signal(signal.SIGINT, _handle_stop)
    signal.signal(signal.SIGTERM, _handle_stop)

    log.info("Iniciando sync para tribuna %s (canal %s)", TRIBUNA_ID, CANAL)
    log.info("poll=%ss reconcile=%ss batch=%d",
             POLL_INTERVAL, RECONCILE_INTERVAL, BATCH_SIZE)

    remote = local = None
    last_reconcile = 0.0
    force_reconcile = True  # bootstrap al arrancar

    while _running:
        try:
            if remote is None or remote.closed:
                remote = psycopg.connect(REMOTE_DSN, autocommit=True)
                log.info("Conectado a la nube")
            if local is None or local.closed:
                local = psycopg.connect(LOCAL_DSN)
                log.info("Conectado a la base local")

            now = time.monotonic()
            if RECONCILE_INTERVAL > 0 and (
                force_reconcile or now - last_reconcile >= RECONCILE_INTERVAL
            ):
                reconcile(remote, local)
                last_reconcile = now
                force_reconcile = False

            poll_outbox(remote, local)

        except (psycopg.OperationalError, psycopg.InterfaceError) as exc:
            log.warning("Error de conexión: %s. Reintentando en %ss",
                        exc, RETRY_BACKOFF)
            for c in (remote, local):
                try:
                    if c is not None and not c.closed:
                        c.close()
                except Exception:
                    pass
            remote = local = None
            force_reconcile = True  # tras reconectar, reconciliar por si acaso
            _sleep(RETRY_BACKOFF)
            continue
        except Exception:  # noqa: BLE001
            log.exception("Error inesperado; reintentando en %ss", RETRY_BACKOFF)
            if local is not None and not local.closed:
                local.rollback()
            _sleep(RETRY_BACKOFF)
            continue

        _sleep(POLL_INTERVAL)

    for c in (remote, local):
        if c is not None and not c.closed:
            c.close()
    log.info("Servicio detenido limpiamente")


def _sleep(seconds: int) -> None:
    """Sleep interrumpible por señal de parada."""
    for _ in range(seconds):
        if not _running:
            return
        time.sleep(1)


if __name__ == "__main__":
    main()
