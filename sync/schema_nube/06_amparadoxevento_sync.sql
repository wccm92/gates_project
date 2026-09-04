-- =====================================================================
-- NUBE · Sincronización nube→nube: visitantexevento → amparadoxevento
-- =====================================================================
-- Cuando un UPDATE sobre 'visitantexevento' cambia 'estado' y/o
-- 'obsingreso', esos mismos valores se propagan a TODAS las filas de
-- 'amparadoxevento' que comparten la FK compuesta (id_visitante,
-- id_evento) -- puede haber varias por cada par visitante-evento.
--
-- Es una pieza INDEPENDIENTE del trigger del outbox (02_trigger.sql):
--   - fn_visitantexevento_outbox   -> alimenta cdc_outbox (sync a nodos) y
--     DESCARTA los cambios de solo estado/obsingreso.
--   - fn_visitantexevento_amparado_sync (este) -> reacciona EXACTAMENTE a
--     esos cambios de estado/obsingreso, pero dentro de la nube.
-- Los nodos NO intervienen aquí.
--
-- DISEÑO DEFENSIVO (igual que el outbox): todo va dentro de un bloque
-- EXCEPTION. Si la propagación fallara por cualquier motivo, se registra
-- un WARNING pero NUNCA se aborta el UPDATE original sobre
-- visitantexevento (que es lo que escribe el servicio de puerta).

CREATE OR REPLACE FUNCTION fn_visitantexevento_amparado_sync()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Solo actuar si realmente cambió 'estado' u 'obsingreso'.
    -- IS DISTINCT FROM es null-safe (NULL -> valor y viceversa cuentan
    -- como cambio). Si no cambió ninguno de los dos, no hay nada que
    -- propagar.
    IF NEW.estado     IS NOT DISTINCT FROM OLD.estado
       AND NEW.obsingreso IS NOT DISTINCT FROM OLD.obsingreso THEN
        RETURN NEW;
    END IF;

    BEGIN
        -- Propaga a todas las filas de amparadoxevento con la misma FK
        -- compuesta. El filtro extra en el WHERE evita reescribir filas
        -- que ya tienen el valor correcto (menos churn de tuplas muertas
        -- en la operación más frecuente del control de acceso).
        UPDATE amparadoxevento a
           SET estado     = NEW.estado,
               obsingreso = NEW.obsingreso
         WHERE a.id_visitante = NEW.id_visitante
           AND a.id_evento    = NEW.id_evento
           AND (a.estado     IS DISTINCT FROM NEW.estado
             OR a.obsingreso IS DISTINCT FROM NEW.obsingreso);
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'Sync amparadoxevento falló para visitante=% evento=%: %',
            NEW.id_visitante, NEW.id_evento, SQLERRM;
    END;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_visitantexevento_amparado_sync ON visitantexevento;

CREATE TRIGGER trg_visitantexevento_amparado_sync
    AFTER UPDATE ON visitantexevento
    FOR EACH ROW
    EXECUTE FUNCTION fn_visitantexevento_amparado_sync();
