# Guía de despliegue del servicio de sync (máquinas Linux)

Este proyecto ya **no** es una carpeta suelta: vive dentro del repositorio
general `gates_project`, que está **clonado en las propias máquinas Linux**.
Por eso **ya no hay transferencias de archivos entre máquinas** (nada de
`scp` ni USB): todo se hace *dentro* de la máquina, sobre el repo, y para
actualizar el servicio basta con `git pull` + reiniciar (ver
[Actualizar a una versión nueva](#actualizar-a-una-versión-nueva)).

Repite estos pasos en **cada** máquina. Ambas usan **los mismos datos de
base local**; lo **único** que cambia entre ellas es `TRIBUNA_ID` (y,
lógicamente, el usuario del sistema y la ruta del repo).

| Máquina | Usuario del sistema | Ruta del repo | `TRIBUNA_ID` |
|---|---|---|---|
| Nodo 1 · OCCIDENTAL | `edc-occidental` | `/home/edc-occidental/Documentos/gates_project` | `1` |
| Nodo 2 · ORIENTAL   | `edc-oriental`   | `/home/edc-oriental/Documentos/gates_project`   | `2` |

**Datos de base local (iguales en ambas máquinas):**

| Parámetro | Valor |
|-----------|-------|
| Base local | `bdmolinetesnodo1` |
| Usuario | `molinetes1` |
| Password | *(la de `molinetes1`; no se escribe en este archivo — ver abajo)* |
| Tabla destino | `invitados` |

> 🔐 **Este documento vive en el repositorio**, así que no lleva
> contraseñas escritas. Los comandos usan variables de shell que defines
> **una sola vez** al abrir la sesión (Paso 0). Las contraseñas reales
> quedan únicamente en `/etc/gates/edc-sync.env` (permisos `600`, fuera
> del repo).

---

## Paso 0 · Preparar la sesión de trabajo

Conéctate a la máquina (o siéntate frente a ella) **con su propio usuario**
(`edc-occidental` o `edc-oriental`) y define las variables que usarán todos
los comandos de esta guía:

```bash
# Ruta del repo clonado en ESTA máquina (igual en ambas, cambia el $HOME):
export REPO_DIR="$HOME/Documentos/gates_project"

# Contraseña de la base local, sin dejarla escrita en pantalla ni en el historial:
read -rsp 'Password de molinetes1: ' LOCAL_DB_PASS; export LOCAL_DB_PASS; echo

cd "$REPO_DIR"
```

> Si abres una terminal nueva más adelante, vuelve a ejecutar este bloque:
> las variables se pierden al cerrar la sesión.

### Requisitos previos

```bash
# Python 3 y venv (Debian/Ubuntu)
sudo apt update && sudo apt install -y python3 python3-venv git

# Debe existir PostgreSQL local con la tabla 'invitados' (ya lo tienes).

# Probar que hay salida de red hacia la nube:
read -rsp 'Password de usrapps (nube): ' REMOTE_DB_PASS; echo
PGPASSWORD="$REMOTE_DB_PASS" psql -h 86.48.21.61 -U usrapps -d db_edcph -c "SELECT 1"
```

Si el último comando responde `1`, hay conectividad. Si se queda colgado,
revisa el firewall / salida a Internet de la máquina.

### Traer el repo al día

Como el código ya está en la máquina, "llevar los archivos" se reduce a
sincronizar el repositorio:

```bash
cd "$REPO_DIR"
git status          # debe estar limpio; si hay cambios locales, resuélvelos antes
git pull
```

A partir de aquí, los archivos que necesitas están en:

| Qué | Dónde (dentro del repo) |
|---|---|
| Servicio Python | `sync/service/sync_tribuna.py` |
| Dependencias | `sync/service/requirements.txt` |
| Plantilla de configuración | `sync/service/.env.example` |
| Plantilla del unit de systemd | `sync/service/edc-sync.service` |
| Esquema de la base local | `sync/schema_local/01_invitados.sql` |
| Scripts de la nube | `sync/schema_nube/*.sql` |

---

## Paso 1 · Completar el esquema local

Ya tienes la tabla `invitados`; falta la tabla del cursor `sync_control`.
El script es seguro (`IF NOT EXISTS`, no recrea `invitados`) y se ejecuta
**directamente desde el repo**:

```bash
PGPASSWORD="$LOCAL_DB_PASS" psql -h localhost -U molinetes1 -d bdmolinetesnodo1 \
  -f "$REPO_DIR/sync/schema_local/01_invitados.sql"
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
PGPASSWORD="$LOCAL_DB_PASS" psql -h localhost -U molinetes1 -d bdmolinetesnodo1 \
  -c "\d invitados" -c "\dt"
```

---

## Paso 2 · Crear el entorno virtual (dentro del repo)

El servicio se ejecuta **desde el repo**, con un venv propio que no toca el
Python de tus otros servicios. El venv vive en `sync/service/.venv/` y está
ignorado por git (no se sube ni se pisa con `git pull`).

```bash
cd "$REPO_DIR/sync/service"

# SIN sudo: el venv debe ser propiedad de tu usuario, no de root.
python3 -m venv .venv
.venv/bin/pip install --upgrade pip
.venv/bin/pip install -r requirements.txt
```

Comprueba que quedó instalado:

```bash
.venv/bin/python -c "import psycopg; print(psycopg.__version__)"
```

> ⚠️ **No uses `sudo` aquí.** Si el venv queda como `root`, el servicio
> (que corre con tu usuario) no podrá usarlo. Si te pasó, arréglalo con
> `sudo rm -rf .venv` y repite el bloque sin `sudo`.

---

## Paso 3 · Configurar el entorno (aquí se define la tribuna)

La configuración **no va dentro del repo** (tiene contraseñas): vive en
`/etc/gates/edc-sync.env`, junto a los otros archivos de entorno del
proyecto (`bridge.env`, `ms-gates.env`).

```bash
sudo mkdir -p /etc/gates
sudo cp "$REPO_DIR/sync/service/.env.example" /etc/gates/edc-sync.env
sudo chmod 600 /etc/gates/edc-sync.env
sudo nano /etc/gates/edc-sync.env
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
LOCAL_DB_PASSWORD=<PASS_LOCAL>

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

Verifica que solo el dueño pueda leerlo:

```bash
ls -l /etc/gates/edc-sync.env      # debe mostrar -rw------- root root
```

---

## Paso 4 · Probar a mano ANTES de systemd

Conviene ver que arranca bien antes de dejarlo como servicio:

```bash
cd "$REPO_DIR/sync/service"
set -a; . <(sudo cat /etc/gates/edc-sync.env); set +a
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
PGPASSWORD="$LOCAL_DB_PASS" psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "SELECT count(*) FROM invitados;"
PGPASSWORD="$LOCAL_DB_PASS" psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "SELECT * FROM sync_control;"
```

---

## Paso 5 · Instalar como servicio systemd

### ¿Por qué este paso?

En el Paso 4 corriste el servicio a mano, pero eso tiene dos problemas: si
cierras la terminal el proceso muere, y si la máquina se reinicia no vuelve
a arrancar solo. **systemd** es el gestor de servicios de Linux (el mismo
que ya usas para `gates-pipeline`): arranca el proceso al encender la
máquina, lo mantiene corriendo en segundo plano, lo reinicia si se cae y
guarda sus logs. Para eso se le entrega un archivo de definición llamado
**unit** (`edc-sync.service`).

### Qué dice el archivo unit (para que lo entiendas antes de tocarlo)

La plantilla está versionada en `sync/service/edc-sync.service` y trae dos
**marcadores** que se sustituyen al instalarla (`__SERVICE_USER__` y
`__REPO_DIR__`), porque la ruta del repo depende del usuario de cada
máquina:

```ini
[Unit]
Description=...                         # nombre legible del servicio
After=network-online.target postgresql.service   # arranca DESPUÉS de que
Wants=network-online.target             #   haya red y Postgres estén listos

[Service]
Type=simple                             # es un proceso que se queda corriendo
User=__SERVICE_USER__                   # usuario dueño del repo (edc-occidental/edc-oriental)
WorkingDirectory=__REPO_DIR__/sync/service        # el servicio corre DESDE el repo
EnvironmentFile=/etc/gates/edc-sync.env # de aquí lee las variables (Paso 3)
ExecStart=__REPO_DIR__/sync/service/.venv/bin/python -u __REPO_DIR__/sync/service/sync_tribuna.py
Restart=always                          # si el proceso muere, relánzalo
RestartSec=10                           #   esperando 10 s entre reintentos
KillSignal=SIGTERM                      # al detener, avisa para cierre limpio
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target              # se habilita para el arranque normal
```

Como `ExecStart` apunta **al repo**, un `git pull` actualiza el servicio sin
copiar nada.

### 5.1 · Instalar el unit con los valores de ESTA máquina

Un solo comando genera el unit real a partir de la plantilla, sustituyendo
el usuario y la ruta:

```bash
sudo sed -e "s|__SERVICE_USER__|$USER|g" \
         -e "s|__REPO_DIR__|$REPO_DIR|g" \
         "$REPO_DIR/sync/service/edc-sync.service" \
         > /etc/systemd/system/edc-sync.service

# Revisa que quedó bien (sin marcadores '__' sueltos):
cat /etc/systemd/system/edc-sync.service
```

Antes de arrancar, comprueba dos cosas del entorno de la máquina:

1. **Nombre real de PostgreSQL en systemd.** Si no se llama
   `postgresql.service`, corrige el `After=` del unit para que el sync
   arranque *después* de la base:
   ```bash
   systemctl list-units --type=service | grep -i postgres
   ```
   (Suele ser `postgresql.service` o `postgresql@16-main.service`.)
2. **Nombre único.** Confirma que no exista ya otro servicio `edc-sync`:
   ```bash
   systemctl list-units --type=service | grep -i edc
   ```
   Si hubiera conflicto, instala el unit con otro nombre (p. ej.
   `edc-cdc-sync.service`) y usa ese nombre en los comandos siguientes.

### 5.2 · Permisos

Con el nuevo esquema **no hace falta `chown`**: el repo y el venv ya son
propiedad de tu usuario (`edc-occidental` / `edc-oriental`), que es el mismo
con el que corre el servicio. Solo verifica que sea así:

```bash
ls -ld "$REPO_DIR/sync/service" "$REPO_DIR/sync/service/.venv"
```

Si alguna de esas rutas apareciera como `root` (por haber usado `sudo` en el
Paso 2), devuélvela a tu usuario:

```bash
sudo chown -R "$USER:$USER" "$REPO_DIR/sync/service"
```

### 5.3 · Arrancar

```bash
# 1) Avisar a systemd que lea los archivos nuevos/cambiados
sudo systemctl daemon-reload

# 2) Habilitar (arranca solo en cada boot) + arrancar YA. Todo en uno:
sudo systemctl enable --now edc-sync
```

> `enable` = que arranque automáticamente al encender la máquina.
> `--now`  = además, arráncalo en este mismo momento sin esperar al reinicio.

### 5.4 · Verificar que quedó corriendo

```bash
# Estado general: debe decir "active (running)"
sudo systemctl status edc-sync

# Ver los logs en vivo (los mismos mensajes del Paso 4)
sudo journalctl -u edc-sync -f
```

Si `status` muestra `active (running)` y en los logs ves los mensajes de
`Reconcile:` / `Poll:`, el servicio ya está operando de forma permanente.
Si mostrara `failed`, revisa los logs con `journalctl -u edc-sync -e` para
ver el error (lo más común: un dato mal puesto en `/etc/gates/edc-sync.env`,
el venv creado con `sudo`, o el usuario sin permisos sobre la base local).

---

## Paso 6 · Comprobar que sincroniza

```bash
# El cursor debe ir avanzando con la actividad:
PGPASSWORD="$LOCAL_DB_PASS" psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "SELECT * FROM sync_control;"

# Conteo local vs nube (para la tribuna de esta máquina, p. ej. 2):
PGPASSWORD="$LOCAL_DB_PASS" psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "SELECT count(*) FROM invitados;"
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

## Actualizar a una versión nueva

Este es el flujo que reemplaza a las antiguas copias entre máquinas:

```bash
cd "$REPO_DIR"
git pull

# Solo si cambió requirements.txt:
sync/service/.venv/bin/pip install -r sync/service/requirements.txt

sudo systemctl restart edc-sync
sudo journalctl -u edc-sync -f
```

Casos especiales:

- **Cambió `edc-sync.service`** (la plantilla del unit) → repite el
  Paso 5.1 y luego `sudo systemctl daemon-reload && sudo systemctl restart edc-sync`.
- **Cambió `.env.example`** (variables nuevas) → compara con tu archivo real
  y añade lo que falte:
  ```bash
  diff <(sudo grep -oP '^[A-Z_]+(?==)' /etc/gates/edc-sync.env | sort) \
       <(grep -oP '^[A-Z_]+(?==)' "$REPO_DIR/sync/service/.env.example" | sort)
  ```
- **Cambió `schema_local/01_invitados.sql`** → vuelve a ejecutarlo (es
  idempotente, Paso 1).

---

## Scripts de la nube (una sola vez, desde cualquier sitio)

Los scripts de `sync/schema_nube/` se aplican **una sola vez** sobre la base
de la nube. Al estar el repo en la máquina, también puedes ejecutarlos desde
aquí:

```bash
cd "$REPO_DIR"
read -rsp 'Password de usrapps (nube): ' REMOTE_DB_PASS; echo

for f in sync/schema_nube/01_outbox.sql \
         sync/schema_nube/02_trigger.sql \
         sync/schema_nube/03_retencion.sql \
         sync/schema_nube/06_amparadoxevento_sync.sql; do
  PGPASSWORD="$REMOTE_DB_PASS" psql -h 86.48.21.61 -U usrapps -d db_edcph \
    --single-transaction -v ON_ERROR_STOP=1 -f "$f"
done
```

> ⚠️ Esto toca la base de **producción** (crea una tabla y triggers).
> Aplícalo **una sola vez**, desde **una sola** de las máquinas, y en una
> ventana de bajo tráfico. Ver `README.md` para el detalle de cada script.

---

## Comandos útiles de operación

```bash
sudo systemctl restart edc-sync     # reiniciar
sudo systemctl stop edc-sync        # detener
sudo systemctl disable edc-sync     # que no arranque en el boot
journalctl -u edc-sync --since "10 min ago"

# Forzar re-sincronización total desde cero (si hiciera falta):
PGPASSWORD="$LOCAL_DB_PASS" psql -h localhost -U molinetes1 -d bdmolinetesnodo1 -c "UPDATE sync_control SET last_outbox_id = 0;"
sudo systemctl restart edc-sync
```
