#!/usr/bin/env bash
#
# Recolector de logs de gates-pipeline.
#
# Sigue el journal (journald) del unit indicado y va agregando cada línea a un
# archivo de texto plano persistente. Usa --cursor-file para que, si el
# recolector se reinicia (o se reinicia la máquina), retome exactamente donde
# quedó: sin perder ni duplicar líneas.
#
# Configurable por variables de entorno (ver gates-log-collector.service):
#   GATES_UNIT       unit de systemd a seguir      (def: gates-pipeline)
#   GATES_LOG_DIR    carpeta del .txt de salida     (def: /var/log/gates)
#   GATES_STATE_DIR  carpeta del cursor persistente (def: /var/lib/gates-log-collector)
#
set -euo pipefail

UNIT="${GATES_UNIT:-gates-pipeline}"
LOG_DIR="${GATES_LOG_DIR:-/var/log/gates}"
STATE_DIR="${GATES_STATE_DIR:-/var/lib/gates-log-collector}"

LOG_FILE="${LOG_DIR}/${UNIT}.log"
CURSOR_FILE="${STATE_DIR}/cursor"

mkdir -p "$LOG_DIR" "$STATE_DIR"

# -n all      : la PRIMERA vez (sin cursor) vuelca todo el histórico disponible
#               en journald y luego sigue; en arranques posteriores el cursor
#               manda y -n all se ignora.
# -o short-iso: timestamp ISO completo (fecha + hora + zona), ideal para exportar.
# --cursor-file: continuidad exacta entre reinicios.
exec journalctl \
    -u "$UNIT" \
    -o short-iso \
    -n all \
    --no-pager \
    --follow \
    --cursor-file="$CURSOR_FILE" \
    >> "$LOG_FILE" 2>&1
