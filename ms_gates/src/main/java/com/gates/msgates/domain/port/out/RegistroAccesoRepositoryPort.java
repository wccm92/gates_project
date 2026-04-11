package com.gates.msgates.domain.port.out;

import com.gates.msgates.domain.model.RegistroAcceso;
import com.gates.msgates.domain.model.ResultadoAcceso;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Puerto de salida: repositorio de registros de acceso (auditoría).
 */
public interface RegistroAccesoRepositoryPort {

    void registrar(RegistroAcceso registro);

    List<RegistroAcceso> buscar(
            String cedula,
            Long molineteId,
            LocalDateTime desde,
            LocalDateTime hasta,
            ResultadoAcceso resultado);
}
