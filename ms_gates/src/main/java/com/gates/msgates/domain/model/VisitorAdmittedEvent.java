package com.gates.msgates.domain.model;

public record VisitorAdmittedEvent(Credential credential, Visitante visitante, String estado) {
}
