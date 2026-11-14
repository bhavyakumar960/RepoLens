package org.example.springaimcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jira")
public record JiraProperties(
    String baseUrl,
    String username,
    String apiToken
) {}
