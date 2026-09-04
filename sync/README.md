# Sincronización nube → local por tribuna (CDC Outbox + polling)

Mecanismo para replicar los cambios de la tabla `visitantexevento` (nube)
hacia una tabla `invitados` en máquinas Linux locales, **filtrando por
tribuna**. Cada máquina se configura con una sola variable: `TRIBUNA_ID`.

## Arquitectura

```
   NUBE (db_edcph, PostgreSQL 16)                 MÁQUINA LOCAL (una por tribuna)
   ┌───────────────────────────────┐              ┌─────────────────────────────┐
   │  visitantexevento             │              │  sync_tribuna.py (systemd)  │
   │        │ INSERT/UPDATE        │              │                             │
   │        ▼                      │  1. POLL     │   lee cdc_outbox            │
   │  trigger  ──►  cdc_outbox ────┼──────────────┼──►  WHERE tribuna = ID      │
   │  (resuelve tribuna vía suites)│  (id > cursor)│    y hace UPSERT en local  │
   │                               │              │            │                │
   │  suites+eventos (JOIN)  ◄─────┼──────────────┼── 2. RECONCILE (snapshot)   │
   │  (solo eventos activos)       │   periódico   │    UPSERT + poda no ingres. │
   │                               │               │            ▼                │
   └───────────────────────────────┘              │      invitados              │
                                                   │      sync_control (cursor)  │
                                                   └─────────────────────────────┘
```

Dos caminos complementarios:

1. **Poll del outbox** (baja latencia): el trigger anexa cada cambio a
   `cdc_outbox` con la tribuna ya resuelta. Cada máquina lee solo las
   filas de *su* tribuna con `id` mayor a su cursor y las aplica con
   `UPSERT`. El cursor se guarda **en la base local** (`sync_control`),
   así cada máquina es autónoma y **no escribe en la nube**.

2. **Reconciliación por snapshot** (red de seguridad): cada
   `RECONCILE_INTERVAL` segundos trae el estado completo de la tribuna
   (`visitantexevento JOIN suites JOIN eventos`, **solo eventos activos**)
   y lo aplica con `UPSERT`. Garantiza convergencia aunque el outbox
   pierda algún evento y hace el **bootstrap** la primera vez. Además
   **poda** de la base local las filas que ya no están en el snapshot
   (bajas, reasignaciones a otra tribuna o eventos desactivados), pero
   **solo las que no han ingresado** (`estado IS NULL`).

### Garantías y decisiones

- **UPSERT + poda selectiva**: los cambios se aplican con `UPSERT`
  (`INSERT ... ON CONFLICT DO UPDATE`). El `reconcile` borra las filas
  locales que salieron del snapshot de la tribuna, pero **solo las no
  ingresadas** (`estado IS NULL`): las que ya ingresaron se conservan
  siempre (historial de dominio local). **Salvaguarda**: si el snapshot
  viniera vacío, no se borra nada.
- **Solo eventos activos**: únicamente se sincronizan los registros cuyo
  `id_evento` esté `Activo` en la tabla `eventos`. El filtro se aplica en
  los dos caminos: el trigger no emite al outbox los eventos inactivos, y
  el `reconcile` los excluye del snapshot (comparación case-insensitive
  `lower(estado) = 'activo'`). Si un evento se activa después, el próximo
  `reconcile` trae todos sus registros; si se desactiva, la poda los
  retira de los nodos (salvo los ya ingresados).
- **`estado` y `obsingreso` son de dominio 100% LOCAL**: los gestiona el
  servicio de puerta (Python/Java) de cada máquina. La nube **nunca** los
  lee ni los escribe:
    - El trigger **no emite evento** cuando un UPDATE cambia *solo*
      `estado` y/o `obsingreso` (además, son los cambios más frecuentes en
      control de acceso → outbox liviano).
    - El `UPSERT` **no incluye** las columnas `estado` ni `obsingreso`: las
      altas nuevas nacen en `NULL` (= "no ingresado" en local) y las filas
      existentes nunca se tocan en esas columnas (ni en poll ni en
      reconcile).
    - En un cambio mixto (p. ej. `estado` + `id_suite`), se sincronizan
      los demás campos y `estado`/`obsingreso` se ignoran.
    - Reparto de columnas sin solape: este servicio escribe `id_suite`,
      `sincronizado` (y crea filas); el servicio de puerta escribe `estado`
      y `obsingreso`.
