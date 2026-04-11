-- ============================================================
-- V2: Datos de ejemplo para pruebas iniciales
-- ============================================================

-- Personas de ejemplo
INSERT INTO personas (cedula, nombre, apellido, email, activo) VALUES
    ('12345678', 'Juan',  'Pérez',   'juan.perez@empresa.com',   TRUE),
    ('87654321', 'María', 'García',  'maria.garcia@empresa.com', TRUE),
    ('11111111', 'Admin', 'Sistema', 'admin@empresa.com',        TRUE),
    ('99999999', 'Carlos','Inactivo','carlos@empresa.com',       FALSE);

-- Permisos de acceso
-- Juan: acceso permanente a todos los molinetes (molinete_id NULL)
INSERT INTO control_acceso (cedula, molinete_id, fecha_inicio, fecha_fin, activo) VALUES
    ('12345678', NULL,  NULL, NULL, TRUE);

-- María: acceso solo al molinete 1, con vencimiento
INSERT INTO control_acceso (cedula, molinete_id, fecha_inicio, fecha_fin, activo) VALUES
    ('87654321', 1, '2024-01-01 00:00:00', '2030-12-31 23:59:59', TRUE);

-- Admin: acceso permanente a todos los molinetes
INSERT INTO control_acceso (cedula, molinete_id, fecha_inicio, fecha_fin, activo) VALUES
    ('11111111', NULL, NULL, NULL, TRUE);

-- Carlos está inactivo, pero tiene permiso (no importa pues su cuenta está desactivada)
INSERT INTO control_acceso (cedula, molinete_id, fecha_inicio, fecha_fin, activo) VALUES
    ('99999999', 1, NULL, NULL, TRUE);
