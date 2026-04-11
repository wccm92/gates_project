-- ============================================================
-- V1: Esquema inicial del sistema de control de acceso
-- ============================================================

-- Personas registradas en el sistema
CREATE TABLE personas (
    id                 BIGSERIAL    PRIMARY KEY,
    cedula             VARCHAR(20)  NOT NULL,
    nombre             VARCHAR(100) NOT NULL,
    apellido           VARCHAR(100),
    email              VARCHAR(150),
    activo             BOOLEAN      NOT NULL DEFAULT TRUE,
    fecha_creacion     TIMESTAMP    NOT NULL DEFAULT NOW(),
    fecha_actualizacion TIMESTAMP,
    CONSTRAINT uk_personas_cedula UNIQUE (cedula)
);

CREATE INDEX idx_personas_cedula ON personas (cedula);
CREATE INDEX idx_personas_activo  ON personas (activo);

-- Permisos de acceso por persona y molinete
-- molinete_id = NULL implica permiso para TODOS los molinetes
-- fecha_inicio / fecha_fin = NULL implica permiso permanente
CREATE TABLE control_acceso (
    id             BIGSERIAL    PRIMARY KEY,
    cedula         VARCHAR(20)  NOT NULL,
    molinete_id    BIGINT,
    fecha_inicio   TIMESTAMP,
    fecha_fin      TIMESTAMP,
    activo         BOOLEAN      NOT NULL DEFAULT TRUE,
    fecha_creacion TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_control_acceso_cedula   ON control_acceso (cedula);
CREATE INDEX idx_control_acceso_molinete ON control_acceso (molinete_id);
CREATE INDEX idx_control_acceso_activo   ON control_acceso (activo);

-- Registro de auditoría de todos los intentos de acceso
-- resultado: AUTORIZADO | DENEGADO | ERROR
CREATE TABLE registro_accesos (
    id          BIGSERIAL    PRIMARY KEY,
    cedula      VARCHAR(20)  NOT NULL,
    molinete_id BIGINT,
    resultado   VARCHAR(20)  NOT NULL,
    motivo      VARCHAR(255),
    timestamp   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_registro_accesos_cedula    ON registro_accesos (cedula);
CREATE INDEX idx_registro_accesos_timestamp ON registro_accesos (timestamp DESC);
CREATE INDEX idx_registro_accesos_resultado ON registro_accesos (resultado);
CREATE INDEX idx_registro_accesos_molinete  ON registro_accesos (molinete_id);
