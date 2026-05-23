package com.gates.msgates.domain.usecase.port;

/**
 * Output port for notifying an external system that a port is cleared for access.
 * Returns the HTTP status code of the notification response.
 * Implementations live in the adapters layer (HTTP, queue, etc.).
 */
public interface AccessNotifierPort {

    int notify(String idPort);
}
