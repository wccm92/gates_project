-- =====================================================================
-- LOCAL · Tabla destino 'invitados' + control de sincronización
-- =====================================================================
-- Se ejecuta en la base de datos LOCAL de cada máquina Linux.
-- Misma estructura que visitantexevento en la nube, pero id_suite NO es
-- llave foránea (aquí no existe la tabla suites, y no interesa forzarla).
-- La PK compuesta es imprescindible para el UPSERT idempotente.

CREATE TABLE IF NOT EXISTS invitados (
    id_visitante  text          NOT NULL,
    id_evento     integer       NOT NULL,
    id_suite      text          NOT NULL,
    estado        char(1),
    obsingreso    char(50),
    sincronizado  boolean       NOT NULL DEFAULT false,
    PRIMARY KEY (id_visitante, id_evento)
);

-- Si la tabla 'invitados' YA existía pero sin la columna 'sincronizado'
-- (el servicio la necesita), estos ALTER la agregan. Son idempotentes.
-- Requieren ser dueño de la tabla; si falla por permisos, ejecútalos con
-- el superusuario:  sudo -u postgres psql -d <base> -f 01_invitados.sql
ALTER TABLE invitados ADD COLUMN IF NOT EXISTS sincronizado boolean NOT NULL DEFAULT false;

-- Cursor de consumo del outbox. Una fila por canal (aquí, la tribuna).
-- Guardar el cursor en local hace a cada máquina autónoma: no necesita
-- permisos de escritura en la nube.
CREATE TABLE IF NOT EXISTS sync_control (
    canal          text        PRIMARY KEY,   -- p. ej. 'tribuna:1'
    last_outbox_id bigint      NOT NULL DEFAULT 0,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
