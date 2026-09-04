-- =====================================================================
-- ENTORNO DE PRUEBA (base db_edcph_test, aislada de producción)
-- Reproduce origen y destino en una sola base; los nombres no colisionan.
-- =====================================================================

-- ---------- ORIGEN (equivale a la "nube") ----------
CREATE TABLE suites (
    id_suite text PRIMARY KEY,
    tribuna  integer NOT NULL,
    capacidad integer NOT NULL DEFAULT 10,
    estado   boolean NOT NULL DEFAULT true
);

CREATE TABLE visitantexevento (
    id_visitante text    NOT NULL,
    id_evento    integer NOT NULL,
    id_suite     text    NOT NULL REFERENCES suites(id_suite),
    estado       char(1),
    obsingreso   char(50),
    sincronizado boolean NOT NULL DEFAULT false,
    PRIMARY KEY (id_visitante, id_evento)
);

-- Suites de muestra en tres tribunas distintas.
INSERT INTO suites (id_suite, tribuna) VALUES
    ('S1', 1),   -- OCCIDENTAL
    ('S2', 1),   -- OCCIDENTAL
    ('S3', 2),   -- ORIENTAL
    ('S4', 3);   -- NORTE

-- Catálogo de eventos (equivale a 'eventos' en la nube). Solo se sincronizan
-- hacia los nodos los registros cuyo evento esté 'Activo'.
CREATE TABLE eventos (
    id     integer PRIMARY KEY,
    evento varchar NOT NULL,
    estado varchar NOT NULL DEFAULT 'Activo'
);
INSERT INTO eventos (id, evento, estado) VALUES
    (100, 'Evento ACTIVO',   'Activo'),
    (200, 'Evento INACTIVO', 'Inactivo');

-- Datos iniciales (para probar el bootstrap por reconcile).
INSERT INTO visitantexevento
    (id_visitante, id_evento, id_suite, estado, obsingreso, sincronizado) VALUES
    ('V1', 100, 'S1', 'N', NULL, false),   -- tribuna 1 -> debe llegar
    ('V2', 100, 'S3', 'N', NULL, false);   -- tribuna 2 -> NO debe llegar

-- Outbox + trigger (mismo código que schema_nube/).
CREATE TABLE cdc_outbox (
    id           bigserial PRIMARY KEY,
    op           text        NOT NULL CHECK (op IN ('INSERT', 'UPDATE')),
    id_visitante text        NOT NULL,
    id_evento    integer     NOT NULL,
    id_suite     text        NOT NULL,
    tribuna      integer,
    estado       char(1),
    obsingreso   char(50),
    sincronizado boolean,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_tribuna_id ON cdc_outbox (tribuna, id);

CREATE OR REPLACE FUNCTION fn_visitantexevento_outbox()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_tribuna integer;
BEGIN
    IF TG_OP = 'UPDATE'
       AND NEW.id_suite     IS NOT DISTINCT FROM OLD.id_suite
       AND NEW.sincronizado IS NOT DISTINCT FROM OLD.sincronizado THEN
        RETURN NEW;   -- solo cambió 'estado' y/o 'obsingreso' -> no emitir
    END IF;

    BEGIN
        PERFORM 1 FROM eventos e
                 WHERE e.id = NEW.id_evento AND lower(e.estado) = 'activo';
        IF FOUND THEN
            SELECT s.tribuna INTO v_tribuna FROM suites s WHERE s.id_suite = NEW.id_suite;
            INSERT INTO cdc_outbox (op, id_visitante, id_evento, id_suite, tribuna,
                                    estado, obsingreso, sincronizado)
            VALUES (TG_OP, NEW.id_visitante, NEW.id_evento, NEW.id_suite, v_tribuna,
                    NEW.estado, NEW.obsingreso, NEW.sincronizado);
        END IF;
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'CDC outbox falló: %', SQLERRM;
    END;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_visitantexevento_outbox
    AFTER INSERT OR UPDATE ON visitantexevento
    FOR EACH ROW EXECUTE FUNCTION fn_visitantexevento_outbox();

-- ---------- DESTINO (equivale a la base "local") ----------
CREATE TABLE invitados (
    id_visitante text    NOT NULL,
    id_evento    integer NOT NULL,
    id_suite     text    NOT NULL,
    estado       char(1),
    obsingreso   char(50),
    sincronizado boolean NOT NULL DEFAULT false,
    PRIMARY KEY (id_visitante, id_evento)
);

CREATE TABLE sync_control (
    canal          text        PRIMARY KEY,
    last_outbox_id bigint      NOT NULL DEFAULT 0,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
