package com.gates.msgates.adapters;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.Visitante;
import com.gates.msgates.domain.usecase.port.VisitorRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Delegates reads to the local DB and writes to both local (critical) and remote (best-effort).
 * The domain use case stays unaware of the dual-database topology.
 */
public class CompositeVisitorRepository implements VisitorRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(CompositeVisitorRepository.class);

    /** Colombia local time (GMT-5, no DST) used to stamp obsingreso. */
    private static final ZoneId ZONA_COLOMBIA = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter OBSINGRESO_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcVisitorRepository local;
    private final RemoteJdbcVisitorRepository remote;

    public CompositeVisitorRepository(JdbcVisitorRepository local,
                                      RemoteJdbcVisitorRepository remote) {
        this.local = local;
        this.remote = remote;
    }

    @Override
    public Optional<Visitante> findByCredential(Credential credential) {
        return local.findByCredential(credential);
    }

    @Override
    public void updateEstado(Visitante visitante, String estado) {
        // Single timestamp so local and cloud share the exact same obsingreso.
        String obsingreso = LocalDateTime.now(ZONA_COLOMBIA).format(OBSINGRESO_FORMAT) + "M";
        local.updateEstado(visitante, estado, obsingreso);
        try {
            remote.updateEstado(visitante, estado, obsingreso);
        } catch (RuntimeException e) {
            log.error("[E006] error al actualizar estado en DB remota — credential={}, idEvento={}",
                    visitante.idVisitante(), visitante.idEvento(), e);
        }
    }
}
