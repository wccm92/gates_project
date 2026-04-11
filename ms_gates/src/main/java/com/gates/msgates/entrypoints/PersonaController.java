package com.gates.msgates.entrypoints;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gates.msgates.domain.model.Persona;
import com.gates.msgates.domain.port.in.GestionarPersonaUseCase;
import com.gates.msgates.entrypoints.dto.ActualizarPersonaRequest;
import com.gates.msgates.entrypoints.dto.PersonaRequest;
import com.gates.msgates.entrypoints.dto.PersonaResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * CRUD de personas (administración del sistema).
 */
@RestController
@RequestMapping("/api/v1/personas")
@RequiredArgsConstructor
public class PersonaController {

    private final GestionarPersonaUseCase gestionarPersonaUseCase;

    /** POST /api/v1/personas — Registra una nueva persona */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonaResponse crear(@RequestBody @Valid PersonaRequest request) {
        GestionarPersonaUseCase.CrearComando comando = new GestionarPersonaUseCase.CrearComando(
                request.cedula(), request.nombre(), request.apellido(), request.email());
        return toResponse(gestionarPersonaUseCase.crear(comando));
    }

    /** GET /api/v1/personas/{cedula} — Busca una persona por cédula */
    @GetMapping("/{cedula}")
    public ResponseEntity<PersonaResponse> buscar(@PathVariable String cedula) {
        return gestionarPersonaUseCase.buscarPorCedula(cedula)
                .map(p -> ResponseEntity.ok(toResponse(p)))
                .orElse(ResponseEntity.notFound().build());
    }

    /** GET /api/v1/personas?soloActivos=true — Lista todas las personas */
    @GetMapping
    public List<PersonaResponse> listar(
            @RequestParam(defaultValue = "true") boolean soloActivos) {
        return gestionarPersonaUseCase.listar(soloActivos)
                .stream().map(this::toResponse).toList();
    }

    /** PUT /api/v1/personas/{cedula} — Actualiza datos de una persona */
    @PutMapping("/{cedula}")
    public PersonaResponse actualizar(@PathVariable String cedula,
                                       @RequestBody @Valid ActualizarPersonaRequest request) {
        GestionarPersonaUseCase.ActualizarComando comando = new GestionarPersonaUseCase.ActualizarComando(
                cedula, request.nombre(), request.apellido(), request.email());
        return toResponse(gestionarPersonaUseCase.actualizar(comando));
    }

    /** PATCH /api/v1/personas/{cedula}/estado?activo=false — Activa o desactiva */
    @PatchMapping("/{cedula}/estado")
    public PersonaResponse cambiarEstado(@PathVariable String cedula,
                                          @RequestParam boolean activo) {
        return toResponse(gestionarPersonaUseCase.cambiarEstado(cedula, activo));
    }

    private PersonaResponse toResponse(Persona p) {
        return new PersonaResponse(p.id(), p.cedula(), p.nombre(),
                p.apellido(), p.email(), p.activo());
    }
}
