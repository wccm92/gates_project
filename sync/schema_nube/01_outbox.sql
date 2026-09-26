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

-- ---------------------------------------------------------------------
-- Procedencia del cambio de estado (ver 02_trigger.sql)
-- ---------------------------------------------------------------------
-- 'estado'/'obsingreso' tienen DOS escritores posibles en la nube:
--   a) los nodos de acceso (ms_gates), que suben el ingreso que acaban de
--      registrar en tierra. Marcan 'obsingreso' con una 'M' final
--      ('2026-09-26 10:28:18M'). Esos cambios NO deben volver al nodo: ya
--      los tiene, y devolvérselos solo crea eco y riesgo de pisar.
--   b) componentes externos ajenos a este despliegue, que escriben en
--      formato ISO ('2026-09-26T10:16:42-05:00'). Esos SÍ deben bajar a
--      los nodos: son ingresos que el nodo desconoce.
--
-- El trigger resuelve la procedencia en la nube y la deja escrita aquí,
-- para que el consumidor no tenga que inferirla.
ALTER TABLE cdc_outbox
    ADD COLUMN IF NOT EXISTS aplica_estado boolean NOT NULL DEFAULT false;

-- Índice para el consumo por tribuna en orden de llegada.
CREATE INDEX IF NOT EXISTS idx_outbox_tribuna_id
    ON cdc_outbox (tribuna, id);
