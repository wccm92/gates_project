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
--
-- ---------------------------------------------------------------------
-- PROCEDENCIA DE 'estado'/'obsingreso' (la 'M' final)
-- ---------------------------------------------------------------------
-- Estas dos columnas tienen dos escritores en la nube, y hay que saber
-- cuál de los dos hizo el UPDATE para decidir si el cambio baja o no:
--
--   NODO (ms_gates)  '2026-09-26 10:28:18M'      -> NO baja
--     El nodo ya aplicó el ingreso en su base local ANTES de subirlo
--     (CompositeVisitorRepository: local primero, nube best-effort), así
--     que devolvérselo no aporta nada: solo genera eco en el outbox y
--     abre la puerta a que un evento tardío pise un ingreso más reciente.
--     Se reconoce por la 'M' que ms_gates añade al timestamp.
--
--   EXTERNO          '2026-09-26T10:16:42-05:00' -> SÍ baja
--     Componentes ajenos a este despliegue que también marcan ingresos.
--     El nodo NO se entera de ellos por ninguna otra vía, y si no bajan,
--     esa persona puede volver a pasar por la puerta física. Se reconocen
--     por descarte: cualquier obsingreso que no termine en 'M' (el
--     formato ISO termina en dígito por el offset horario).
--
-- La decisión se toma AQUÍ, en la nube, y viaja escrita en la columna
-- cdc_outbox.aplica_estado, para que el consumidor no tenga que inferir
-- nada a partir del texto.

CREATE OR REPLACE FUNCTION fn_visitantexevento_outbox()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_tribuna       integer;
    v_cambio_datos  boolean;  -- ¿cambió id_suite o sincronizado?
    v_cambio_estado boolean;  -- ¿cambió estado u obsingreso?
    v_de_nodo       boolean;  -- ¿lo escribió un nodo? (obsingreso con 'M')
    v_aplica_estado boolean;  -- ¿el nodo debe aplicar estado/obsingreso?
BEGIN
    -- rtrim porque obsingreso es char(50): el valor viene rellenado con
    -- espacios hasta los 50 caracteres y la 'M' no quedaría al final.
    v_de_nodo := right(rtrim(coalesce(NEW.obsingreso, '')), 1) = 'M';

    IF TG_OP = 'UPDATE' THEN
        v_cambio_datos  := NEW.id_suite     IS DISTINCT FROM OLD.id_suite
                        OR NEW.sincronizado IS DISTINCT FROM OLD.sincronizado;
        v_cambio_estado := NEW.estado       IS DISTINCT FROM OLD.estado
                        OR NEW.obsingreso   IS DISTINCT FROM OLD.obsingreso;
    ELSE  -- INSERT: la fila entera es nueva para el nodo.
        v_cambio_datos  := true;
        v_cambio_estado := NEW.estado IS NOT NULL
                        OR rtrim(coalesce(NEW.obsingreso, '')) <> '';
    END IF;

    -- Solo se le pide al nodo que toque estado/obsingreso cuando el
    -- cambio NO salió de un nodo. Un alta normal (estado NULL) tampoco
    -- los toca: la fila nace en NULL en local de todos modos.
    v_aplica_estado := v_cambio_estado AND NOT v_de_nodo;

    -- Nada que contarle al nodo: ni cambiaron los campos que él replica,
    -- ni hay un cambio de estado de origen externo que deba bajar. Este
    -- es el camino del eco (el nodo subiendo sus propios ingresos), que
    -- es la operación más frecuente en control de acceso: cortarlo aquí
    -- mantiene el outbox liviano.
    IF NOT v_cambio_datos AND NOT v_aplica_estado THEN
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

            -- 'estado' y 'obsingreso' viajan siempre (sirven de traza de
            -- lo que había en la nube en ese instante), pero el consumidor
            -- solo los ESCRIBE cuando aplica_estado = true.
            INSERT INTO cdc_outbox (
                op, id_visitante, id_evento, id_suite, tribuna,
                estado, obsingreso, sincronizado, aplica_estado
            )
            VALUES (
                TG_OP, NEW.id_visitante, NEW.id_evento, NEW.id_suite, v_tribuna,
                NEW.estado, NEW.obsingreso, NEW.sincronizado, v_aplica_estado
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
