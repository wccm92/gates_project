package com.gates.msgates.adapters;

import com.gates.msgates.domain.model.Visitante;
import org.springframework.jdbc.core.JdbcTemplate;

public class RemoteJdbcVisitorRepository {

    private final JdbcTemplate jdbc;
    private final String table;
    private final String visitorTable;

    public RemoteJdbcVisitorRepository(JdbcTemplate jdbc, String table, String visitorTable) {
        this.jdbc = jdbc;
        this.table = table;
        this.visitorTable = visitorTable;
    }

    public void updateEstado(Visitante visitante, String estado, String obsingreso) {
        String sql = "UPDATE " + table + " SET estado = ?, obsingreso = ? " +
                     "WHERE id_visitante = ? AND id_evento = ?";
        jdbc.update(sql, estado, obsingreso, visitante.idVisitante(), visitante.idEvento());
    }

    /**
     * Fills in the visitor's name in the cloud master table ({@code visitante}).
     *
     * <p>Only when it is still empty: registration creates visitors with no name, and a name
     * someone typed in by hand is kept over the one read from the card.
     *
     * @return rows updated — 0 when the visitor already had a name or does not exist
     */
    public int updateNombre(String idVisitante, String nombre) {
        String sql = "UPDATE " + visitorTable + " SET nom_visitante = ? " +
                     "WHERE id_visitante = ? " +
                     "AND (nom_visitante IS NULL OR btrim(nom_visitante) = '')";
        return jdbc.update(sql, nombre, idVisitante);
    }
}
