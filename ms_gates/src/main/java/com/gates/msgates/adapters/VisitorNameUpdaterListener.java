package com.gates.msgates.adapters;

import com.gates.msgates.domain.model.VisitorAdmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Copies the name read from the ID card into the cloud {@code visitante} table.
 * Async and best-effort: it runs after the gate has opened and a failure never affects
 * the admission.
 */
@Component
public class VisitorNameUpdaterListener {

    private static final Logger log = LoggerFactory.getLogger(VisitorNameUpdaterListener.class);

    private final RemoteJdbcVisitorRepository remote;

    public VisitorNameUpdaterListener(RemoteJdbcVisitorRepository remote) {
        this.remote = remote;
    }

    @Async
    @EventListener
    public void onVisitorAdmitted(VisitorAdmittedEvent event) {
        String nombre = event.nombre();
        if (nombre == null) {
            return;
        }
        String idVisitante = event.visitante().idVisitante();
        try {
            if (remote.updateNombre(idVisitante, nombre) > 0) {
                log.info("[S005] nombre registrado en DB remota — credential={}, nombre={}",
                        idVisitante, nombre);
            } else {
                log.debug("Nombre no actualizado: el visitante ya tenía nombre o no existe "
                        + "— credential={}", idVisitante);
            }
        } catch (RuntimeException e) {
            log.error("[E009] error al registrar nombre en DB remota — credential={}, nombre={}",
                    idVisitante, nombre, e);
        }
    }
}
