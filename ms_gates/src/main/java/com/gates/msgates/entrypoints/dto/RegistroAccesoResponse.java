package com.gates.msgates.entrypoints.dto;

import java.time.LocalDateTime;

public record RegistroAccesoResponse(
        String cedula,
        Long molineteId,
        String resultado,
        String motivo,
        LocalDateTime timestamp) {
}
