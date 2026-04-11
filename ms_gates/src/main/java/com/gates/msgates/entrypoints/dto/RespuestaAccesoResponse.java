package com.gates.msgates.entrypoints.dto;

import java.time.LocalDateTime;

/**
 * Respuesta devuelta al lector/sistema tras validar el acceso.
 */
public record RespuestaAccesoResponse(
        boolean autorizado,
        String mensaje,
        String resultado,
        LocalDateTime timestamp) {
}
