# Guía de despliegue del servicio (máquinas Linux)

Repite estos pasos en **cada** máquina. Ambas usan **los mismos datos de
base local**; lo **único** que cambia entre ellas es `TRIBUNA_ID`.

**Datos de base local (iguales en ambas máquinas):**

| Parámetro | Valor |
|-----------|-------|
| Base local | `bdmolinetesnodo1` |
| Usuario | `molinetes1` |
| Password | `moli2026` |
| Tabla destino | `invitados` |

**Lo único distinto por máquina:**

| Máquina | TRIBUNA_ID |
|---------|-----------|
| Nodo 1  | 1 (OCCIDENTAL) |
| Nodo 2  | 2 (ORIENTAL)   |

> ⚠️ **Este documento contiene contraseñas.** No lo compartas ni lo subas
> a un repositorio.

---

## Paso 0 · Requisitos previos

En la máquina Linux:

```bash
# Python 3 y venv (Debian/Ubuntu)
sudo apt update && sudo apt install -y python3 python3-venv

# Debe existir PostgreSQL local con la tabla 'invitados' (ya lo tienes).

# Probar que hay salida de red hacia la nube:
PGPASSWORD='<PASS_NUBE>' psql -h 86.48.21.61 -U usrapps -d db_edcph -c "SELECT 1"
```

Si el último comando responde `1`, hay conectividad. Si se queda colgado,
revisa el firewall / salida a Internet de la máquina.

---

## Paso 1 · Llevar los archivos a la máquina

Desde tu Mac (donde está este proyecto), copia la carpeta del servicio y el
esquema local a la máquina correspondiente.

**Máquina OCCIDENTAL (Nodo 1, tribuna 1):**

```bash
scp -r sync/service                        edc-occidental@100.100.34.101:/tmp/edc-sync-src
scp    sync/schema_local/01_invitados.sql  edc-occidental@100.100.34.101:/tmp/
```

**Máquina ORIENTAL (Nodo 2, tribuna 2):**

```bash
scp -r sync/service                        edc-oriental@100.67.92.103:/tmp/edc-sync-src
scp    sync/schema_local/01_invitados.sql  edc-oriental@100.67.92.103:/tmp/
```

(También sirve un USB o `git`; solo necesitas esos archivos en la máquina.)

---

## Paso 2 · Completar el esquema local

Ya tienes la tabla `invitados`; falta la tabla del cursor `sync_control`.
El script es seguro (`IF NOT EXISTS`, no recrea `invitados`):

```bash
PGPASSWORD='moli2026' psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -f /tmp/01_invitados.sql
```

> **Si aparece `ERROR: permission denied for schema public`:** desde
> PostgreSQL 15 el usuario `molinetes1` no puede crear tablas en `public`
> por defecto. Otórgale el permiso con el superusuario y repite el comando
> anterior (las tablas nuevas quedarán como propiedad de `molinetes1`, con
> todos los permisos que el servicio necesita):
>
> ```bash
> sudo -u postgres psql -d bdmolinetesnodo1 \
>   -c "GRANT CREATE ON SCHEMA public TO molinetes1;"
> ```
>
> Si `sudo -u postgres psql` no funciona, usa el superusuario/instalación
> de Postgres que corresponda a esa máquina.

> **Si tu tabla `invitados` ya existía SIN la columna `sincronizado`** (el
> servicio la necesita), el script intenta agregarla con un `ALTER TABLE`.
> Si eso falla con `must be owner of table invitados`, agrégala con el
> superusuario:
>
> ```bash
> sudo -u postgres psql -d bdmolinetesnodo1 \
>   -c "ALTER TABLE invitados ADD COLUMN IF NOT EXISTS sincronizado boolean NOT NULL DEFAULT false;"
> ```

Verifica que la tabla tenga las columnas correctas (incluida `sincronizado`)
y que exista `sync_control`:

```bash
PGPASSWORD='moli2026' psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "\d invitados" -c "\dt"
```

---

## Paso 3 · Instalar el servicio y sus dependencias (venv aislado)

```bash
sudo mkdir -p /opt/edc-sync
sudo cp /tmp/edc-sync-src/sync_tribuna.py /opt/edc-sync/

# venv PROPIO: no toca el Python de tus otros servicios
sudo python3 -m venv /opt/edc-sync/.venv
sudo /opt/edc-sync/.venv/bin/pip install --upgrade pip
sudo /opt/edc-sync/.venv/bin/pip install -r /tmp/edc-sync-src/requirements.txt
```

---

## Paso 4 · Configurar el `.env` (aquí se define la tribuna)

