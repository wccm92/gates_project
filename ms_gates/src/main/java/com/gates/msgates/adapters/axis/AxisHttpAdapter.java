package com.gates.msgates.adapters.axis;

import java.time.Duration;
import java.util.Optional;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.gates.msgates.domain.port.out.AbrirMolinetePort;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Adaptador que se comunica vía HTTP con el dispositivo Axis (VAPIX API)
 * para activar la apertura del molinete.
 *
 * Utiliza HTTP Basic Auth con las credenciales configuradas por molinete.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AxisHttpAdapter implements AbrirMolinetePort {

    private final AxisProperties axisProperties;
    private final RestTemplateBuilder restTemplateBuilder;

    @Override
    public boolean abrir(Long molineteId) {
        Optional<AxisProperties.MolineteAxisConfig> configOpt =
                axisProperties.getConfigParaMolinete(molineteId);

        if (configOpt.isEmpty()) {
            log.warn("No hay configuración Axis para el molinete {}. Verifique application.yml", molineteId);
            return false;
        }

        AxisProperties.MolineteAxisConfig config = configOpt.get();
        String url = String.format("http://%s:%d%s",
                config.getIp(), config.getPuerto(), config.getEndpoint());

        log.debug("Enviando señal de apertura al dispositivo Axis: {}", url);

        try {
            RestTemplate restTemplate = restTemplateBuilder
                    .basicAuthentication(config.getUsuario(), config.getPassword())
                    .setConnectTimeout(Duration.ofMillis(axisProperties.getTimeoutMs()))
                    .setReadTimeout(Duration.ofMillis(axisProperties.getTimeoutMs()))
                    .build();

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            log.info("Dispositivo Axis respondió HTTP {} para molinete {}",
                    response.getStatusCode().value(), molineteId);

            return response.getStatusCode().is2xxSuccessful();

        } catch (ResourceAccessException e) {
            log.error("Timeout o error de conexión con dispositivo Axis del molinete {}: {}",
                    molineteId, e.getMessage());
            return false;
        } catch (HttpClientErrorException e) {
            log.error("Error HTTP {} (cliente) al comunicar con Axis molinete {}: {}",
                    e.getStatusCode().value(), molineteId, e.getMessage());
            return false;
        } catch (HttpServerErrorException e) {
            log.error("Error HTTP {} (servidor Axis) en molinete {}: {}",
                    e.getStatusCode().value(), molineteId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Error inesperado al comunicar con Axis molinete {}", molineteId, e);
            return false;
        }
    }
}
