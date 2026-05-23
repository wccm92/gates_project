package com.gates.msgates.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.reader")
public record ReaderProperties(int idTribuna) {
}
