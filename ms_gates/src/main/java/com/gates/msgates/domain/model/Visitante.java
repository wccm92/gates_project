package com.gates.msgates.domain.model;

public record Visitante(
        String idVisitante,
        int idEvento,
        String idSuite,
        String estado,
        String obsIngreso) {

    /**
     * Returns true when the visitor has already been admitted.
     * The 'estado' column is CHARACTER(1) — any non-blank value means admitted.
     */
    public boolean isIngresado() {
        return estado != null && !estado.trim().isEmpty();
    }
}
