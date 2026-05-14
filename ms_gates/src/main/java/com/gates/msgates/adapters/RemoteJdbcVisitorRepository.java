package com.gates.msgates.adapters;

import com.gates.msgates.domain.model.Visitante;
import org.springframework.jdbc.core.JdbcTemplate;

public class RemoteJdbcVisitorRepository {

    private final JdbcTemplate jdbc;
    private final String table;

    public RemoteJdbcVisitorRepository(JdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    public void updateEstado(Visitante visitante, String estado) {
        String sql = "UPDATE " + table + " SET estado = ? " +
                     "WHERE id_visitante = ? AND id_evento = ?";
        jdbc.update(sql, estado, visitante.idVisitante(), visitante.idEvento());
    }
}
