package com.gates.msgates.entrypoints.dto;

import java.time.LocalDateTime;

public record PersonaResponse(
        Long id,
        String cedula,
        String nombre,
        String apellido,
        String email,
        boolean activo) {
}
