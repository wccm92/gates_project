package com.gates.msgates.entrypoints;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gates.msgates.domain.model.RegistroAcceso;
import com.gates.msgates.domain.model.ResultadoAcceso;
import com.gates.msgates.domain.port.in.ConsultarRegistrosUseCase;
import com.gates.msgates.domain.port.in.ValidarAccesoUseCase;
import com.gates.msgates.entrypoints.dto.RegistroAccesoResponse;
import com.gates.msgates.entrypoints.dto.RespuestaAccesoResponse;
import com.gates.msgates.entrypoints.dto.SolicitudAccesoRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Punto de entrada principal: recibe la cédula del lector y controla el molinete.
 */
@RestController
@RequestMapping("/api/v1/acceso")
@RequiredArgsConstructor
public class AccesoController {

    private final ValidarAccesoUseCase validarAccesoUseCase;
    private final ConsultarRegistrosUseCase consultarRegistrosUseCase;

    /**
     * Endpoint principal: el lector de cédulas llama aquí.
     * POST /api/v1/acceso/validar
     * Body: { "cedula": "12345678", "molineteId": 1 }
     */
    @PostMapping("/validar")
    public ResponseEntity<RespuestaAccesoResponse> validarAcceso(
            @RequestBody @Valid SolicitudAccesoRequest request) {

        ValidarAccesoUseCase.Respuesta respuesta = validarAccesoUseCase.validar(
                new ValidarAccesoUseCase.Comando(request.cedula(), request.molineteId()));

        RespuestaAccesoResponse response = new RespuestaAccesoResponse(
                respuesta.autorizado(),
                respuesta.mensaje(),
                respuesta.resultado().name(),
                respuesta.timestamp());

        HttpStatus status = respuesta.autorizado() ? HttpStatus.OK : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * Consulta el historial de accesos con filtros opcionales.
     * GET /api/v1/acceso/registros?cedula=&molineteId=&desde=&hasta=&resultado=
     */
    @GetMapping("/registros")
    public List<RegistroAccesoResponse> obtenerRegistros(
            @RequestParam(required = false) String cedula,
            @RequestParam(required = false) Long molineteId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta,
            @RequestParam(required = false) ResultadoAcceso resultado) {

        List<RegistroAcceso> registros =
                consultarRegistrosUseCase.consultar(cedula, molineteId, desde, hasta, resultado);

        return registros.stream()
                .map(r -> new RegistroAccesoResponse(
                        r.cedula(), r.molineteId(),
                        r.resultado().name(), r.motivo(), r.timestamp()))
                .toList();
    }
}
