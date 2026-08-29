#!/usr/bin/env bash
#
# Exporta todos los logs recolectados de gates-pipeline (el archivo actual + los
# rotados, comprimidos o no) a un único .txt en orden cronológico.
#
# Uso:
#   gates-logs-export.sh [archivo_salida]
#
# Ejemplos:
#   gates-logs-export.sh                          # -> ~/gates-pipeline-YYYYmmdd-HHMMSS.txt
#   gates-logs-export.sh /tmp/logs.txt            # ruta explícita
#   GATES_UNIT=gates-pipeline gates-logs-export.sh
#
set -euo pipefail

UNIT="${GATES_UNIT:-gates-pipeline}"
LOG_DIR="${GATES_LOG_DIR:-/var/log/gates}"
OUT="${1:-${HOME}/${UNIT}-$(date +%Y%m%d-%H%M%S).txt}"

shopt -s nullglob

# Reunimos todos los fragmentos ordenados por fecha de modificación (más viejo
# primero), independientemente del esquema de nombres de logrotate (.1 / .gz /
# fechado). Los .gz se descomprimen al vuelo.
files=("$LOG_DIR/${UNIT}".log*)
if [ ${#files[@]} -eq 0 ]; then
    echo "No se encontraron logs en ${LOG_DIR}/${UNIT}.log*" >&2
    exit 1
fi

# Orden cronológico por mtime ascendente.
mapfile -t ordered < <(ls -1tr "${files[@]}")

: > "$OUT"
for f in "${ordered[@]}"; do
    case "$f" in
        *.gz) zcat -- "$f" >> "$OUT" ;;
        *)    cat  -- "$f" >> "$OUT" ;;
    esac
done

echo "Exportado: $OUT"
echo "Líneas:    $(wc -l < "$OUT")"
echo "Tamaño:    $(du -h "$OUT" | cut -f1)"
