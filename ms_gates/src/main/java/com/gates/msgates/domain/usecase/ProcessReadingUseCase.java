package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.RawReading;
import com.gates.msgates.domain.usecase.port.CredentialPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class ProcessReadingUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessReadingUseCase.class);

    private final ReadingParser parser;
    private final CredentialPublisherPort publisher;

    public ProcessReadingUseCase(ReadingParser parser, CredentialPublisherPort publisher) {
        this.parser = parser;
        this.publisher = publisher;
    }

    public void handle(RawReading raw) {
        try {
            Optional<Credential> credential = parser.parse(raw);
            if (credential.isEmpty()) {
                log.warn("Discarded reading: no numeric attribute of 5+ digits found [length={}]",
                        raw.payload().length());
                return;
            }
            publisher.publish(credential.get());
        } catch (RuntimeException e) {
            log.error("Unexpected failure while processing reading", e);
        }
    }
}
