package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.RegistroAcceso;
import com.gates.msgates.domain.model.ResultadoAcceso;
import com.gates.msgates.domain.port.in.ConsultarRegistrosUseCase;
import com.gates.msgates.domain.port.out.RegistroAccesoRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConsultarRegistrosService implements ConsultarRegistrosUseCase {

    private final RegistroAccesoRepositoryPort repositoryPort;

    @Override
    public List<RegistroAcceso> consultar(String cedula, Long molineteId,
                                           LocalDateTime desde, LocalDateTime hasta,
                                           ResultadoAcceso resultado) {
        return repositoryPort.buscar(cedula, molineteId, desde, hasta, resultado);
    }
}
