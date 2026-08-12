package com.gates.msgates.adapters;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.Visitante;
import com.gates.msgates.domain.usecase.port.VisitorRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public class JdbcVisitorRepository implements VisitorRepositoryPort {

    /** Colombia local time (GMT-5, no DST) used to stamp obsingreso. */
    private static final ZoneId ZONA_COLOMBIA = ZoneId.of("America/Bogota");
    private static final DateTimeFormatter OBSINGRESO_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final String table;

    public JdbcVisitorRepository(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    @Override
    public Optional<Visitante> findByCredential(Credential credential) {
        String sql = "SELECT id_visitante, id_evento, id_suite, estado, obsingreso " +
                     "FROM " + table + " " +
                     "WHERE id_visitante = ? " +
                     "ORDER BY id_evento DESC " +
                     "LIMIT 1";
        List<Visitante> results = jdbc.query(
                sql,
                (rs, rowNum) -> new Visitante(
                        rs.getString("id_visitante"),
                        rs.getInt("id_evento"),
                        rs.getString("id_suite"),
                        rs.getString("estado"),
                        rs.getString("obsingreso")),
                credential.value());
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public void updateEstado(Visitante visitante, String estado) {
        updateEstado(visitante, estado,
                LocalDateTime.now(ZONA_COLOMBIA).format(OBSINGRESO_FORMAT) + "M");
    }

    public void updateEstado(Visitante visitante, String estado, String obsingreso) {
        String sql = "UPDATE " + table + " SET estado = ?, obsingreso = ? " +
                     "WHERE id_visitante = ? AND id_evento = ?";
        jdbc.update(sql, estado, obsingreso, visitante.idVisitante(), visitante.idEvento());
    }
}