```bash
sudo cp /tmp/edc-sync-src/.env.example /opt/edc-sync/.env
sudo nano /opt/edc-sync/.env
```

Completa (todo es igual en ambas máquinas **excepto `TRIBUNA_ID`**):

```ini
# --- Nube (igual en ambas máquinas) ---
REMOTE_DB_HOST=86.48.21.61
REMOTE_DB_PORT=5432
REMOTE_DB_NAME=db_edcph
REMOTE_DB_USERNAME=usrapps
REMOTE_DB_PASSWORD=<PASS_NUBE>

# --- Base local (igual en ambas máquinas) ---
LOCAL_DB_HOST=localhost
LOCAL_DB_PORT=5432
LOCAL_DB_NAME=bdmolinetesnodo1
LOCAL_DB_USERNAME=molinetes1
LOCAL_DB_PASSWORD=moli2026

# --- Tribuna de ESTA máquina (LO ÚNICO QUE CAMBIA) ---
# Nodo 1 -> TRIBUNA_ID=1 (OCCIDENTAL)  |  Nodo 2 -> TRIBUNA_ID=2 (ORIENTAL)
TRIBUNA_ID=1
```

> ⚠️ **No pongas comentarios `#` en la misma línea que un valor.** systemd
> los toma como parte del valor y el servicio falla al arrancar (p. ej.
> `POLL_INTERVAL debe ser un entero`). Comenta siempre en líneas aparte.
>
> La tabla destino es `invitados` (fija en el servicio). En el Nodo 2, lo
> único que cambia es `TRIBUNA_ID=2`.

Protege el archivo (tiene contraseñas):

```bash
sudo chmod 600 /opt/edc-sync/.env
```

---

## Paso 5 · Probar a mano ANTES de systemd

Conviene ver que arranca bien antes de dejarlo como servicio:

```bash
cd /opt/edc-sync
set -a; . ./.env; set +a
./.venv/bin/python sync_tribuna.py
```

Deberías ver en consola algo como:

```
INFO Iniciando sync para tribuna 1 (canal tribuna:1)
INFO Conectado a la nube
INFO Conectado a la base local
INFO Reconcile: N filas de la tribuna 1 sincronizadas (podadas M no ingresadas)
```

> `podadas M` = filas locales retiradas por ya no estar en el snapshot de
> la tribuna (bajas, reasignaciones o eventos desactivados) y que **no**
> habían ingresado. En un despliegue nuevo suele ser `0`.

Corta con `Ctrl+C`. Verifica que los datos llegaron:

```bash
PGPASSWORD='moli2026' psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "SELECT count(*) FROM invitados;"
PGPASSWORD='moli2026' psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "SELECT * FROM sync_control;"
```

---

## Paso 6 · Instalar como servicio systemd

### ¿Por qué este paso?

En el Paso 5 corriste el servicio a mano, pero eso tiene dos problemas: si
cierras la terminal el proceso muere, y si la máquina se reinicia no vuelve
a arrancar solo. **systemd** es el gestor de servicios de Linux (el mismo
que ya usas para tus servicios Python/Java): se encarga de arrancar el
proceso al encender la máquina, mantenerlo corriendo en segundo plano,
reiniciarlo si se cae y guardar sus logs. Para eso se le entrega un
archivo de definición llamado **unit** (`edc-sync.service`).

### Qué dice el archivo unit (para que lo entiendas antes de tocarlo)

```ini
[Unit]
Description=...                         # nombre legible del servicio
After=network-online.target postgresql.service   # arranca DESPUÉS de que
Wants=network-online.target            #   haya red y Postgres estén listos

[Service]
Type=simple                            # es un proceso que se queda corriendo
User=gates                             # con qué usuario del sistema corre
WorkingDirectory=/opt/edc-sync         # carpeta base del servicio
EnvironmentFile=/opt/edc-sync/.env     # de aquí lee las variables (tu .env)
ExecStart=/opt/edc-sync/.venv/bin/python /opt/edc-sync/sync_tribuna.py
Restart=always                         # si el proceso muere, relánzalo
RestartSec=10                          #   esperando 10 s entre reintentos
KillSignal=SIGTERM                     # al detener, avisa para cierre limpio
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target             # se habilita para el arranque normal
```

### 6.1 · Editar el unit para TU máquina

Antes de instalarlo hay **tres cosas** que debes revisar/ajustar:

```bash
nano /tmp/edc-sync-src/edc-sync.service
```

1. **`User=gates`** → ya viene configurado con tu usuario (`gates`). Solo
   verifica que ese usuario pueda leer `/opt/edc-sync/` y su `.env`. (La
   conexión a Postgres local va con usuario/contraseña propios en el
   `.env`, así que no depende del usuario del sistema.)
