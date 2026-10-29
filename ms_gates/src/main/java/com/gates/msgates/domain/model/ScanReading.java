package com.gates.msgates.domain.model;

/**
 * @param nombre holder's name as printed on the ID card, or {@code null} when the scan
 *               carries none (passports, badges, older readers).
 */
public record ScanReading(Credential credential, int idLector, String nombre) {

    public ScanReading(Credential credential, int idLector) {
        this(credential, idLector, null);
    }
}
