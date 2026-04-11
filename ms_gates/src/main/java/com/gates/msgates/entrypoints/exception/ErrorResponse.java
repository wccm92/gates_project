package com.gates.msgates.entrypoints.exception;

import java.time.LocalDateTime;
import java.util.List;

public record ErrorResponse(
        int codigo,
        String mensaje,
        List<String> detalles,
        LocalDateTime timestamp) {
}
