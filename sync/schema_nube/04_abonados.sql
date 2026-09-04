-- =====================================================================
-- NUBE · Tabla 'abonados'
-- =====================================================================
-- PK 'id_abonado' (text) y FK 'id_suite' (text) referenciada a suites.
-- Idempotente: se puede reejecutar sin error.

CREATE TABLE IF NOT EXISTS abonados (
    id_abonado text PRIMARY KEY,
    id_suite   text REFERENCES suites (id_suite)
);

-- Índice para consultas/joins por suite.
CREATE INDEX IF NOT EXISTS idx_abonados_id_suite
    ON abonados (id_suite);
