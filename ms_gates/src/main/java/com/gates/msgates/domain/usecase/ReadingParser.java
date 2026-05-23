package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.RawReading;
import com.gates.msgates.domain.model.ScanReading;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, framework-free parser. Splits the raw payload on '|'.
 * Expects the first token to be the reader ID (id_lector) and scans the
 * remaining tokens for the first numeric sequence of 5 or more digits (credential).
 */
public class ReadingParser {

    private static final String FIELD_DELIMITER = "\\|";
    private static final Pattern NUMERIC_SEQUENCE = Pattern.compile("\\d{5,}");

    public Optional<ScanReading> parse(RawReading raw) {
        String payload = raw.payload();
        if (payload.isBlank()) {
            return Optional.empty();
        }

        String[] tokens = payload.split(FIELD_DELIMITER, -1);
        if (tokens.length < 2) {
            return Optional.empty();
        }

        int idLector;
        try {
            idLector = Integer.parseInt(tokens[0].trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        for (int i = 1; i < tokens.length; i++) {
            Matcher matcher = NUMERIC_SEQUENCE.matcher(tokens[i].trim());
            if (matcher.find()) {
                return Optional.of(new ScanReading(new Credential(matcher.group()), idLector));
            }
        }

        return Optional.empty();
    }
}
