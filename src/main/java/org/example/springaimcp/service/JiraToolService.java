package org.example.springaimcp.service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * MCP tools for Jira Cloud (REST API v3).
 * Base URL: https://freechargebiz.atlassian.net/rest/api/3
 * Descriptions and comments use Atlassian Document Format (ADF).
 */
@Service
public class JiraToolService {

  private static final Logger log = LoggerFactory.getLogger(JiraToolService.class);
  private static final Pattern WIKI_LINK_PATTERN =
      Pattern.compile("\\[([^\\]|]+)\\|(https?://[^\\]\\s]+)]");
  private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+)");

  private final RestClient restClient;

  public JiraToolService(RestClient jiraRestClient) {
    this.restClient = jiraRestClient;
  }

  @Tool(description = "Get the details of a Jira issue by its key (e.g. AXISLIAB-3275)")
  public String getIssue(
      @ToolParam(description = "Jira issue key, e.g. AXISLIAB-3275") String issueKey) {
    log.debug("Fetching Jira issue: {}", issueKey);
    try {
      String response = restClient.get()
          .uri("/issue/{key}", issueKey)
          .retrieve()
          .body(String.class);
      log.debug("Successfully fetched issue {}, response length: {}", issueKey,
          response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error fetching issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error fetching issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error fetching issue {}: {}", issueKey, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Search Jira issues using JQL (Jira Query Language)")
  public String searchIssues(
      @ToolParam(description = "JQL query string, e.g. 'project = AXISLIAB AND status = \"In Progress\"'")
          String jql,
      @ToolParam(description = "Maximum number of results to return (1-100, defaults to 20)")
          int maxResults) {
    int limit = maxResults > 0 && maxResults <= 100 ? maxResults : 20;
    log.debug("Searching Jira issues with JQL: '{}', maxResults: {}", jql, limit);
    try {
      String response = restClient.get()
          .uri(u -> u.path("/search/jql")
              .queryParam("jql", jql)
              .queryParam("maxResults", limit)
              .queryParam("fields", "summary,status,assignee,priority,issuetype,created,updated")
              .build())
          .retrieve()
          .body(String.class);
      log.debug("Search returned response length: {}", response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error searching issues with JQL '{}': status={}, body={}", jql,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error searching issues with JQL '{}': status={}, body={}", jql,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error searching issues with JQL '{}': {}", jql, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Get all Jira issues for a project, optionally filtered by status")
  public String getProjectIssues(
      @ToolParam(description = "Jira project key, e.g. AXISLIAB") String projectKey,
      @ToolParam(description = "Status to filter by (e.g. 'To Do', 'In Progress', 'Done'). Leave blank for all.")
          String status,
      @ToolParam(description = "Maximum number of results (1-100, defaults to 20)") int maxResults) {
    int limit = maxResults > 0 && maxResults <= 100 ? maxResults : 20;
    log.debug("Fetching issues for project: {}, status filter: '{}', maxResults: {}", projectKey, status, limit);
    String jql = "project = \"" + projectKey + "\""
        + (status != null && !status.isBlank() ? " AND status = \"" + status + "\"" : "")
        + " ORDER BY created DESC";
    try {
      String response = restClient.get()
          .uri(u -> u.path("/search/jql")
              .queryParam("jql", jql)
              .queryParam("maxResults", limit)
              .queryParam("fields", "summary,status,assignee,priority,issuetype,created,updated")
              .build())
          .retrieve()
          .body(String.class);
      log.debug("Fetched project issues for {}, response length: {}", projectKey,
          response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error fetching issues for project {}: status={}, body={}", projectKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error fetching issues for project {}: status={}, body={}", projectKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error fetching issues for project {}: {}", projectKey, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Create a new Jira issue in a given project")
  public String createIssue(
      @ToolParam(description = "Jira project key, e.g. AXISLIAB") String projectKey,
      @ToolParam(description = "Issue summary / title") String summary,
      @ToolParam(description = "Detailed description of the issue") String description,
      @ToolParam(description = "Issue type: Story, Task, Bug, Epic, or Sub-task. Defaults to Task.")
          String issueType,
      @ToolParam(description = "Priority: Highest, High, Medium, Low, or Lowest. Defaults to Medium.")
          String priority) {
    String type = (issueType != null && !issueType.isBlank()) ? issueType : "Task";
    String prio = (priority != null && !priority.isBlank()) ? priority : "Medium";
    log.debug("Creating Jira issue in project: {}, type: {}, priority: {}, summary: '{}'",
        projectKey, type, prio, summary);
    String body = "{"
        + "\"fields\":{"
        + "\"project\":{\"key\":\"" + escapeJson(projectKey) + "\"},"
        + "\"summary\":\"" + escapeJson(summary) + "\","
        + "\"description\":" + adfText(description) + ","
        + "\"issuetype\":{\"name\":\"" + escapeJson(type) + "\"},"
        + "\"priority\":{\"name\":\"" + escapeJson(prio) + "\"}"
        + "}}";
    try {
      String response = restClient.post()
          .uri("/issue")
          .body(body)
          .retrieve()
          .body(String.class);
      log.info("Created Jira issue in project {}: {}", projectKey, response);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error creating issue in project {}: status={}, body={}", projectKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error creating issue in project {}: status={}, body={}", projectKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error creating issue in project {}: {}", projectKey, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Get all comments on a Jira issue")
  public String getIssueComments(
      @ToolParam(description = "Jira issue key, e.g. AXISLIAB-3275") String issueKey) {
    log.debug("Fetching comments for Jira issue: {}", issueKey);
    try {
      String response = restClient.get()
          .uri("/issue/{key}/comment", issueKey)
          .retrieve()
          .body(String.class);
      log.debug("Successfully fetched comments for issue {}, response length: {}", issueKey,
          response != null ? response.length() : 0);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error fetching comments for issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error fetching comments for issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error fetching comments for issue {}: {}", issueKey, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Add a comment to an existing Jira issue")
  public String addComment(
      @ToolParam(description = "Jira issue key, e.g. AXISLIAB-3275") String issueKey,
      @ToolParam(description = "Comment text to post") String comment) {
    log.debug("Adding comment to Jira issue: {}", issueKey);
    String body = "{\"body\":" + adfText(comment) + "}";
    try {
      String response = restClient.post()
          .uri("/issue/{key}/comment", issueKey)
          .body(body)
          .retrieve()
          .body(String.class);
      log.debug("Comment added to issue {}", issueKey);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error adding comment to issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error adding comment to issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error adding comment to issue {}: {}", issueKey, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Get the available workflow transitions for a Jira issue")
  public String getIssueTransitions(
      @ToolParam(description = "Jira issue key, e.g. AXISLIAB-3275") String issueKey) {
    log.debug("Fetching transitions for Jira issue: {}", issueKey);
    try {
      String response = restClient.get()
          .uri("/issue/{key}/transitions", issueKey)
          .retrieve()
          .body(String.class);
      log.debug("Fetched transitions for issue {}", issueKey);
      return response;
    } catch (HttpClientErrorException e) {
      log.error("Client error fetching transitions for issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error fetching transitions for issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error fetching transitions for issue {}: {}", issueKey, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Transition a Jira issue to a new status using a transition ID (from getIssueTransitions)")
  public String transitionIssue(
      @ToolParam(description = "Jira issue key, e.g. AXISLIAB-3275") String issueKey,
      @ToolParam(description = "Transition ID obtained from getIssueTransitions") String transitionId) {
    log.debug("Transitioning issue {} with transitionId: {}", issueKey, transitionId);
    String body = "{\"transition\":{\"id\":\"" + escapeJson(transitionId) + "\"}}";
    try {
      restClient.post()
          .uri("/issue/{key}/transitions", issueKey)
          .body(body)
          .retrieve()
          .toBodilessEntity();
      log.info("Transition {} applied successfully to issue {}", transitionId, issueKey);
      return "{\"result\":\"Transition applied successfully\",\"issueKey\":\"" + issueKey + "\"}";
    } catch (HttpClientErrorException e) {
      log.error("Client error transitioning issue {} with transitionId {}: status={}, body={}",
          issueKey, transitionId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error transitioning issue {} with transitionId {}: status={}, body={}",
          issueKey, transitionId, e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error transitioning issue {} with transitionId {}: {}",
          issueKey, transitionId, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Update the summary and/or description of a Jira issue")
  public String updateIssue(
      @ToolParam(description = "Jira issue key, e.g. AXISLIAB-3275") String issueKey,
      @ToolParam(description = "New summary text (leave blank to keep existing)") String summary,
      @ToolParam(description = "New description text (leave blank to keep existing)") String description) {
    log.debug("Updating issue {}: summary='{}', hasDescription={}", issueKey, summary,
        description != null && !description.isBlank());
    StringBuilder fields = new StringBuilder();
    if (summary != null && !summary.isBlank()) {
      fields.append("\"summary\":\"").append(escapeJson(summary)).append("\"");
    }
    if (description != null && !description.isBlank()) {
      if (!fields.isEmpty()) fields.append(",");
      fields.append("\"description\":").append(adfText(description));
    }
    if (fields.isEmpty()) {
      log.warn("updateIssue called for {} but no fields were provided", issueKey);
      return "{\"error\":\"No fields provided to update.\"}";
    }
    String body = "{\"fields\":{" + fields + "}}";
    try {
      restClient.put()
          .uri("/issue/{key}", issueKey)
          .body(body)
          .retrieve()
          .toBodilessEntity();
      log.info("Issue {} updated successfully", issueKey);
      return "{\"result\":\"Issue updated successfully\",\"issueKey\":\"" + issueKey + "\"}";
    } catch (HttpClientErrorException e) {
      log.error("Client error updating issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error updating issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error updating issue {}: {}", issueKey, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Assign a Jira issue to a user by their account ID")
  public String assignIssue(
      @ToolParam(description = "Jira issue key, e.g. AXISLIAB-3275") String issueKey,
      @ToolParam(description = "Assignee's Jira account ID (use blank to unassign)") String accountId) {
    log.debug("Assigning issue {} to accountId: '{}'", issueKey,
        accountId != null && !accountId.isBlank() ? accountId : "<unassign>");
    String body = (accountId != null && !accountId.isBlank())
        ? "{\"accountId\":\"" + escapeJson(accountId) + "\"}"
        : "{\"accountId\":null}";
    try {
      restClient.put()
          .uri("/issue/{key}/assignee", issueKey)
          .body(body)
          .retrieve()
          .toBodilessEntity();
      log.info("Issue {} assigned successfully to accountId: {}", issueKey, accountId);
      return "{\"result\":\"Issue assigned successfully\",\"issueKey\":\"" + issueKey + "\"}";
    } catch (HttpClientErrorException e) {
      log.error("Client error assigning issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error assigning issue {}: status={}, body={}", issueKey,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error assigning issue {}: {}", issueKey, e.getMessage(), e);
      return error(e);
    }
  }

  @Tool(description = "Get Jira attachment metadata and optionally content by attachment ID")
  public String getAttachment(
      @ToolParam(description = "Jira attachment ID, e.g. 10000") String attachmentId,
      @ToolParam(description = "Include attachment file content as base64 (true/false). Defaults to false.")
          boolean includeContent,
      @ToolParam(description = "Maximum attachment size in bytes when includeContent=true (defaults to 1048576).")
          int maxBytes) {
    int sizeLimit = maxBytes > 0 ? maxBytes : 1_048_576;
    log.debug("Fetching attachment id={}, includeContent={}, maxBytes={}", attachmentId, includeContent, sizeLimit);
    try {
      String metadata = restClient.get()
          .uri("/attachment/{id}", attachmentId)
          .retrieve()
          .body(String.class);

      if (!includeContent) {
        log.debug("Fetched attachment metadata for id={}, response length={}", attachmentId,
            metadata != null ? metadata.length() : 0);
        return metadata;
      }

      byte[] content = restClient.get()
          .uri("/attachment/content/{id}", attachmentId)
          .header("Accept", "*/*")
          .retrieve()
          .body(byte[].class);

      int contentSize = content != null ? content.length : 0;
      if (contentSize > sizeLimit) {
        log.warn("Attachment id={} size {} exceeds maxBytes={}", attachmentId, contentSize, sizeLimit);
        return "{\"error\":\"Attachment size exceeds maxBytes; request with higher maxBytes or includeContent=false\","
            + "\"attachmentId\":\"" + escapeJson(attachmentId) + "\","
            + "\"sizeBytes\":" + contentSize + ","
            + "\"maxBytes\":" + sizeLimit + "}";
      }

      String contentBase64 = Base64.getEncoder().encodeToString(content != null ? content : new byte[0]);
      log.info("Fetched attachment content id={}, bytes={}", attachmentId, contentSize);
      return "{"
          + "\"attachmentId\":\"" + escapeJson(attachmentId) + "\","
          + "\"sizeBytes\":" + contentSize + ","
          + "\"metadata\":" + (metadata != null ? metadata : "{}") + ","
          + "\"contentBase64\":\"" + contentBase64 + "\""
          + "}";
    } catch (HttpClientErrorException e) {
      log.error("Client error fetching attachment {}: status={}, body={}", attachmentId,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (HttpServerErrorException e) {
      log.error("Server error fetching attachment {}: status={}, body={}", attachmentId,
          e.getStatusCode(), e.getResponseBodyAsString());
      return error(e);
    } catch (RestClientException e) {
      log.error("Unexpected error fetching attachment {}: {}", attachmentId, e.getMessage(), e);
      return error(e);
    }
  }

  /** Wraps plain text in Atlassian Document Format (ADF) for Jira Cloud API v3. */
  private static String adfText(String text) {
    if (text == null || text.isBlank()) {
      return "{\"type\":\"doc\",\"version\":1,\"content\":[]}";
    }

    List<String> paragraphs = new ArrayList<>();
    String[] lines = text.split("\\R", -1);
    for (String line : lines) {
      List<String> nodes = parseInlineNodes(line);
      paragraphs.add("{\"type\":\"paragraph\",\"content\":[" + String.join(",", nodes) + "]}");
    }
    return "{\"type\":\"doc\",\"version\":1,\"content\":[" + String.join(",", paragraphs) + "]}";
  }

  private static List<String> parseInlineNodes(String line) {
    List<String> nodes = new ArrayList<>();
    int cursor = 0;
    while (cursor < line.length()) {
      Match wikiMatch = findWikiMatch(line, cursor);
      Match urlMatch = findUrlMatch(line, cursor);
      Match next = pickNextMatch(wikiMatch, urlMatch);

      if (next == null) {
        appendTextNode(nodes, line.substring(cursor));
        break;
      }

      if (next.start > cursor) {
        appendTextNode(nodes, line.substring(cursor, next.start));
      }

      if (next.kind == MatchKind.WIKI) {
        appendLinkNode(nodes, next.label, next.url);
      } else {
        appendLinkNode(nodes, next.url, next.url);
        if (next.linkEnd < next.end) {
          appendTextNode(nodes, line.substring(next.linkEnd, next.end));
        }
      }
      cursor = next.end;
    }

    if (nodes.isEmpty()) {
      appendTextNode(nodes, line);
    }
    return nodes;
  }

  private static Match findWikiMatch(String text, int from) {
    Matcher matcher = WIKI_LINK_PATTERN.matcher(text);
    if (!matcher.find(from)) return null;
    return new Match(
        MatchKind.WIKI,
        matcher.start(),
        matcher.end(),
        matcher.group(1),
        trimTrailingUrlPunctuation(matcher.group(2)),
        -1);
  }

  private static Match findUrlMatch(String text, int from) {
    Matcher matcher = URL_PATTERN.matcher(text);
    if (!matcher.find(from)) return null;
    String rawUrl = matcher.group(1);
    String url = trimTrailingUrlPunctuation(rawUrl);
    int linkEnd = matcher.start() + url.length();
    return new Match(MatchKind.URL, matcher.start(), matcher.end(), null, url, linkEnd);
  }

  private static Match pickNextMatch(Match first, Match second) {
    if (first == null) return second;
    if (second == null) return first;
    if (first.start <= second.start) return first;
    return second;
  }

  private static void appendTextNode(List<String> nodes, String value) {
    if (value == null || value.isEmpty()) return;
    nodes.add("{\"type\":\"text\",\"text\":\"" + escapeJson(value) + "\"}");
  }

  private static void appendLinkNode(List<String> nodes, String text, String url) {
    if (url == null || url.isBlank()) return;
    String nodeText = (text == null || text.isBlank()) ? url : text;
    String escapedText = escapeJson(nodeText);
    String escapedUrl = escapeJson(url);
    nodes.add("{\"type\":\"text\",\"text\":\"" + escapedText
        + "\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"" + escapedUrl + "\"}}]}");
  }

  private static String trimTrailingUrlPunctuation(String url) {
    int end = url.length();
    while (end > 0) {
      char c = url.charAt(end - 1);
      if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?'
          || c == ')' || c == ']' || c == '}' || c == '"' || c == '\'') {
        end--;
        continue;
      }
      break;
    }
    return url.substring(0, end);
  }

  private enum MatchKind {
    WIKI,
    URL
  }

  private record Match(MatchKind kind, int start, int end, String label, String url, int linkEnd) {
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
