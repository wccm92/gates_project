package com.gates.msgates.domain.port.out;

import com.gates.msgates.domain.model.Persona;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de salida: repositorio de personas.
 */
public interface PersonaRepositoryPort {

    Optional<Persona> buscarPorCedula(String cedula);

    boolean existePorCedula(String cedula);

    Persona guardar(Persona persona);

    List<Persona> listar(boolean soloActivos);
}
