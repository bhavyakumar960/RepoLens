package org.example.springaimcp.service;

import org.example.springaimcp.config.BitbucketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * MCP tools for Bitbucket Cloud (REST API 2.0).
 * Base URL: https://api.bitbucket.org/2.0
 * Default workspace: freechargebiz
 */
@Service
public class BitbucketToolService {

  private static final Logger log = LoggerFactory.getLogger(BitbucketToolService.class);
  private static final int DEFAULT_PAGE_LEN = 25;

  private final RestClient restClient;
  private final String defaultWorkspace;

  public BitbucketToolService(RestClient bitbucketRestClient, BitbucketProperties properties) {
    this.restClient = bitbucketRestClient;
    this.defaultWorkspace = properties.workspace();
  }

  @Tool(description = "List all repositories in a Bitbucket Cloud workspace")
  public String listRepositories(
      @ToolParam(description = "Workspace slug (e.g. freechargebiz). Uses configured default if blank.")
          String workspace) {
    String ws = resolve(workspace, defaultWorkspace);
    log.info("Listing repositories for workspace={}", ws);
    try {
      String response = restClient.get()
          .uri("/repositories/{ws}?pagelen={len}", ws, DEFAULT_PAGE_LEN)
          .retrieve()
          .body(String.class);
      log.debug("listRepositories response length={}", response != null ? response.length() : 0);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to list repositories for workspace={}: {}", ws, e.getMessage());
      return error(e);
    }
  }

  @Tool(description = "List pull requests for a Bitbucket Cloud repository")
  public String listPullRequests(
      @ToolParam(description = "Workspace slug. Uses configured default if blank.") String workspace,
      @ToolParam(description = "Repository slug (e.g. axis-customerdigitalidentity)") String repoSlug,
      @ToolParam(description = "PR state: OPEN, MERGED, DECLINED, or SUPERSEDED. Defaults to OPEN.")
          String state) {
    String ws = resolve(workspace, defaultWorkspace);
    String prState = resolve(state, "OPEN");
    log.info("Listing pull requests for workspace={}, repo={}, state={}", ws, repoSlug, prState);
    try {
      String response = restClient.get()
          .uri("/repositories/{ws}/{repo}/pullrequests?state={state}&pagelen={len}",
              ws, repoSlug, prState, DEFAULT_PAGE_LEN)
          .retrieve()
          .body(String.class);
      log.debug("listPullRequests response length={}", response != null ? response.length() : 0);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to list PRs for workspace={}, repo={}: {}", ws, repoSlug, e.getMessage());
      return error(e);
    }
  }

  @Tool(description = "Get details of a specific Bitbucket Cloud pull request")
  public String getPullRequest(
      @ToolParam(description = "Workspace slug. Uses configured default if blank.") String workspace,
      @ToolParam(description = "Repository slug") String repoSlug,
      @ToolParam(description = "Pull request ID (integer)") int prId) {
    String ws = resolve(workspace, defaultWorkspace);
    log.info("Getting PR details for workspace={}, repo={}, prId={}", ws, repoSlug, prId);
    try {
      String response = restClient.get()
          .uri("/repositories/{ws}/{repo}/pullrequests/{id}", ws, repoSlug, prId)
          .retrieve()
          .body(String.class);
      log.debug("getPullRequest response length={}", response != null ? response.length() : 0);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to get PR workspace={}, repo={}, prId={}: {}", ws, repoSlug, prId, e.getMessage());
      return error(e);
    }
  }

  @Tool(description = "Get the diff of a Bitbucket Cloud pull request")
  public String getPullRequestDiff(
      @ToolParam(description = "Workspace slug. Uses configured default if blank.") String workspace,
      @ToolParam(description = "Repository slug") String repoSlug,
      @ToolParam(description = "Pull request ID (integer)") int prId) {
    String ws = resolve(workspace, defaultWorkspace);
    log.info("Getting PR diff for workspace={}, repo={}, prId={}", ws, repoSlug, prId);
    try {
      String response = restClient.get()
          .uri("/repositories/{ws}/{repo}/pullrequests/{id}/diff", ws, repoSlug, prId)
          .retrieve()
          .body(String.class);
      log.debug("getPullRequestDiff response length={}", response != null ? response.length() : 0);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to get PR diff workspace={}, repo={}, prId={}: {}", ws, repoSlug, prId, e.getMessage());
      return error(e);
    }
  }

  @Tool(description = "Get the raw content of a file from a Bitbucket Cloud repository")
  public String getFileContent(
      @ToolParam(description = "Workspace slug. Uses configured default if blank.") String workspace,
      @ToolParam(description = "Repository slug (e.g. axis-customerdigitalidentity)") String repoSlug,
      @ToolParam(description = "Branch name or commit hash (e.g. master)") String branch,
      @ToolParam(description = "File path within the repository (e.g. src/main/java/App.java)")
          String filePath) {
    String ws = resolve(workspace, defaultWorkspace);
    log.info("Getting file content for workspace={}, repo={}, branch={}, path={}", ws, repoSlug, branch, filePath);
    try {
      String response = restClient.get()
          .uri("/repositories/{ws}/{repo}/src/{ref}/{path}", ws, repoSlug, branch, filePath)
          .retrieve()
          .body(String.class);
      log.debug("getFileContent response length={}", response != null ? response.length() : 0);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to get file content workspace={}, repo={}, branch={}, path={}: {}", ws, repoSlug, branch, filePath, e.getMessage());
      return error(e);
    }
  }

