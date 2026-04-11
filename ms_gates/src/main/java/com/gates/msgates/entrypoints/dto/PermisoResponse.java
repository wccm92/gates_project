package com.gates.msgates.entrypoints.dto;

import java.time.LocalDateTime;

public record PermisoResponse(
        Long id,
        String cedula,
        Long molineteId,
        LocalDateTime fechaInicio,
        LocalDateTime fechaFin,
        boolean activo) {
}
