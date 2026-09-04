-- =====================================================================
-- NUBE · Tabla outbox para Change Data Capture (CDC) de visitantexevento
-- =====================================================================
-- Registro append-only de cambios. Cada consumidor (una máquina por
-- tribuna) lee de aquí filtrando por su 'tribuna' y avanza su propio
-- cursor local. Nadie escribe en esta tabla salvo el trigger.
--
-- 'tribuna' es NULLable a propósito: si por algún motivo no se puede
-- resolver la tribuna de la suite, NO queremos bloquear la escritura en
-- la tabla de producción (el trigger es best-effort, ver 02_trigger.sql).

CREATE TABLE IF NOT EXISTS cdc_outbox (
    id            bigserial PRIMARY KEY,
    op            text        NOT NULL CHECK (op IN ('INSERT', 'UPDATE')),
    id_visitante  text        NOT NULL,
    id_evento     integer     NOT NULL,
    id_suite      text        NOT NULL,
    tribuna       integer,                      -- resuelta desde suites
    estado        char(1),
    obsingreso    char(50),
    sincronizado  boolean,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Índice para el consumo por tribuna en orden de llegada.
CREATE INDEX IF NOT EXISTS idx_outbox_tribuna_id
    ON cdc_outbox (tribuna, id);
