package com.gates.msgates.domain.model;

public record Persona(
        Long id,
        String cedula,
        String nombre,
        String apellido,
        String email,
        boolean activo) {

    public String getNombreCompleto() {
        String n = nombre != null ? nombre : "";
        String a = apellido != null ? " " + apellido : "";
        return (n + a).trim();
    }
}
