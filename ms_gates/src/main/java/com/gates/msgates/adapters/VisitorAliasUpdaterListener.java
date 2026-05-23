package com.gates.msgates.adapters;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.VisitorAdmittedEvent;
import com.gates.msgates.domain.model.Visitante;
import com.gates.msgates.domain.usecase.port.VisitorRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class VisitorAliasUpdaterListener {

    private static final Logger log = LoggerFactory.getLogger(VisitorAliasUpdaterListener.class);

    private final VisitorRepositoryPort repository;

    public VisitorAliasUpdaterListener(VisitorRepositoryPort repository) {
        this.repository = repository;
    }

    @Async
    @EventListener
    public void onVisitorAdmitted(VisitorAdmittedEvent event) {
        String aliasValue = "0" + event.credential().value();
        Credential aliasCredential = new Credential(aliasValue);

        Optional<Visitante> found = repository.findByCredential(aliasCredential);
        if (found.isEmpty()) {
            return;
        }

        Visitante aliasVisitante = found.get();
        if (aliasVisitante.isIngresado()) {
            log.info("[S003] alias con prefijo '0' ya ingresado — alias={}", aliasValue);
            return;
        }

        try {
            repository.updateEstado(aliasVisitante, event.estado());
            log.info("[S004] alias con prefijo '0' actualizado — alias={}", aliasValue);
        } catch (RuntimeException e) {
            log.error("[E007] error al actualizar alias con prefijo '0' — alias={}", aliasValue, e);
        }
    }
}
