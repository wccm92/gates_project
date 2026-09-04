-- =====================================================================
-- NUBE · Función + trigger que alimenta el outbox
-- =====================================================================
-- Se dispara AFTER INSERT/UPDATE en visitantexevento, resuelve la
-- tribuna de la suite y anexa una fila a cdc_outbox.
--
-- DISEÑO DEFENSIVO: todo el cuerpo va dentro de un bloque EXCEPTION.
-- Si el CDC falla por cualquier motivo, se registra un WARNING pero
-- NUNCA se aborta la operación original sobre visitantexevento. La
-- reconciliación periódica del servicio local cubre cualquier evento
-- que el outbox pudiera perder.

CREATE OR REPLACE FUNCTION fn_visitantexevento_outbox()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_tribuna integer;
BEGIN
    -- Omitir los UPDATE cuyo único cambio sea 'estado' y/o 'obsingreso':
    -- ambas columnas son de dominio LOCAL (las marca el servicio de puerta),
    -- no interesan aguas abajo y son los cambios más frecuentes en control
    -- de acceso, así que filtrarlos aquí mantiene el outbox liviano. Solo se
    -- emite si cambió id_suite o sincronizado (lo que sí gobierna el sync).
    IF TG_OP = 'UPDATE'
       AND NEW.id_suite     IS NOT DISTINCT FROM OLD.id_suite
       AND NEW.sincronizado IS NOT DISTINCT FROM OLD.sincronizado THEN
        RETURN NEW;
    END IF;

    BEGIN
        -- Solo se emite al outbox si el evento del registro está ACTIVO en
        -- la tabla 'eventos'. Los eventos inactivos NO se propagan a los
        -- nodos. Comparación case-insensitive por robustez.
        --
        -- La consulta a 'eventos' va DENTRO de este bloque best-effort a
        -- propósito: si fallara (p. ej. por permisos), se emite un WARNING
        -- y la escritura en producción continúa; la reconciliación periódica
        -- del servicio cubre el registro. Y si un evento nace inactivo y
        -- luego se activa, el próximo reconcile trae todos sus registros.
        PERFORM 1
           FROM eventos e
          WHERE e.id = NEW.id_evento
            AND lower(e.estado) = 'activo';

        IF FOUND THEN
            SELECT s.tribuna
              INTO v_tribuna
              FROM suites s
             WHERE s.id_suite = NEW.id_suite;

            -- 'estado' y 'obsingreso' se anexan por consistencia, pero son
            -- de dominio local: el servicio de sync los IGNORA al aplicar.
            INSERT INTO cdc_outbox (
                op, id_visitante, id_evento, id_suite, tribuna,
                estado, obsingreso, sincronizado
            )
            VALUES (
                TG_OP, NEW.id_visitante, NEW.id_evento, NEW.id_suite, v_tribuna,
                NEW.estado, NEW.obsingreso, NEW.sincronizado
            );
        END IF;
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'CDC outbox falló para visitante=% evento=%: %',
            NEW.id_visitante, NEW.id_evento, SQLERRM;
    END;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_visitantexevento_outbox ON visitantexevento;

CREATE TRIGGER trg_visitantexevento_outbox
    AFTER INSERT OR UPDATE ON visitantexevento
    FOR EACH ROW
    EXECUTE FUNCTION fn_visitantexevento_outbox();
