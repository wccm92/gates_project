package com.gates.msgates.adapters.axis;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Propiedades de configuración para los dispositivos Axis.
 * Se configura en application.yml bajo el prefijo "axis".
 *
 * Ejemplo de endpoint VAPIX para relay de Axis:
 *   /axis-cgi/io/port.cgi?action=6:1/
 *
 * Para controladores de acceso Axis A1001 (apertura de puerta):
 *   /axis-cgi/accesscontrol/V1/door/unlock?door=1
 */
@ConfigurationProperties(prefix = "axis")
@Getter
@Setter
public class AxisProperties {

    /** Timeout en milisegundos para llamadas HTTP al dispositivo Axis */
    private int timeoutMs = 3000;

    /** Mapa de molineteId (como string) → configuración del dispositivo Axis */
    private Map<String, MolineteAxisConfig> molinetes = new HashMap<>();

    public Optional<MolineteAxisConfig> getConfigParaMolinete(Long molineteId) {
        return Optional.ofNullable(molinetes.get(String.valueOf(molineteId)));
    }

    @Getter
    @Setter
    public static class MolineteAxisConfig {

        /** IP o hostname del dispositivo Axis en la red LAN */
        private String ip;

        /** Puerto HTTP del dispositivo (default: 80) */
        private int puerto = 80;

        /** Usuario para autenticación Basic Auth del dispositivo Axis */
        private String usuario = "root";

        /** Contraseña para autenticación Basic Auth del dispositivo Axis */
        private String password;

        /**
         * Endpoint VAPIX a invocar para abrir el molinete.
         * Ejemplos:
         *   - Relay/salida digital: /axis-cgi/io/port.cgi?action=6:1/
         *   - Controlador de acceso: /axis-cgi/accesscontrol/V1/door/unlock?door=1
         */
        private String endpoint = "/axis-cgi/io/port.cgi?action=6:1/";
    }
}
