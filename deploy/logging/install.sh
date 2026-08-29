#!/usr/bin/env bash
#
# Instalador del recolector de logs de gates-pipeline.
# Ejecutar como root en cada máquina Linux donde corre el servicio:
#
#   sudo ./install.sh
#
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Este instalador debe ejecutarse como root (usa: sudo ./install.sh)" >&2
    exit 1
fi

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Instalando scripts en /usr/local/bin"
install -m 0755 "$SRC_DIR/gates-log-collector.sh" /usr/local/bin/gates-log-collector.sh
install -m 0755 "$SRC_DIR/gates-logs-export.sh"   /usr/local/bin/gates-logs-export.sh

echo "==> Instalando unit de systemd"
install -m 0644 "$SRC_DIR/gates-log-collector.service" /etc/systemd/system/gates-log-collector.service

echo "==> Instalando regla de logrotate"
install -m 0644 "$SRC_DIR/gates-logs.logrotate" /etc/logrotate.d/gates-logs

echo "==> Creando directorios de datos"
mkdir -p /var/log/gates /var/lib/gates-log-collector

echo "==> Habilitando y arrancando el servicio"
systemctl daemon-reload
systemctl enable --now gates-log-collector.service

echo
echo "Listo. Estado del recolector:"
systemctl --no-pager --lines=0 status gates-log-collector.service || true
echo
echo "Los logs se acumulan en: /var/log/gates/gates-pipeline.log"
echo "Exportar en cualquier momento con: gates-logs-export.sh"
