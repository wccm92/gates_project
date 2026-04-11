package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.Persona;
import com.gates.msgates.domain.port.in.GestionarPersonaUseCase;
import com.gates.msgates.domain.port.out.PersonaRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GestionarPersonaService implements GestionarPersonaUseCase {

    private final PersonaRepositoryPort personaRepository;

    @Override
    @Transactional
    public Persona crear(CrearComando comando) {
        if (personaRepository.existePorCedula(comando.cedula())) {
            throw new IllegalArgumentException(
                    "Ya existe una persona registrada con cédula: " + comando.cedula());
        }
        Persona nueva = new Persona(null, comando.cedula(), comando.nombre(),
                comando.apellido(), comando.email(), true);
        Persona guardada = personaRepository.guardar(nueva);
        log.info("Persona creada: cedula={}", guardada.cedula());
        return guardada;
    }

    @Override
    @Transactional
    public Persona actualizar(ActualizarComando comando) {
        Persona existente = personaRepository.buscarPorCedula(comando.cedula())
                .orElseThrow(() -> new NoSuchElementException(
                        "Persona no encontrada: " + comando.cedula()));

        Persona actualizada = new Persona(existente.id(), existente.cedula(),
                comando.nombre(), comando.apellido(), comando.email(), existente.activo());
        log.info("Persona actualizada: cedula={}", existente.cedula());
        return personaRepository.guardar(actualizada);
    }

    @Override
    @Transactional
    public Persona cambiarEstado(String cedula, boolean activo) {
        Persona existente = personaRepository.buscarPorCedula(cedula)
                .orElseThrow(() -> new NoSuchElementException("Persona no encontrada: " + cedula));

        Persona actualizada = new Persona(existente.id(), existente.cedula(),
                existente.nombre(), existente.apellido(), existente.email(), activo);
        log.info("Estado de persona cambiado: cedula={}, activo={}", cedula, activo);
        return personaRepository.guardar(actualizada);
    }

    @Override
    public Optional<Persona> buscarPorCedula(String cedula) {
        return personaRepository.buscarPorCedula(cedula);
    }

    @Override
    public List<Persona> listar(boolean soloActivos) {
        return personaRepository.listar(soloActivos);
    }
}
