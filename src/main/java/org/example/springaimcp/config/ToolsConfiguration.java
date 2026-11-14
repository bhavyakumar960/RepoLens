package org.example.springaimcp.config;

import java.util.Base64;
import org.example.springaimcp.service.BitbucketToolService;
import org.example.springaimcp.service.ConfluenceToolService;
import org.example.springaimcp.service.JiraToolService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties({BitbucketProperties.class, JiraProperties.class, ConfluenceProperties.class})
public class ToolsConfiguration {

  @Bean
  public RestClient bitbucketRestClient(BitbucketProperties properties) {
    return RestClient.builder()
        .baseUrl(properties.resolvedBaseUrl())
        .defaultHeader("Authorization", basicAuth(properties.username(), properties.appPassword()))
        .defaultHeader("Accept", "application/json")
        .defaultHeader("Content-Type", "application/json")
        .build();
  }

  @Bean
  public RestClient jiraRestClient(JiraProperties properties) {
    String base = properties.baseUrl() != null ? properties.baseUrl().stripTrailing() : "";
    return RestClient.builder()
        .baseUrl(base + "/rest/api/3")   // Jira Cloud uses REST API v3
        .defaultHeader("Authorization", basicAuth(properties.username(), properties.apiToken()))
        .defaultHeader("Accept", "application/json")
        .defaultHeader("Content-Type", "application/json")
        .build();
  }

  @Bean
  public RestClient confluenceRestClient(ConfluenceProperties properties) {
    String base = properties.baseUrl() != null ? properties.baseUrl().stripTrailing() : "";
    return RestClient.builder()
        .baseUrl(base + "/wiki/rest/api")
        .defaultHeader("Authorization", basicAuth(properties.username(), properties.apiToken()))
        .defaultHeader("Accept", "application/json")
        .defaultHeader("Content-Type", "application/json")
        .build();
  }

  @Bean
  public ToolCallbackProvider atlassianToolCallbackProvider(
      BitbucketToolService bitbucketToolService,
      JiraToolService jiraToolService,
      ConfluenceToolService confluenceToolService) {
    return MethodToolCallbackProvider.builder()
        .toolObjects(bitbucketToolService, jiraToolService, confluenceToolService)
        .build();
  }

  private static String basicAuth(String user, String password) {
    String credentials = (user != null ? user : "") + ":" + (password != null ? password : "");
    return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
  }
}

