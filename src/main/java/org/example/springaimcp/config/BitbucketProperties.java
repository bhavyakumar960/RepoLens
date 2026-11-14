package org.example.springaimcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bitbucket")
public record BitbucketProperties(
    String baseUrl,
    String username,
    String appPassword,
    String workspace
) {
  public String resolvedBaseUrl() {
    return baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.bitbucket.org/2.0";
  }
}
