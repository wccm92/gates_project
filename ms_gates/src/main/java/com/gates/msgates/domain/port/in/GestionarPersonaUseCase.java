package com.gates.msgates.domain.port.in;

import com.gates.msgates.domain.model.Persona;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de entrada: gestión CRUD de personas en el sistema.
 */
public interface GestionarPersonaUseCase {

    record CrearComando(String cedula, String nombre, String apellido, String email) {}

    record ActualizarComando(String cedula, String nombre, String apellido, String email) {}

    Persona crear(CrearComando comando);

    Persona actualizar(ActualizarComando comando);

    Persona cambiarEstado(String cedula, boolean activo);

    Optional<Persona> buscarPorCedula(String cedula);

    List<Persona> listar(boolean soloActivos);
}
