package com.gates.msgates.domain.port.in;

import com.gates.msgates.domain.model.RegistroAcceso;
import com.gates.msgates.domain.model.ResultadoAcceso;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Puerto de entrada: consulta del historial de accesos (auditoría).
 */
public interface ConsultarRegistrosUseCase {

    List<RegistroAcceso> consultar(
            String cedula,
            Long molineteId,
            LocalDateTime desde,
            LocalDateTime hasta,
            ResultadoAcceso resultado);
}
