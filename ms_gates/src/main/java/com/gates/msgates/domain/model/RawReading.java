package com.gates.msgates.domain.model;

import java.time.Instant;
import java.util.Objects;

public record RawReading(String payload, Instant receivedAt) {

    public RawReading {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }
}
