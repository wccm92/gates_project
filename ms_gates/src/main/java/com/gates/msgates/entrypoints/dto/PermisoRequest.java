package com.gates.msgates.entrypoints.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PermisoRequest(

        @NotBlank(message = "La cédula es obligatoria")
        @Size(min = 3, max = 25)
        @Pattern(regexp = "^[0-9A-Za-z\\-]+$", message = "Formato de cédula inválido")
        String cedula,

        /** null = permiso para todos los molinetes */
        Long molineteId,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime fechaInicio,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime fechaFin) {
}
