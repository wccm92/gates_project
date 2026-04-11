package com.gates.msgates.domain.model;

import java.time.LocalDateTime;

/**
 * Registro de auditoría de cada intento de acceso al molinete.
 */
public record RegistroAcceso(
        String cedula,
        Long molineteId,
        ResultadoAcceso resultado,
        String motivo,
        LocalDateTime timestamp) {
}
