package com.gates.msgates.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A document number read from a badge, ID card or passport.
 *
 * <p>Not necessarily numeric: passports and foreign ID cards carry letters. The value is
 * expected already normalised — upper case, no separators — which is what
 * {@code ReadingParser} produces; the lookup against {@code id_visitante} is an exact
 * string comparison, so a lower-case value would simply match nothing.
 *
 * <p>At least one digit is required. Letters alone are never a document, and that rule is
 * what stops a name or a label ({@code CREDENCIAL}, {@code HERRERA}) from being accepted
 * as one.
 */
public record Credential(String value) {

    private static final Pattern DOCUMENT = Pattern.compile("[A-Z0-9]{5,}");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    public Credential {
        Objects.requireNonNull(value, "value must not be null");
        if (!DOCUMENT.matcher(value).matches() || !HAS_DIGIT.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "Credential value must be 5 or more upper-case letters and digits, "
                            + "including at least one digit");
        }
    }
}
