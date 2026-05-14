package com.gates.msgates.adapters;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.Visitante;
import com.gates.msgates.domain.usecase.port.VisitorRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcVisitorRepository implements VisitorRepositoryPort {

    private static final String QUERY =
            "SELECT id_visitante, id_evento, id_suite, estado, obsingreso " +
            "FROM invitados " +
            "WHERE id_visitante = ? " +
            "ORDER BY id_evento DESC " +
            "LIMIT 1";

    private static final String UPDATE =
            "UPDATE invitados SET estado = ? " +
            "WHERE id_visitante = ? AND id_evento = ?";

    private final JdbcTemplate jdbc;

    public JdbcVisitorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Visitante> findByCredential(Credential credential) {
        List<Visitante> results = jdbc.query(
                QUERY,
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
        jdbc.update(UPDATE, estado, visitante.idVisitante(), visitante.idEvento());
    }
}
