package com.gates.msgates.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.access.http")
public record AccessHttpProperties(String baseUrl, String path, int timeoutSeconds) {
}
