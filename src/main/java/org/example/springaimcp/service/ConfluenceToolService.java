package org.example.springaimcp.service;

import org.example.springaimcp.config.ConfluenceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * MCP tools for Confluence Cloud (REST API v1).
 * Base URL: https://{domain}/wiki/rest/api
 * Auth: Basic auth with email + API token (same credentials as Jira).
 */
@Service
public class ConfluenceToolService {

  private static final Logger log = LoggerFactory.getLogger(ConfluenceToolService.class);

  private final RestClient restClient;
  private final String defaultSpace;

  public ConfluenceToolService(RestClient confluenceRestClient, ConfluenceProperties properties) {
    this.restClient = confluenceRestClient;
    this.defaultSpace = properties.defaultSpace();
  }

  @Tool(description = "List all Confluence spaces in the instance")
  public String listSpaces(
      @ToolParam(description = "Maximum number of results (1-50, defaults to 25)") int limit) {
    int size = limit > 0 && limit <= 50 ? limit : 25;
    log.info("Listing Confluence spaces, limit={}", size);
    try {
      String response = restClient.get()
          .uri(u -> u.path("/space")
              .queryParam("limit", size)
              .queryParam("type", "global")
              .build())
          .retrieve()
          .body(String.class);
      log.debug("listSpaces response length={}", response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error listing spaces: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error listing spaces: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error listing spaces: {}", e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Search Confluence content using CQL (Confluence Query Language)")
  public String searchContent(
      @ToolParam(description = "CQL query, e.g. 'space = \"DEV\" AND type = page AND text ~ \"deployment\"'")
          String cql,
      @ToolParam(description = "Maximum number of results (1-50, defaults to 25)") int limit) {
    int size = limit > 0 && limit <= 50 ? limit : 25;
    log.info("Searching Confluence with CQL='{}', limit={}", cql, size);
    try {
      String response = restClient.get()
          .uri(u -> u.path("/search")
              .queryParam("cql", cql)
              .queryParam("limit", size)
              .queryParam("expand", "metadata.labels")
              .build())
          .retrieve()
          .body(String.class);
      log.debug("searchContent response length={}", response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error searching CQL='{}': status={}, body={}", cql, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error searching CQL='{}': status={}, body={}", cql, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error searching CQL='{}': {}", cql, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Get a Confluence page by its ID, including its body content")
  public String getPage(
      @ToolParam(description = "Confluence page ID (numeric)") String pageId) {
    log.info("Getting Confluence page id={}", pageId);
    try {
      String response = restClient.get()
          .uri(u -> u.path("/content/{id}")
              .queryParam("expand", "body.storage,version,ancestors,space")
              .build(pageId))
          .retrieve()
          .body(String.class);
      log.debug("getPage id={} response length={}", pageId, response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error getting page id={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error getting page id={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error getting page id={}: {}", pageId, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "List pages in a Confluence space")
  public String getPagesInSpace(
      @ToolParam(description = "Space key (e.g. DEV). Uses configured default if blank.") String spaceKey,
      @ToolParam(description = "Maximum number of results (1-50, defaults to 25)") int limit) {
    String space = resolve(spaceKey, defaultSpace);
    int size = limit > 0 && limit <= 50 ? limit : 25;
    log.info("Listing pages in space={}, limit={}", space, size);
    try {
      String response = restClient.get()
          .uri(u -> u.path("/space/{key}/content/page")
              .queryParam("limit", size)
              .queryParam("expand", "version,ancestors")
              .build(space))
          .retrieve()
          .body(String.class);
      log.debug("getPagesInSpace space={} response length={}", space, response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error listing pages in space={}: status={}, body={}", space, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error listing pages in space={}: status={}, body={}", space, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error listing pages in space={}: {}", space, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Get child pages of a Confluence page")
  public String getChildPages(
      @ToolParam(description = "Parent page ID (numeric)") String pageId,
      @ToolParam(description = "Maximum number of results (1-50, defaults to 25)") int limit) {
    int size = limit > 0 && limit <= 50 ? limit : 25;
    log.info("Getting child pages for pageId={}, limit={}", pageId, size);
    try {
      String response = restClient.get()
          .uri(u -> u.path("/content/{id}/child/page")
              .queryParam("limit", size)
              .queryParam("expand", "version")
              .build(pageId))
          .retrieve()
          .body(String.class);
      log.debug("getChildPages pageId={} response length={}", pageId, response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error getting child pages for pageId={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error getting child pages for pageId={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error getting child pages for pageId={}: {}", pageId, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Create a new Confluence page in a space")
  public String createPage(
      @ToolParam(description = "Space key (e.g. DEV). Uses configured default if blank.") String spaceKey,
      @ToolParam(description = "Page title") String title,
      @ToolParam(description = "Page body content (plain text or HTML)") String content,
      @ToolParam(description = "Parent page ID to nest under (optional, leave blank for root)") String parentId) {
    String space = resolve(spaceKey, defaultSpace);
    log.info("Creating Confluence page title='{}' in space={}, parentId={}", title, space, parentId);
    String ancestorsJson = (parentId != null && !parentId.isBlank())
        ? ",\"ancestors\":[{\"id\":\"" + escapeJson(parentId) + "\"}]"
        : "";
    String body = "{"
        + "\"type\":\"page\","
        + "\"title\":\"" + escapeJson(title) + "\","
        + "\"space\":{\"key\":\"" + escapeJson(space) + "\"},"
        + "\"body\":{\"storage\":{\"value\":\"" + escapeJson(content) + "\",\"representation\":\"storage\"}}"
        + ancestorsJson
        + "}";
    try {
      String response = restClient.post()
          .uri("/content")
          .body(body)
          .retrieve()
          .body(String.class);
      log.info("Created Confluence page title='{}' in space={}", title, space);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error creating page title='{}' in space={}: status={}, body={}", title, space, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error creating page title='{}' in space={}: status={}, body={}", title, space, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error creating page title='{}' in space={}: {}", title, space, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Update the title and/or content of an existing Confluence page")
  public String updatePage(
      @ToolParam(description = "Confluence page ID (numeric)") String pageId,
      @ToolParam(description = "New page title") String title,
      @ToolParam(description = "New page body content (plain text or HTML)") String content,
      @ToolParam(description = "Current version number of the page (required by Confluence API)") int version) {
    log.info("Updating Confluence page id={}, title='{}', version={}", pageId, title, version);
    String body = "{"
        + "\"id\":\"" + escapeJson(pageId) + "\","
        + "\"type\":\"page\","
        + "\"title\":\"" + escapeJson(title) + "\","
        + "\"version\":{\"number\":" + (version + 1) + "},"
        + "\"body\":{\"storage\":{\"value\":\"" + escapeJson(content) + "\",\"representation\":\"storage\"}}"
        + "}";
    try {
      String response = restClient.put()
          .uri("/content/{id}", pageId)
          .body(body)
          .retrieve()
          .body(String.class);
      log.info("Updated Confluence page id={} successfully", pageId);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error updating page id={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error updating page id={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error updating page id={}: {}", pageId, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Add a comment to a Confluence page")
  public String addPageComment(
      @ToolParam(description = "Confluence page ID (numeric)") String pageId,
      @ToolParam(description = "Comment text to post") String comment) {
    log.info("Adding comment to Confluence page id={}", pageId);
    String body = "{"
        + "\"type\":\"comment\","
        + "\"container\":{\"id\":\"" + escapeJson(pageId) + "\",\"type\":\"page\"},"
        + "\"body\":{\"storage\":{\"value\":\"<p>" + escapeJson(comment) + "</p>\",\"representation\":\"storage\"}}"
        + "}";
    try {
      String response = restClient.post()
          .uri("/content/{id}/child/comment", pageId)
          .body(body)
          .retrieve()
          .body(String.class);
      log.info("Added comment to Confluence page id={}", pageId);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error adding comment to page id={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error adding comment to page id={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error adding comment to page id={}: {}", pageId, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Change the parent page of a Confluence page (move it under a new parent)")
  public String changeParentPage(
      @ToolParam(description = "Confluence page ID to move (numeric)") String pageId,
      @ToolParam(description = "New parent page ID (numeric)") String newParentId) {
    log.info("Changing parent of page id={} to newParentId={}", pageId, newParentId);
    try {
      String response = restClient.put()
          .uri("/content/{pageId}/move/append/{targetId}", pageId, newParentId)
          .retrieve()
          .body(String.class);
      log.info("Moved page id={} under parent id={} successfully", pageId, newParentId);
      return response != null ? response : "{\"status\":\"success\",\"pageId\":\"" + escapeJson(pageId) + "\",\"newParentId\":\"" + escapeJson(newParentId) + "\"}";
    } catch (HttpClientErrorException e) {
      log.error("Client error moving page id={} to parent id={}: status={}, body={}", pageId, newParentId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error moving page id={} to parent id={}: status={}, body={}", pageId, newParentId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error moving page id={} to parent id={}: {}", pageId, newParentId, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Get comments on a Confluence page")
  public String getPageComments(
      @ToolParam(description = "Confluence page ID (numeric)") String pageId) {
    log.info("Getting comments for Confluence page id={}", pageId);
    try {
      String response = restClient.get()
          .uri(u -> u.path("/content/{id}/child/comment")
              .queryParam("expand", "body.storage,version")
              .build(pageId))
          .retrieve()
          .body(String.class);
      log.debug("getPageComments pageId={} response length={}", pageId, response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error getting comments for page id={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error getting comments for page id={}: status={}, body={}", pageId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error getting comments for page id={}: {}", pageId, e.getMessage(), e);
      return error(e);
    }
  }

  private static String resolve(String value, String fallback) {
    return (value != null && !value.isBlank()) ? value : (fallback != null ? fallback : "");
  }

  private static String escapeJson(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
  }

  private static String error(Exception e) {
    return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
  }
}
