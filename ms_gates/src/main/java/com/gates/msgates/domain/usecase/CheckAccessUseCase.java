package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.exception.BusinessException;
import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.VisitorAdmittedEvent;
import com.gates.msgates.domain.model.Visitante;
import com.gates.msgates.domain.usecase.port.AccessNotifierPort;
import com.gates.msgates.domain.usecase.port.VisitorRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

public class CheckAccessUseCase {

    private static final Logger log = LoggerFactory.getLogger(CheckAccessUseCase.class);

    private final VisitorRepositoryPort repository;
    private final AccessNotifierPort notifier;
    private final ApplicationEventPublisher eventPublisher;

    public CheckAccessUseCase(VisitorRepositoryPort repository,
                               AccessNotifierPort notifier,
                               ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.notifier = notifier;
        this.eventPublisher = eventPublisher;
    }

    public void handle(Credential credential) {
        try {
            Optional<Visitante> found = repository.findByCredential(credential);

            if (found.isEmpty()) {
                log.warn("[E001] documento no presente — credential={}", credential.value());
                throw new BusinessException("E001", "documento no presente");
            }

            Visitante visitante = found.get();

            if (visitante.isIngresado()) {
                log.warn("[E002] documento ya ingresó — credential={}, estado='{}'",
                        credential.value(), visitante.estado());
                throw new BusinessException("E002", "documento ya ingresó");
            }

            log.info("[S000] documento apto para ingresar — credential={}", credential.value());

            int statusCode = notifier.notify(credential);

            if (statusCode == 200) {
                log.info("[S001] notificación HTTP exitosa — credential={}, status={}",
                        credential.value(), statusCode);
                try {
                    repository.updateEstado(visitante, "1");
                    log.info("[S002] estado actualizado a '1' en DB — credential={}",
                            credential.value());
                    eventPublisher.publishEvent(new VisitorAdmittedEvent(credential, visitante, "1"));
                } catch (RuntimeException e) {
                    log.error("[E005] error al actualizar estado en DB — credential={}",
                            credential.value(), e);
                }
            } else {
                log.error("[E003] notificación HTTP fallida — credential={}, status={}",
                        credential.value(), statusCode);
            }

        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("[E004] error inesperado en verificación de acceso — credential={}",
                    credential.value(), e);
        }
    }
}
