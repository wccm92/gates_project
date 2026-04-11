package com.gates.msgates.entrypoints.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActualizarPersonaRequest(

        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no debe superar 100 caracteres")
        String nombre,

        @Size(max = 100, message = "El apellido no debe superar 100 caracteres")
        String apellido,

        @Email(message = "El email no tiene un formato válido")
        @Size(max = 150, message = "El email no debe superar 150 caracteres")
        String email) {
}
