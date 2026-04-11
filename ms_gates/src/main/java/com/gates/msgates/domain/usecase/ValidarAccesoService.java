package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.Persona;
import com.gates.msgates.domain.model.RegistroAcceso;
import com.gates.msgates.domain.model.ResultadoAcceso;
import com.gates.msgates.domain.port.in.ValidarAccesoUseCase;
import com.gates.msgates.domain.port.out.AbrirMolinetePort;
import com.gates.msgates.domain.port.out.ControlAccesoRepositoryPort;
import com.gates.msgates.domain.port.out.PersonaRepositoryPort;
import com.gates.msgates.domain.port.out.RegistroAccesoRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ValidarAccesoService implements ValidarAccesoUseCase {

    private final PersonaRepositoryPort personaRepository;
    private final ControlAccesoRepositoryPort controlAccesoRepository;
    private final RegistroAccesoRepositoryPort registroAccesoRepository;
    private final AbrirMolinetePort abrirMolinetePort;

    @Override
    @Transactional
    public Respuesta validar(Comando comando) {
        LocalDateTime ahora = LocalDateTime.now();

        // 1. Verificar que la persona existe
        Optional<Persona> personaOpt = personaRepository.buscarPorCedula(comando.cedula());

        if (personaOpt.isEmpty()) {
            grabarRegistro(comando.cedula(), comando.molineteId(),
                    ResultadoAcceso.DENEGADO, "Cédula no registrada en el sistema", ahora);
            log.warn("Acceso denegado — cédula no registrada: {}", comando.cedula());
            return new Respuesta(false, "Acceso denegado: cédula no registrada",
                    ResultadoAcceso.DENEGADO, ahora);
        }

        Persona persona = personaOpt.get();

        // 2. Verificar que la persona está activa
        if (!persona.activo()) {
            grabarRegistro(comando.cedula(), comando.molineteId(),
                    ResultadoAcceso.DENEGADO, "Usuario inactivo", ahora);
            log.warn("Acceso denegado — usuario inactivo: {}", comando.cedula());
            return new Respuesta(false, "Acceso denegado: usuario inactivo",
                    ResultadoAcceso.DENEGADO, ahora);
        }

        // 3. Verificar permiso de acceso al molinete (con fechas si aplica)
        boolean tieneAcceso = controlAccesoRepository
                .tieneAccesoValido(comando.cedula(), comando.molineteId(), ahora);

        if (!tieneAcceso) {
            grabarRegistro(comando.cedula(), comando.molineteId(),
                    ResultadoAcceso.DENEGADO, "Sin permiso de acceso para este molinete", ahora);
            log.warn("Acceso denegado — sin permisos: cedula={}, molinete={}",
                    comando.cedula(), comando.molineteId());
            return new Respuesta(false, "Acceso denegado: sin permisos para este molinete",
                    ResultadoAcceso.DENEGADO, ahora);
        }

        // 4. Enviar señal de apertura al dispositivo Axis
        boolean molineteAbierto = abrirMolinetePort.abrir(comando.molineteId());

        if (!molineteAbierto) {
            grabarRegistro(comando.cedula(), comando.molineteId(),
                    ResultadoAcceso.ERROR, "Error al comunicar con dispositivo Axis", ahora);
            log.error("Error al abrir molinete {} para cédula {}", comando.molineteId(), comando.cedula());
            return new Respuesta(false, "Error de comunicación con el molinete",
                    ResultadoAcceso.ERROR, ahora);
        }

        grabarRegistro(comando.cedula(), comando.molineteId(), ResultadoAcceso.AUTORIZADO, null, ahora);
        log.info("Acceso autorizado: cedula={}, molinete={}", comando.cedula(), comando.molineteId());

        return new Respuesta(true,
                "Acceso autorizado: " + persona.getNombreCompleto(),
                ResultadoAcceso.AUTORIZADO, ahora);
    }

    private void grabarRegistro(String cedula, Long molineteId,
                                 ResultadoAcceso resultado, String motivo,
                                 LocalDateTime timestamp) {
        try {
            registroAccesoRepository.registrar(
                    new RegistroAcceso(cedula, molineteId, resultado, motivo, timestamp));
        } catch (Exception e) {
            log.error("Error al persistir registro de acceso para cédula {}: {}", cedula, e.getMessage());
        }
    }
}
