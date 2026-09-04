-- =====================================================================
-- Limpieza del entorno de prueba.
-- Ejecutar conectado a otra base (p. ej. db_edcph), NO a db_edcph_test.
-- WITH (FORCE) termina cualquier conexión residual del servicio.
-- =====================================================================
DROP DATABASE IF EXISTS db_edcph_test WITH (FORCE);
