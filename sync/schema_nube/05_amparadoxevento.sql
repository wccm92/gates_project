-- =====================================================================
-- NUBE · Tabla 'amparadoxevento'
-- =====================================================================
-- Relaciona a un 'amparado' con un par visitante-evento concreto.
--
-- PK 'id_amparado' (text).
-- FK compuesta (id_visitante, id_evento) -> visitantexevento: su PK es
--   compuesta, así que id_visitante SOLA no puede referenciarla; se usa el
--   par completo para garantizar que el amparado pertenece a un registro
--   visitante-evento real.
-- FK id_evento -> eventos(id) y FK id_suite -> suites(id_suite).
-- 'estado' y 'obsingreso' replican el formato de visitantexevento:
--   character(1) y character(50) respectivamente.
-- Idempotente: se puede reejecutar sin error.

CREATE TABLE IF NOT EXISTS amparadoxevento (
    id_amparado  text         PRIMARY KEY,
    id_visitante text         NOT NULL,
    id_evento    integer      NOT NULL,
    id_suite     text,
    estado       character(1),
    obsingreso   character(50),
    FOREIGN KEY (id_visitante, id_evento)
        REFERENCES visitantexevento (id_visitante, id_evento),
    FOREIGN KEY (id_evento) REFERENCES eventos (id),
    FOREIGN KEY (id_suite)  REFERENCES suites (id_suite)
);

-- Índices para los joins por las columnas FK (PostgreSQL no los crea solo).
CREATE INDEX IF NOT EXISTS idx_amparadoxevento_visitante_evento
    ON amparadoxevento (id_visitante, id_evento);
CREATE INDEX IF NOT EXISTS idx_amparadoxevento_id_evento
    ON amparadoxevento (id_evento);
CREATE INDEX IF NOT EXISTS idx_amparadoxevento_id_suite
    ON amparadoxevento (id_suite);
