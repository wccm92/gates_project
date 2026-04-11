package com.gates.msgates.domain.port.in;

import com.gates.msgates.domain.model.ControlAcceso;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Puerto de entrada: gestión de permisos de acceso por persona y molinete.
 */
public interface GestionarPermisoUseCase {

    record OtorgarComando(
            String cedula,
            Long molineteId,
            LocalDateTime fechaInicio,
            LocalDateTime fechaFin) {}

    ControlAcceso otorgar(OtorgarComando comando);

    void revocar(Long permisoId);

    List<ControlAcceso> listarPorCedula(String cedula);

    List<ControlAcceso> listarPorMolinete(Long molineteId);
}
