package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.RawReading;
import com.gates.msgates.domain.model.ScanReading;
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

    public Optional<ScanReading> handle(RawReading raw) {
        try {
            Optional<ScanReading> reading = parser.parse(raw);
            if (reading.isEmpty()) {
                log.warn("Discarded reading: could not parse id_lector or credential [length={}]",
                        raw.payload().length());
                return Optional.empty();
            }
            publisher.publish(reading.get().credential());
            return reading;
        } catch (RuntimeException e) {
            log.error("Unexpected failure while processing reading", e);
            return Optional.empty();
        }
    }
}
