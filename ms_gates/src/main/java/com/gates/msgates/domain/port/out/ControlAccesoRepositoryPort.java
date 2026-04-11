package com.gates.msgates.domain.port.out;

import com.gates.msgates.domain.model.ControlAcceso;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida: repositorio de permisos de acceso.
 */
public interface ControlAccesoRepositoryPort {

    boolean tieneAccesoValido(String cedula, Long molineteId, LocalDateTime ahora);

    ControlAcceso guardar(ControlAcceso controlAcceso);

    Optional<ControlAcceso> buscarPorId(Long id);

    void revocar(Long id);

    List<ControlAcceso> listarPorCedula(String cedula);

    List<ControlAcceso> listarPorMolinete(Long molineteId);
}
