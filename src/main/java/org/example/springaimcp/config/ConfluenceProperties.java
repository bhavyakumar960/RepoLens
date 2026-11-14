package org.example.springaimcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "confluence")
public record ConfluenceProperties(
    String baseUrl,
    String username,
    String apiToken,
    String defaultSpace
) {}