- **Idempotente / entrega al menos una vez**: reprocesar un cambio no
  causa daño; por eso el orden es *aplicar en local → avanzar cursor* en
  la misma transacción local.
- **No rompe producción**: el trigger es *best-effort* (bloque
  `EXCEPTION`); si el CDC fallara, la escritura en `visitantexevento`
  sigue adelante y el `reconcile` recupera el estado.
- **Filtro por tribuna**: resuelto en la nube (trigger) y reforzado en el
  `WHERE`. Una sola variable por máquina.

## Sincronización nube→nube: `visitantexevento` → `amparadoxevento`

Además del flujo hacia los nodos, existe un segundo mecanismo que vive
**enteramente dentro de la nube, sin intervención de los nodos**. Mantiene
alineadas las columnas `estado` y `obsingreso` entre `visitantexevento` y la
tabla `amparadoxevento`.

`amparadoxevento` relaciona a cada *amparado* con un par visitante-evento
mediante la FK compuesta `(id_visitante, id_evento)`, y puede haber **varias**
filas por cada par. Cuando el servicio de puerta hace un `UPDATE` de
`estado`/`obsingreso` sobre `visitantexevento`, esos mismos valores deben
reflejarse en todos los amparados de ese par.

- **Trigger dedicado e independiente**: `trg_visitantexevento_amparado_sync`
  (`AFTER UPDATE`, función `fn_visitantexevento_amparado_sync`), en
  `schema_nube/06_amparadoxevento_sync.sql`. Es una pieza aparte del trigger
  del outbox: aquel **descarta** los cambios de solo `estado`/`obsingreso`
  (no interesan a los nodos), mientras que este reacciona **justo** a ellos.
  Ambos triggers conviven sobre `visitantexevento` sin pisarse.
- **Se dispara solo si cambió `estado` u `obsingreso`** (`IS DISTINCT FROM`,
  null-safe). Propaga `NEW.estado`/`NEW.obsingreso` a **todas** las filas de
  `amparadoxevento` con la misma `(id_visitante, id_evento)`; un filtro extra
  en el `WHERE` evita reescribir las que ya tienen el valor correcto.
- **Solo `UPDATE`** (no `INSERT`): los amparados referencian una fila de
  `visitantexevento` preexistente, así que en el alta inicial no hay nada que
  propagar.
- **Best-effort** (bloque `EXCEPTION`, igual que el outbox): si la propagación
  fallara, se registra un `WARNING` pero el `UPDATE` original sobre
  `visitantexevento` **nunca** se aborta. Corre en la misma transacción, así
  que en condiciones normales es atómico con ese `UPDATE`.
- **Sin reconciliación**: a diferencia del sync a nodos, aquí no hay una red
  de seguridad periódica. El riesgo es mínimo (un `UPDATE` de dos columnas en
  la misma base), pero conviene tenerlo presente.

## Despliegue

### 1. En la NUBE (una sola vez, requiere permiso de escritura)

```bash
./query.sh sync/schema_nube/01_outbox.sql
./query.sh sync/schema_nube/02_trigger.sql
# opcional, para retención:
./query.sh sync/schema_nube/03_retencion.sql
# sincronización nube→nube (requiere la tabla amparadoxevento):
./query.sh sync/schema_nube/06_amparadoxevento_sync.sql
```

