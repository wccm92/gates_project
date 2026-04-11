package com.gates.msgates.domain.port.in;

import com.gates.msgates.domain.model.ResultadoAcceso;

import java.time.LocalDateTime;

/**
 * Puerto de entrada: caso de uso principal del sistema.
 * Recibe la cédula del lector y decide si se abre el molinete.
 */
public interface ValidarAccesoUseCase {

    record Comando(String cedula, Long molineteId) {}

    record Respuesta(
            boolean autorizado,
            String mensaje,
            ResultadoAcceso resultado,
            LocalDateTime timestamp) {}

    Respuesta validar(Comando comando);
}
