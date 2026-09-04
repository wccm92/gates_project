-- =====================================================================
-- Panel de monitoreo del CDC en la NUBE (db_edcph)
-- Uso:  ./query.sh sync/queries_monitor.sql
-- Todo es de solo lectura; no modifica nada.
-- =====================================================================

\echo '=== 1. ¿El trigger está instalado y habilitado? (habilitado = O) ==='
SELECT tgname AS trigger,
       CASE tgenabled WHEN 'O' THEN 'habilitado' ELSE tgenabled::text END AS estado
FROM pg_trigger
WHERE tgrelid = 'visitantexevento'::regclass AND NOT tgisinternal;

\echo ''
\echo '=== 2. Resumen del outbox: total y ventana de tiempo capturada ==='
SELECT count(*)      AS eventos_totales,
       min(created_at) AS primer_evento,
       max(created_at) AS ultimo_evento
FROM cdc_outbox;

\echo ''
\echo '=== 3. Eventos por tribuna y operación ==='
SELECT tribuna,
       op,
       count(*) AS eventos
FROM cdc_outbox
GROUP BY tribuna, op
ORDER BY tribuna, op;

\echo ''
\echo '=== 4. Actividad reciente (últimos 5, 60 y 1440 min) ==='
SELECT
    count(*) FILTER (WHERE created_at > now() - interval '5 minutes')   AS ult_5_min,
    count(*) FILTER (WHERE created_at > now() - interval '60 minutes')  AS ult_60_min,
    count(*) FILTER (WHERE created_at > now() - interval '24 hours')    AS ult_24_h
FROM cdc_outbox;

\echo ''
\echo '=== 5. Últimos 10 eventos capturados ==='
SELECT id, op, id_visitante, id_evento, id_suite, tribuna,
       sincronizado, created_at
FROM cdc_outbox
ORDER BY id DESC
LIMIT 10;