> ⚠️ Esto crea una tabla y un trigger en la base de **producción**. El
> trigger añade a cada INSERT/UPDATE de `visitantexevento` dos lookups por
> PK (`eventos` para saber si está activo y `suites` para la tribuna) más
> un INSERT al outbox. Sobrecarga mínima, pero conviene aplicarlo en una
> ventana de bajo tráfico. `02_trigger.sql` es `CREATE OR REPLACE` +
> `DROP/CREATE TRIGGER`: para reaplicarlo sin ninguna ventana sin trigger,
> ejecútalo con `psql --single-transaction`.

### 2. En cada MÁQUINA LOCAL

El repositorio ya está clonado en cada máquina (`~/Documentos/gates_project`),
así que **no hay que transferir archivos**: el servicio corre *desde el repo*
y se actualiza con `git pull` + `systemctl restart`.

```bash
export REPO_DIR="$HOME/Documentos/gates_project"
cd "$REPO_DIR" && git pull

# a) Crear el esquema local
psql -d bdmolinetesnodo1 -f sync/schema_local/01_invitados.sql

# b) Entorno virtual DENTRO del repo (sin sudo: debe ser de tu usuario)
cd "$REPO_DIR/sync/service"
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# c) Configurar (¡aquí se define la tribuna de ESTA máquina!)
sudo mkdir -p /etc/gates
sudo cp .env.example /etc/gates/edc-sync.env
sudo chmod 600 /etc/gates/edc-sync.env
sudo nano /etc/gates/edc-sync.env   # ajustar credenciales y TRIBUNA_ID

# d) Instalar el unit (la plantilla lleva marcadores que se sustituyen aquí)
sudo sed -e "s|__SERVICE_USER__|$USER|g" -e "s|__REPO_DIR__|$REPO_DIR|g" \
    "$REPO_DIR/sync/service/edc-sync.service" > /etc/systemd/system/edc-sync.service
sudo systemctl daemon-reload
sudo systemctl enable --now edc-sync
sudo journalctl -u edc-sync -f     # ver logs en vivo
```

Paso a paso, con verificaciones y solución de problemas: **`DESPLIEGUE.md`**.

Para probar sin systemd:

```bash
cd "$REPO_DIR/sync/service"
set -a; . <(sudo cat /etc/gates/edc-sync.env); set +a
.venv/bin/python sync_tribuna.py
```

Para actualizar tras un cambio:

```bash
cd "$REPO_DIR" && git pull
sudo systemctl restart edc-sync
```

## Configuración (variables de entorno)

| Variable | Descripción | Default |
|---|---|---|
| `REMOTE_DB_*` | Conexión a la nube (origen) | — |
| `LOCAL_DB_*` | Conexión a la base local (destino) | `localhost` |
| `TRIBUNA_ID` | **La única variable que cambia por máquina** (1=OCC, 2=ORI, 3=NOR, 4=SUR) | — |
| `POLL_INTERVAL` | Segundos entre lecturas del outbox | `5` |
| `RECONCILE_INTERVAL` | Segundos entre snapshots completos (`0` desactiva) | `300` |
| `BATCH_SIZE` | Filas por lote de poll | `500` |
| `RETRY_BACKOFF` | Espera tras un error de conexión | `10` |

## Notas operativas

- **Escalar a varias máquinas** = copiar el mismo despliegue cambiando
  solo `TRIBUNA_ID`. Los cursores no chocan porque cada máquina usa su
  propia base local (`canal = 'tribuna:N'`).
- **Reset de una máquina**: `UPDATE sync_control SET last_outbox_id = 0;`
  y el próximo `reconcile` la deja al día (o borra la tabla `invitados`
  para reconstruir desde cero).
- **Retención del outbox**: crece con cada cambio; ejecuta
  `03_retencion.sql` periódicamente (o vía `pg_cron`).
- **Huecos de secuencia**: un `bigserial` puede confirmarse fuera de
  orden bajo concurrencia; el `reconcile` periódico corrige cualquier
  fila que el cursor pudiera saltarse, por eso conviene dejarlo activo.
```
