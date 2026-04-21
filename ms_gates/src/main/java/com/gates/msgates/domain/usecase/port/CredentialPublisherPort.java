package com.gates.msgates.domain.usecase.port;

import com.gates.msgates.domain.model.Credential;

/**
 * Output port for publishing an extracted credential.
 * Implementations live in the adapters layer (console, HTTP, queue, etc.).
 */
public interface CredentialPublisherPort {

    void publish(Credential credential);
}