2. **`After=... postgresql.service`** → si tu PostgreSQL local se llama
   distinto en systemd, ponlo aquí para que el sync arranque *después* de
   la base. Para saber su nombre real:
   ```bash
   systemctl list-units --type=service | grep -i postgres
   ```
   (Suele ser `postgresql.service` o `postgresql@16-main.service`.)
3. **Nombre único** → confirma que no tienes ya un servicio llamado
   `edc-sync`, para no pisar otro:
   ```bash
   systemctl list-units --type=service | grep -i edc
   ```
   Si hubiera conflicto, renombra el archivo (p. ej. `edc-cdc-sync.service`)
   y usa ese nombre en los comandos siguientes.

### 6.2 · Ajustar los permisos de los archivos

En los Pasos 3 y 4 copiaste todo con `sudo`, así que los archivos quedaron
como propiedad de **`root`**. Pero el servicio correrá como **`gates`**, y
al `.env` le pusiste `chmod 600` (solo el dueño lo lee). Si el dueño sigue
siendo `root`, `gates` **no podrá leer el `.env`** y el servicio fallará al
arrancar. Para evitarlo, haz a `gates` dueño de todo:

```bash
# Ver quién es el dueño actual (si aparece 'root', necesitas el chown):
ls -l /opt/edc-sync

# Poner a 'gates' como propietario de la carpeta y todo su contenido:
sudo chown -R gates:gates /opt/edc-sync
```

> `chown` = *change owner* (cambia el dueño). `-R` = recursivo (la carpeta y
> todo lo de adentro). `gates:gates` = usuario:grupo nuevos. Es un ajuste de
> permisos, para alinear "quién posee los archivos" con "quién ejecuta el
> servicio".

### 6.3 · Instalar y arrancar

```bash
# 1) Copiar el unit a la carpeta donde systemd busca los servicios
sudo cp /tmp/edc-sync-src/edc-sync.service /etc/systemd/system/

# 2) Avisar a systemd que lea los archivos nuevos/cambiados
sudo systemctl daemon-reload

# 3) Habilitar (arranca solo en cada boot) + arrancar YA. Todo en uno:
sudo systemctl enable --now edc-sync
```

> `enable` = que arranque automáticamente al encender la máquina.
> `--now`  = además, arráncalo en este mismo momento sin esperar al reinicio.

### 6.4 · Verificar que quedó corriendo

```bash
# Estado general: debe decir "active (running)"
sudo systemctl status edc-sync

# Ver los logs en vivo (los mismos mensajes del Paso 5)
sudo journalctl -u edc-sync -f
```

Si `status` muestra `active (running)` y en los logs ves los mensajes de
`Reconcile:` / `Poll:`, el servicio ya está operando de forma permanente.
Si mostrara `failed`, revisa los logs con `journalctl -u edc-sync -e` para
ver el error (lo más común: un dato mal puesto en el `.env` o el usuario
sin permisos sobre la base local).

---

## Paso 7 · Comprobar que sincroniza

```bash
# El cursor debe ir avanzando con la actividad:
PGPASSWORD='moli2026' psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "SELECT * FROM sync_control;"

# Conteo local vs nube (para la tribuna de esta máquina, p. ej. 2):
PGPASSWORD='moli2026' psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "SELECT count(*) FROM invitados;"
```

En la nube, el conteo de referencia de esa tribuna (**solo eventos
activos**, igual que lo que replica el servicio):

```sql
SELECT count(*) FROM visitantexevento ve
JOIN suites  s ON s.id_suite = ve.id_suite
JOIN eventos e ON e.id       = ve.id_evento
WHERE s.tribuna = 2
  AND lower(e.estado) = 'activo';
```

Ambos deben coincidir *aproximadamente* (con unos segundos de desfase por
el poll). El conteo local puede quedar **ligeramente por encima**: el
`reconcile` conserva a quienes **ya ingresaron** (`estado` marcado) aunque
después los borren o reasignen en la nube. Los que **no** han ingresado sí
se podan y cuadran con la nube.

---

## Comandos útiles de operación

```bash
sudo systemctl restart edc-sync     # reiniciar
sudo systemctl stop edc-sync        # detener
sudo systemctl disable edc-sync     # que no arranque en el boot
journalctl -u edc-sync --since "10 min ago"

# Forzar re-sincronización total desde cero (si hiciera falta):
PGPASSWORD='moli2026' psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "UPDATE sync_control SET last_outbox_id = 0;"
sudo systemctl restart edc-sync
```
