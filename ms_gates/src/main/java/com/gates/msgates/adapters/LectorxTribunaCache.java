package com.gates.msgates.adapters;

import com.gates.msgates.domain.usecase.port.ReaderCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class LectorxTribunaCache implements ReaderCachePort {

    private static final Logger log = LoggerFactory.getLogger(LectorxTribunaCache.class);

    private final Map<Integer, String> cache;

    public LectorxTribunaCache(JdbcTemplate jdbc, int idTribuna) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id_lector, id_puerto FROM lectorxtribuna WHERE id_tribuna = ?",
                    idTribuna);

            Map<Integer, String> loaded = new HashMap<>();
            for (Map<String, Object> row : rows) {
                loaded.put((Integer) row.get("id_lector"), (String) row.get("id_puerto"));
            }
            this.cache = Collections.unmodifiableMap(loaded);
            log.info("[S_INIT] caché de lectores cargada — id_tribuna={}, entradas={}",
                    idTribuna, cache.size());
        } catch (Exception e) {
            log.error("[E000] no se pudo cargar la caché de lectores desde 'lectorxtribuna' " +
                      "— id_tribuna={}, el servicio no puede iniciarse", idTribuna, e);
            throw new IllegalStateException(
                    "Startup failed: could not load lectorxtribuna cache for id_tribuna=" + idTribuna, e);
        }
    }

    @Override
    public Optional<String> findPortId(int idLector) {
        return Optional.ofNullable(cache.get(idLector));
    }
}
