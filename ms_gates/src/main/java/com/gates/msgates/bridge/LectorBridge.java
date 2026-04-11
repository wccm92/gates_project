package com.gates.msgates.bridge;

import java.util.Scanner;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.gates.msgates.domain.port.in.ValidarAccesoUseCase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Puente integrado entre el lector USB (modo teclado HID) y el caso de uso de validación.
 *
 * Se ejecuta automáticamente al arrancar Spring Boot en un hilo de fondo,
 * de modo que el servidor REST y la escucha del lector corren en el mismo proceso.
 *
 * Trama que envía el lector:  "6|1110294635|MEDINA CARDONA JULIANA |"
 *   - Posición 0: molineteId
 *   - Posición 1: cédula
 *   - Posición 2: nombre (ignorado, el servidor lo obtiene de BD)
 *
 * Cómo ejecutar todo junto:
 *   mvn spring-boot:run
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LectorBridge implements ApplicationRunner {

    private final ValidarAccesoUseCase validarAccesoUseCase;

    @Override
    public void run(ApplicationArguments args) {
        // Hilo de fondo para no bloquear el arranque de Spring Boot
        Thread hilo = new Thread(this::escucharLector, "lector-bridge");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void escucharLector() {
        log.info("=================================================");
        log.info(" LectorBridge activo — esperando tramas del lector USB");
        log.info(" Formato esperado: molineteId|cedula|nombre|");
        log.info("=================================================");

        Scanner scanner = new Scanner(System.in);

        while (scanner.hasNextLine()) {
            String linea = scanner.nextLine().trim();

            if (!linea.isEmpty()) {
                procesarTrama(linea);
            }
        }

        log.info("[LectorBridge] Fin de stdin.");
    }

    private void procesarTrama(String linea) {
        String[] partes = linea.split("\\|");

        if (partes.length < 2) {
            log.warn("[LectorBridge] Trama inválida (se esperan al menos 2 campos separados por |): {}", linea);
            return;
        }

        long molineteId;
        try {
            molineteId = Long.parseLong(partes[0].trim());
        } catch (NumberFormatException e) {
            log.warn("[LectorBridge] El primer campo (molineteId) no es un número: {}", partes[0]);
            return;
        }

        String cedula = partes[1].trim();

        if (cedula.isEmpty()) {
            log.warn("[LectorBridge] La cédula está vacía en la trama: {}", linea);
            return;
        }

        try {
            ValidarAccesoUseCase.Respuesta respuesta = validarAccesoUseCase.validar(
                    new ValidarAccesoUseCase.Comando(cedula, molineteId));

            log.info("[LectorBridge] [{}] cedula={} molinete={} — {}",
                    respuesta.resultado(), cedula, molineteId, respuesta.mensaje());

        } catch (Exception e) {
            log.error("[LectorBridge] Error al procesar trama para cedula={}: {}", cedula, e.getMessage());
        }
    }
}
