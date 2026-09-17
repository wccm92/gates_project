package com.gates.msgates.domain.usecase;

import com.gates.msgates.domain.model.RawReading;
import com.gates.msgates.domain.model.ScanReading;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingParserTest {

    private final ReadingParser parser = new ReadingParser();

    private Optional<ScanReading> parse(String payload) {
        return parser.parse(new RawReading(payload, Instant.now()));
    }

    private String credencial(String payload) {
        return parse(payload).orElseThrow().credential().value();
    }

    // ── Cédulas: lo que ya funcionaba tiene que seguir igual ────────────────

    @Test
    @DisplayName("Trama real del lector de cédula: id_lector, documento y nombre")
    void tramaRealDeCedula() {
        ScanReading r = parse("6|1144140410|HERRERA TORRES FELIPE ALBERTO|").orElseThrow();

        assertEquals(6, r.idLector());
        assertEquals("1144140410", r.credential().value());
    }

    @Test
    @DisplayName("Trama sin nombre")
    void tramaSinNombre() {
        ScanReading r = parse("7|12345678").orElseThrow();

        assertEquals(7, r.idLector());
        assertEquals("12345678", r.credential().value());
    }

    @Test
    @DisplayName("El documento se salta los metadatos que lo preceden")
    void metadatosAntesDelDocumento() {
        assertEquals("12345678", credencial("7|CARD|12345678|DOOR-01"));
    }

    @Test
    @DisplayName("El backtick también separa campos")
    void separadorBacktick() {
        assertEquals("1144140410", credencial("6`1144140410`HERRERA TORRES FELIPE ALBERTO`"));
    }

    @Test
    @DisplayName("El documento embebido en un token se sigue extrayendo")
    void documentoEmbebido() {
        assertEquals("12345678", credencial("7|CARD-ID:12345678|HERRERA TORRES FELIPE|"));
    }

    // ── Pasaportes: lo que antes se leía mal ────────────────────────────────

    @Test
    @DisplayName("Pasaporte alfanumérico: se lee entero, no solo sus dígitos")
    void pasaporteAlfanumerico() {
        // Antes esto devolvía "123456" —los dígitos sueltos—, que es OTRO documento.
        assertEquals("AB123456", credencial("7|AB123456"));
    }

    @Test
    @DisplayName("Pasaporte con relleno '<' de la zona legible por máquina")
    void pasaporteConRelleno() {
        assertEquals("AB123456", credencial("7|AB123456<<<<<<<"));
    }

    @Test
    @DisplayName("El relleno '<' separa, no se borra: lo pegado a los lados no se funde")
    void rellenoSepara() {
        // Borrar el '<' daría "AB123456COL", un documento que no existe.
        assertEquals("AB123456", credencial("7|AB123456<COL"));
    }

    @Test
    @DisplayName("Pasaporte en minúsculas se normaliza a mayúsculas")
    void pasaporteEnMinusculas() {
        // La búsqueda contra id_visitante es exacta: en minúsculas no encontraría a nadie.
        assertEquals("AB123456", credencial("7|ab123456"));
    }

    @Test
    @DisplayName("Pasaporte con letras al final")
    void pasaporteConLetraFinal() {
        assertEquals("X1234567L", credencial("7|X1234567L"));
    }

    @Test
    @DisplayName("Pasaporte precedido de metadatos")
    void pasaporteConMetadatos() {
        assertEquals("AB123456", credencial("7|CARD|AB123456|DOOR-01"));
    }

    @Test
    @DisplayName("Pasaporte que empieza por dígitos: se toma entero, no el tramo numérico")
    void pasaporteQueEmpiezaPorDigitos() {
        // Con el patrón anterior (\d{5,}) esto devolvía "12412": el tramo de cinco
        // dígitos del final. La primera pasada toma el token completo y lo evita.
        assertEquals("12HA12412", credencial("7|12HA12412"));
        assertEquals("12HA12412", credencial("7|12HA12412<<<<"));
        assertEquals("12HA12412", credencial("7|12ha12412"));
    }

    // ── Lo que NO debe tomarse por un documento ─────────────────────────────

    @Test
    @DisplayName("Un nombre no es un documento: no tiene dígitos")
    void elNombreNoEsDocumento() {
        // "HERRERA" son 7 letras seguidas; sin la regla del dígito sería el primer match.
        assertEquals("1144140410", credencial("6|HERRERA TORRES|1144140410|"));
    }

    @Test
    @DisplayName("Una etiqueta pegada al número no se lleva el match")
    void etiquetaNoSeLlevaElMatch() {
        assertEquals("12345678", credencial("7|CREDENCIAL:12345678"));
    }

    @Test
    @DisplayName("Los acentos de un nombre nunca entran en un documento")
    void nombreConAcentos() {
        assertEquals("1144140410", credencial("6|1144140410|MUÑOZ PEÑA JOSÉ ÁNGEL|"));
    }

    @Test
    @DisplayName("Tramas inválidas se descartan igual que antes")
    void tramasInvalidas() {
        assertTrue(parse("").isEmpty());
        assertTrue(parse("   ").isEmpty());
        assertTrue(parse("SIN_SEPARADORES").isEmpty());
        assertTrue(parse("EVENT|CARD|12345678|DOOR-01").isEmpty());
        assertTrue(parse("6|1234").isEmpty(), "cuatro dígitos son pocos");
        assertTrue(parse("6|AB12").isEmpty(), "cuatro caracteres son pocos");
        assertTrue(parse("6|SOLOLETRAS").isEmpty(), "sin dígitos no es un documento");
        assertTrue(parse("6|<<<<<<<<").isEmpty(), "solo relleno no es un documento");
    }
}
