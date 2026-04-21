package com.gates.msgates.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record Credential(String value) {

    private static final Pattern NUMERIC_SEQUENCE = Pattern.compile("\\d{5,}");

    public Credential {
        Objects.requireNonNull(value, "value must not be null");
        if (!NUMERIC_SEQUENCE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Credential value must be a numeric sequence of 5 or more digits");
        }
    }
}
