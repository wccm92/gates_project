package com.gates.msgates.entrypoints.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Solicitud enviada por el lector de cédulas al API.
 */
public record SolicitudAccesoRequest(

        @NotBlank(message = "La cédula es obligatoria")
        @Size(min = 3, max = 25, message = "La cédula debe tener entre 3 y 25 caracteres")
        @Pattern(regexp = "^[0-9A-Za-z\\-]+$", message = "La cédula solo puede contener letras, números y guiones")
        String cedula,

        @NotNull(message = "El ID del molinete es obligatorio")
        Long molineteId) {
}
