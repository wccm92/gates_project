package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.RawReading;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, framework-free parser. Splits the raw payload on '|' and returns the
 * first attribute that contains a numeric sequence of 5 or more digits.
 */
public class ReadingParser {

    private static final String FIELD_DELIMITER = "\\|";
    private static final Pattern NUMERIC_SEQUENCE = Pattern.compile("\\d{5,}");

    public Optional<Credential> parse(RawReading raw) {
        String payload = raw.payload();
        if (payload.isBlank()) {
            return Optional.empty();
        }
        for (String token : payload.split(FIELD_DELIMITER, -1)) {
            Matcher matcher = NUMERIC_SEQUENCE.matcher(token.trim());
            if (matcher.find()) {
                return Optional.of(new Credential(matcher.group()));
            }
        }
        return Optional.empty();
    }
}
