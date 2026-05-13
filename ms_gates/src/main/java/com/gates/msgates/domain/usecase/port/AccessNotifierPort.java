package com.gates.msgates.domain.usecase.port;

import com.gates.msgates.domain.model.Credential;

/**
 * Output port for notifying an external system that a credential is cleared for access.
 * Returns the HTTP status code of the notification response.
 * Implementations live in the adapters layer (HTTP, queue, etc.).
 */
public interface AccessNotifierPort {

    int notify(Credential credential);
}