  @Tool(description = "List branches in a Bitbucket Cloud repository")
  public String listBranches(
      @ToolParam(description = "Workspace slug. Uses configured default if blank.") String workspace,
      @ToolParam(description = "Repository slug") String repoSlug) {
    String ws = resolve(workspace, defaultWorkspace);
    log.info("Listing branches for workspace={}, repo={}", ws, repoSlug);
    try {
      String response = restClient.get()
          .uri("/repositories/{ws}/{repo}/refs/branches?pagelen={len}", ws, repoSlug, DEFAULT_PAGE_LEN)
          .retrieve()
          .body(String.class);
      log.debug("listBranches response length={}", response != null ? response.length() : 0);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to list branches for workspace={}, repo={}: {}", ws, repoSlug, e.getMessage());
      return error(e);
    }
  }

  @Tool(description = "Get recent commits for a Bitbucket Cloud repository, optionally filtered by branch")
  public String getCommits(
      @ToolParam(description = "Workspace slug. Uses configured default if blank.") String workspace,
      @ToolParam(description = "Repository slug") String repoSlug,
      @ToolParam(description = "Branch name to filter commits (optional, omit for all branches)")
          String branch) {
    String ws = resolve(workspace, defaultWorkspace);
    log.info("Getting commits for workspace={}, repo={}, branch={}", ws, repoSlug, branch);
    try {
      String uri = (branch != null && !branch.isBlank())
          ? "/repositories/{ws}/{repo}/commits?include={branch}&pagelen={len}"
          : "/repositories/{ws}/{repo}/commits?pagelen={len}";
      String response = restClient.get()
          .uri(uri, ws, repoSlug, branch, DEFAULT_PAGE_LEN)
          .retrieve()
          .body(String.class);
      log.debug("getCommits response length={}", response != null ? response.length() : 0);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to get commits for workspace={}, repo={}, branch={}: {}", ws, repoSlug, branch, e.getMessage());
      return error(e);
    }
  }

  @Tool(description = "Create a new pull request in a Bitbucket Cloud repository")
  public String createPullRequest(
      @ToolParam(description = "Workspace slug. Uses configured default if blank.") String workspace,
      @ToolParam(description = "Repository slug") String repoSlug,
      @ToolParam(description = "Title of the pull request") String title,
      @ToolParam(description = "Source branch name (the branch with your changes)") String sourceBranch,
      @ToolParam(description = "Destination branch name (e.g. master, main, develop). Defaults to master if blank.")
          String destinationBranch,
      @ToolParam(description = "Description / body of the pull request (optional)") String description,
      @ToolParam(description = "Close source branch after merge (true/false, defaults to false)")
          boolean closeSourceBranch) {
    String ws = resolve(workspace, defaultWorkspace);
    String dest = resolve(destinationBranch, "master");
    log.info("Creating PR workspace={}, repo={}, title='{}', source={}, dest={}",
        ws, repoSlug, title, sourceBranch, dest);
    String descJson = (description != null && !description.isBlank())
        ? "\"description\":\"" + escapeJson(description) + "\","
        : "";
    String body = "{"
        + "\"title\":\"" + escapeJson(title) + "\","
        + descJson
        + "\"source\":{\"branch\":{\"name\":\"" + escapeJson(sourceBranch) + "\"}},"
        + "\"destination\":{\"branch\":{\"name\":\"" + escapeJson(dest) + "\"}},"
        + "\"close_source_branch\":" + closeSourceBranch
        + "}";
    try {
      String response = restClient.post()
          .uri("/repositories/{ws}/{repo}/pullrequests", ws, repoSlug)
          .body(body)
          .retrieve()
          .body(String.class);
      log.info("Successfully created PR workspace={}, repo={}, source={}", ws, repoSlug, sourceBranch);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to create PR workspace={}, repo={}, source={}: {}", ws, repoSlug, sourceBranch, e.getMessage());
      return error(e);
    }
  }

  @Tool(description = "Get all comments on a Bitbucket Cloud pull request")
  public String getPullRequestComments(
      @ToolParam(description = "Workspace slug. Uses configured default if blank.") String workspace,
      @ToolParam(description = "Repository slug") String repoSlug,
      @ToolParam(description = "Pull request ID (integer)") int prId) {
    String ws = resolve(workspace, defaultWorkspace);
    log.info("Getting comments for PR workspace={}, repo={}, prId={}", ws, repoSlug, prId);
    try {
      String response = restClient.get()
          .uri("/repositories/{ws}/{repo}/pullrequests/{id}/comments?pagelen={len}", ws, repoSlug, prId, DEFAULT_PAGE_LEN)
          .retrieve()
          .body(String.class);
      log.debug("getPullRequestComments response length={}", response != null ? response.length() : 0);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to get comments for PR workspace={}, repo={}, prId={}: {}", ws, repoSlug, prId, e.getMessage());
      return error(e);
    }
  }

  @Tool(description = "Add a comment to a Bitbucket Cloud pull request")
  public String addPullRequestComment(
      @ToolParam(description = "Workspace slug. Uses configured default if blank.") String workspace,
      @ToolParam(description = "Repository slug") String repoSlug,
      @ToolParam(description = "Pull request ID (integer)") int prId,
      @ToolParam(description = "Comment text to post") String comment) {
    String ws = resolve(workspace, defaultWorkspace);
    log.info("Adding comment to PR workspace={}, repo={}, prId={}", ws, repoSlug, prId);
    String body = "{\"content\":{\"raw\":\"" + escapeJson(comment) + "\"}}";
    try {
      String response = restClient.post()
          .uri("/repositories/{ws}/{repo}/pullrequests/{id}/comments", ws, repoSlug, prId)
          .body(body)
          .retrieve()
          .body(String.class);
      log.info("Successfully added comment to PR workspace={}, repo={}, prId={}", ws, repoSlug, prId);
      return response;
    } catch (RestClientException e) {
      log.error("Failed to add comment to PR workspace={}, repo={}, prId={}: {}", ws, repoSlug, prId, e.getMessage());
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
