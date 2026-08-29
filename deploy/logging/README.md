# Recolector de logs de `gates-pipeline`

Mecanismo para persistir en un `.txt` todo lo que emite el servicio
`gates-pipeline` (lo que ves con `sudo journalctl -u gates-pipeline -f`), de modo
que **no se pierda** al cerrar la consola ni al reiniciar el servicio o la
máquina, y poder **exportarlo** después.

## ¿Qué hace?

Un servicio de systemd aparte (`gates-log-collector`) sigue el journal de
`gates-pipeline` y va agregando cada línea a un archivo de texto plano:

```
/var/log/gates/gates-pipeline.log
```

Es robusto ante reinicios: usa `journalctl --cursor-file`, así que si el
recolector (o la máquina) se reinicia, retoma **exactamente** donde quedó, sin
perder ni duplicar líneas. `logrotate` acota el tamaño rotando a diario y
conservando 30 días comprimidos.

## Componentes

| Archivo                        | Destino en el sistema                          | Rol |
|--------------------------------|------------------------------------------------|-----|
| `gates-log-collector.sh`       | `/usr/local/bin/`                              | Sigue el journal y vuelca al `.txt` |
| `gates-log-collector.service`  | `/etc/systemd/system/`                         | Servicio systemd que mantiene vivo el recolector |
| `gates-logs.logrotate`         | `/etc/logrotate.d/gates-logs`                  | Rotación diaria + compresión |
| `gates-logs-export.sh`         | `/usr/local/bin/`                              | Une actual + rotados en un solo `.txt` |
| `install.sh`                   | —                                              | Instala todo lo anterior |

## Instalación

En cada máquina Linux donde corre el servicio:

```bash
cd deploy/logging
sudo ./install.sh
```

Esto instala los archivos, crea los directorios, y habilita + arranca el
recolector (queda activo al boot).

## Uso

Ver el estado:

```bash
systemctl status gates-log-collector
```

Ver el `.txt` en vivo (equivalente a tu `journalctl -f`, pero ya persistido):

```bash
tail -f /var/log/gates/gates-pipeline.log
```

## Exportar los logs

Genera un único `.txt` con todo el histórico disponible (archivo actual + los
rotados comprimidos), en orden cronológico:

```bash
# A tu home, con nombre autogenerado por fecha
gates-logs-export.sh

# A una ruta específica
gates-logs-export.sh /tmp/gates-logs.txt
```

Luego lo copias a tu máquina con, por ejemplo:

```bash
scp usuario@maquina:/tmp/gates-logs.txt .
```

## Configuración

Las rutas y el unit a seguir se ajustan por variables de entorno en
`gates-log-collector.service` (recarga con `systemctl daemon-reload &&
systemctl restart gates-log-collector` tras editarlo):

- `GATES_UNIT` — unit a seguir (def. `gates-pipeline`)
- `GATES_LOG_DIR` — carpeta del `.txt` (def. `/var/log/gates`)
- `GATES_STATE_DIR` — carpeta del cursor (def. `/var/lib/gates-log-collector`)

Retención y frecuencia de rotación: edita `/etc/logrotate.d/gates-logs`
(`rotate 30`, `daily`, etc.).

## Recomendado (opcional): journald persistente

El recolector ya persiste todo en disco mientras esté corriendo. Como red de
seguridad adicional —para no perder los mensajes generados en la breve ventana
entre un apagón y el rearranque del recolector— conviene que el propio journald
sea persistente:

```bash
sudo mkdir -p /var/log/journal
sudo sed -i 's/^#\?Storage=.*/Storage=persistent/' /etc/systemd/journald.conf
sudo systemctl restart systemd-journald
```

Con esto, al rearrancar, el recolector recupera vía `--cursor-file` incluso lo
ocurrido mientras estuvo caído.

## Requisitos

- systemd con `journalctl --cursor-file` (systemd ≥ 245; presente en Ubuntu
  20.04+, Debian 11+ y equivalentes). Verifica con `journalctl --version`.
