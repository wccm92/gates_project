package com.gates.msgates.domain.model;

public record VisitorAdmittedEvent(Credential credential, Visitante visitante, String estado, String nombre) {

    public VisitorAdmittedEvent(Credential credential, Visitante visitante, String estado) {
        this(credential, visitante, estado, null);
    }
}
