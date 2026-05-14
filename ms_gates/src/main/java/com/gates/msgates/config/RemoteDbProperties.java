package com.gates.msgates.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.db.remote")
public record RemoteDbProperties(String url, String username, String password, String table) {
}
