package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.Credential;
import com.gates.msgates.domain.model.RawReading;
import com.gates.msgates.domain.model.ScanReading;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, framework-free parser. Splits the raw payload on '|' or backtick.
 * Expects the first token to be the reader ID (id_lector) and scans the
 * remaining tokens for the document number (credential).
 *
 * <p>Documents are <b>not</b> always numeric: passports and foreign ID cards carry
 * letters (e.g. {@code AB123456}). Matching {@code \d{5,}} as a subsequence — what this
 * parser used to do — did not reject those scans, it silently extracted the digits only:
 * {@code AB123456} yielded {@code 123456}, a different document. If that number belonged
 * to somebody else in the event, the turnstile opened for the wrong person.
 *
 * <p>Two passes, in this order:
 * <ol>
 *   <li><b>Whole token.</b> The document normally arrives as a field of its own
 *       ({@code 6|1144140410|NAME|}, {@code 7|AB123456}), so a token that is entirely a
 *       valid document is taken as-is. This is what keeps {@code AB123456} intact.</li>
 *   <li><b>Subsequence.</b> Only if no token qualifies, the document is searched for
 *       inside the tokens, which is what makes {@code 7|CARD-ID:12345678} still work.</li>
 * </ol>
 *
 * <p>A match must contain at least one digit. Without that rule the first pass would
 * happily take a name or a label — {@code CREDENCIAL:12345678} would yield
 * {@code CREDENCIAL} — since both are just letters and digits.
 */
public class ReadingParser {

    private static final String FIELD_DELIMITER = "[|`]";

    /** Letters and digits only: accents are excluded so a name can never match. */
    private static final Pattern DOCUMENT = Pattern.compile("[A-Z0-9]{5,}");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

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

        String[] cleaned = new String[tokens.length];
        for (int i = 1; i < tokens.length; i++) {
            cleaned[i] = clean(tokens[i]);
        }

        // Pass 1: a token that is, in full, a valid document.
        for (int i = 1; i < tokens.length; i++) {
            if (isDocument(cleaned[i])) {
                return reading(cleaned[i], idLector);
            }
        }

        // Pass 2: a document embedded inside a token.
        for (int i = 1; i < tokens.length; i++) {
            Matcher matcher = DOCUMENT.matcher(cleaned[i]);
            while (matcher.find()) {
                if (hasDigit(matcher.group())) {
                    return reading(matcher.group(), idLector);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Normalises a token before matching.
     *
     * <p>{@code '<'} is the filler character of a passport's machine-readable zone
     * ({@code AB123456<<<}). It becomes a space rather than being deleted: removing it
     * would weld together whatever sits on each side, turning {@code AB123456<NAME} into
     * {@code AB123456NAME}. As a space it acts as the separator it really is.
     *
     * <p>Upper case is required, not cosmetic: the registration front-end stores
     * documents upper-cased and the lookup is an exact {@code WHERE id_visitante = ?},
     * so a reader sending {@code ab123456} would match nobody.
     */
    private String clean(String token) {
        return token.replace('<', ' ').trim().toUpperCase(Locale.ROOT);
    }

    private boolean isDocument(String value) {
        return DOCUMENT.matcher(value).matches() && hasDigit(value);
    }

    private boolean hasDigit(String value) {
        return HAS_DIGIT.matcher(value).find();
    }

    private Optional<ScanReading> reading(String value, int idLector) {
        return Optional.of(new ScanReading(new Credential(value), idLector));
    }
}
