package com.gates.msgates.domain.model;

import java.time.LocalDateTime;

/**
 * Permiso de acceso de una persona a un molinete.
 * Si molineteId es null, la persona tiene acceso a TODOS los molinetes.
 */
public record ControlAcceso(
        Long id,
        String cedula,
        Long molineteId,
        LocalDateTime fechaInicio,
        LocalDateTime fechaFin,
        boolean activo) {
}
