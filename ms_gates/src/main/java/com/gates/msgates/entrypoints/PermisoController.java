package com.gates.msgates.entrypoints;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gates.msgates.domain.model.ControlAcceso;
import com.gates.msgates.domain.port.in.GestionarPermisoUseCase;
import com.gates.msgates.entrypoints.dto.PermisoRequest;
import com.gates.msgates.entrypoints.dto.PermisoResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Gestión de permisos de acceso (quién puede pasar por qué molinete).
 */
@RestController
@RequestMapping("/api/v1/permisos")
@RequiredArgsConstructor
public class PermisoController {

    private final GestionarPermisoUseCase gestionarPermisoUseCase;

    /**
     * POST /api/v1/permisos — Otorga acceso a una persona para un molinete.
     * Si molineteId es null, se otorga acceso a todos los molinetes.
     * fechaInicio/fechaFin son opcionales; si son null, el permiso es permanente.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PermisoResponse otorgar(@RequestBody @Valid PermisoRequest request) {
        GestionarPermisoUseCase.OtorgarComando comando = new GestionarPermisoUseCase.OtorgarComando(
                request.cedula(), request.molineteId(),
                request.fechaInicio(), request.fechaFin());
        return toResponse(gestionarPermisoUseCase.otorgar(comando));
    }

    /** DELETE /api/v1/permisos/{id} — Revoca un permiso de acceso */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revocar(@PathVariable Long id) {
        gestionarPermisoUseCase.revocar(id);
    }

    /**
     * GET /api/v1/permisos?cedula= o ?molineteId=
     * Consulta permisos por cédula o por molinete.
     */
    @GetMapping
    public ResponseEntity<List<PermisoResponse>> listar(
            @RequestParam(required = false) String cedula,
            @RequestParam(required = false) Long molineteId) {

        if (cedula != null) {
            return ResponseEntity.ok(
                    gestionarPermisoUseCase.listarPorCedula(cedula)
                            .stream().map(this::toResponse).toList());
        }
        if (molineteId != null) {
            return ResponseEntity.ok(
                    gestionarPermisoUseCase.listarPorMolinete(molineteId)
                            .stream().map(this::toResponse).toList());
        }
        return ResponseEntity.badRequest().build();
    }

    private PermisoResponse toResponse(ControlAcceso ca) {
        return new PermisoResponse(ca.id(), ca.cedula(), ca.molineteId(),
                ca.fechaInicio(), ca.fechaFin(), ca.activo());
    }
}
