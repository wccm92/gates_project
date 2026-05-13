package com.gates.msgates.domain.usecase.port;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.Visitante;

import java.util.Optional;

/**
 * Output port for querying visitor records by credential.
 * Implementations live in the adapters layer (JDBC, JPA, etc.).
 */
public interface VisitorRepositoryPort {

    Optional<Visitante> findByCredential(Credential credential);
}
