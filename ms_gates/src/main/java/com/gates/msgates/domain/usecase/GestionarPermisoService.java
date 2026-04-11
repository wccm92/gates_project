package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.ControlAcceso;
import com.gates.msgates.domain.port.in.GestionarPermisoUseCase;
import com.gates.msgates.domain.port.out.ControlAccesoRepositoryPort;
import com.gates.msgates.domain.port.out.PersonaRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Slf4j
@Service
@RequiredArgsConstructor
public class GestionarPermisoService implements GestionarPermisoUseCase {

    private final ControlAccesoRepositoryPort controlAccesoRepository;
    private final PersonaRepositoryPort personaRepository;

    @Override
    @Transactional
    public ControlAcceso otorgar(OtorgarComando comando) {
        // Validar que la persona existe antes de asignar permiso
        personaRepository.buscarPorCedula(comando.cedula())
                .orElseThrow(() -> new NoSuchElementException(
                        "Persona no encontrada con cédula: " + comando.cedula()));

        ControlAcceso permiso = new ControlAcceso(
                null,
                comando.cedula(),
                comando.molineteId(),
                comando.fechaInicio(),
                comando.fechaFin(),
                true);

        ControlAcceso guardado = controlAccesoRepository.guardar(permiso);
        log.info("Permiso otorgado: cedula={}, molinete={}", comando.cedula(), comando.molineteId());
        return guardado;
    }

    @Override
    @Transactional
    public void revocar(Long permisoId) {
        controlAccesoRepository.buscarPorId(permisoId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permiso de acceso no encontrado con id: " + permisoId));
        controlAccesoRepository.revocar(permisoId);
        log.info("Permiso revocado: id={}", permisoId);
    }

    @Override
    public List<ControlAcceso> listarPorCedula(String cedula) {
        return controlAccesoRepository.listarPorCedula(cedula);
    }

    @Override
    public List<ControlAcceso> listarPorMolinete(Long molineteId) {
        return controlAccesoRepository.listarPorMolinete(molineteId);
    }
}
