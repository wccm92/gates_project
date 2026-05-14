package com.gates.msgates.adapters;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.Visitante;
import com.gates.msgates.domain.usecase.port.VisitorRepositoryPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

public class JdbcVisitorRepository implements VisitorRepositoryPort {

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
        String sql = "UPDATE " + table + " SET estado = ? " +
                     "WHERE id_visitante = ? AND id_evento = ?";
        jdbc.update(sql, estado, visitante.idVisitante(), visitante.idEvento());
    }
}
