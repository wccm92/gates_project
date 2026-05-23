package com.gates.msgates.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.db.catalog")
public record CatalogDbProperties(String url, String username, String password) {
}
