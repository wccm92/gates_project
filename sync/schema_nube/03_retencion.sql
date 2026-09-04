-- =====================================================================
-- NUBE · Retención del outbox (OPCIONAL)
-- =====================================================================
-- El outbox es append-only y crece con cada cambio. Como cada máquina
-- guarda su cursor de forma independiente, no hay un punto único que
-- sepa "hasta dónde consumieron todas". La estrategia más simple y
-- segura es borrar por antigüedad: cualquier fila con más de N días ya
-- fue consumida por polling (y, ante la duda, la reconciliación
-- periódica cubre el estado completo).
--
-- Ejecuta esto manualmente cada tanto, o prográmalo con pg_cron si está
-- disponible. Ajusta el intervalo a tu gusto (7 días es conservador).

DELETE FROM cdc_outbox
 WHERE created_at < now() - interval '7 days';

-- --- Alternativa con pg_cron (si la extensión está instalada) ----------
-- SELECT cron.schedule(
--     'purga_cdc_outbox',
--     '30 4 * * *',   -- todos los días 04:30
--     $$DELETE FROM cdc_outbox WHERE created_at < now() - interval '7 days'$$
-- );
