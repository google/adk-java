package com.google.adk.samples.stale.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.agents.LlmAgent;
import com.google.adk.samples.stale.config.StaleBotSettings;
import com.google.adk.samples.stale.utils.GitHubUtils;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StaleAgent {
  private static final Logger logger = LoggerFactory.getLogger(StaleAgent.class);

  private static final String BOT_ALERT_SIGNATURE =
      "**Notification:** The author has updated the issue description";
  private static final String BOT_NAME = "adk-bot";

  private List<String> maintainersCache = null;

  public static LlmAgent create() {
    StaleAgent toolInstance = new StaleAgent();
    String prompt = toolInstance.loadAndFormatPrompt();

    return LlmAgent.builder()
        .name("adk_repository_auditor_agent")
        .description("Audits open issues.")
        .instruction(prompt)
        .model(StaleBotSettings.LLM_MODEL_NAME)
        .tools(
            FunctionTool.create(toolInstance, "addLabelToIssue"),
            FunctionTool.create(toolInstance, "addStaleLabelAndComment"),
            FunctionTool.create(toolInstance, "alertMaintainerOfEdit"),
            FunctionTool.create(toolInstance, "closeAsStale"),
            FunctionTool.create(toolInstance, "getIssueState"),
            FunctionTool.create(toolInstance, "removeLabelFromIssue"))
        .build();
  }

  private String loadAndFormatPrompt() {
    try (InputStream is = getClass().getResourceAsStream("/PROMPT_INSTRUCTION.txt")) {
      if (is == null) throw new RuntimeException("PROMPT_INSTRUCTION.txt not found");

      String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);

      return template
          .replace("{OWNER}", StaleBotSettings.OWNER)
          .replace("{REPO}", StaleBotSettings.REPO)
          .replace("{STALE_LABEL_NAME}", StaleBotSettings.STALE_LABEL_NAME)
          .replace("{REQUEST_CLARIFICATION_LABEL}", StaleBotSettings.REQUEST_CLARIFICATION_LABEL)
          .replace(
              "{stale_threshold_days}",
              String.valueOf(StaleBotSettings.STALE_HOURS_THRESHOLD / 24.0))
          .replace(
              "{close_threshold_days}",
              String.valueOf(StaleBotSettings.CLOSE_HOURS_AFTER_STALE_THRESHOLD / 24.0));

    } catch (IOException e) {
      throw new RuntimeException("Failed to load prompt template", e);
    }
  }

  private List<String> getCachedMaintainers() {
    if (maintainersCache != null) return maintainersCache;

    logger.info("Initializing Maintainers Cache...");
    try {
      String url =
          String.format(
              "%s/repos/%s/%s/collaborators",
              StaleBotSettings.GITHUB_BASE_URL, StaleBotSettings.OWNER, StaleBotSettings.REPO);

      JsonNode data = GitHubUtils.getRequest(url, Map.of("permission", "push"));

      if (data.isArray()) {
        List<String> list = new ArrayList<>();
        for (JsonNode u : data) {
          if (u.has("login")) {
            list.add(u.get("login").asText());
          }
        }
        maintainersCache = list;
        logger.info("Cached {} maintainers.", list.size());
        return maintainersCache;
      } else {
        throw new IllegalArgumentException("GitHub API returned non-list data");
      }
    } catch (Exception e) {
      logger.error("FATAL: Failed to verify repository maintainers.", e);
      throw new RuntimeException("Maintainer verification failed. processing aborted.", e);
    }
  }

  private JsonNode fetchGraphqlData(int itemNumber) {
    String query =
        """
				query($owner: String!, $name: String!, $number: Int!, $commentLimit: Int!, $timelineLimit: Int!, $editLimit: Int!) {
				  repository(owner: $owner, name: $name) {
				    issue(number: $number) {
				      author { login }
				      createdAt
				      labels(first: 20) { nodes { name } }
				      comments(last: $commentLimit) {
				        nodes {
				          author { login }
				          body
				          createdAt
				          lastEditedAt
				        }
				      }
				      userContentEdits(last: $editLimit) {
				        nodes {
				          editor { login }
				          editedAt
				        }
				      }
				      timelineItems(itemTypes: [LABELED_EVENT, RENAMED_TITLE_EVENT, REOPENED_EVENT], last: $timelineLimit) {
				        nodes {
				          __typename
				          ... on LabeledEvent {
				            createdAt
				            actor { login }
				            label { name }
				          }
				          ... on RenamedTitleEvent {
				            createdAt
				            actor { login }
				          }
				          ... on ReopenedEvent {
				            createdAt
				            actor { login }
				          }
				        }
				      }
				    }
				  }
				}
				""";

    Map<String, Object> variables = new HashMap<>();
    variables.put("owner", StaleBotSettings.OWNER);
    variables.put("name", StaleBotSettings.REPO);
    variables.put("number", itemNumber);
    variables.put("commentLimit", StaleBotSettings.GRAPHQL_COMMENT_LIMIT);
    variables.put("editLimit", StaleBotSettings.GRAPHQL_EDIT_LIMIT);
    variables.put("timelineLimit", StaleBotSettings.GRAPHQL_TIMELINE_LIMIT);

    JsonNode response =
        GitHubUtils.postRequest(
            StaleBotSettings.GITHUB_BASE_URL + "/graphql",
            Map.of("query", query, "variables", variables));

    if (response.has("errors")) {
      throw new RuntimeException(
          "GraphQL Error: " + response.get("errors").get(0).get("message").asText());
    }

    JsonNode data = response.path("data").path("repository").path("issue");
    if (data.isMissingNode() || data.isNull()) {
      throw new RuntimeException("Issue #" + itemNumber + " not found.");
    }
    return data;
  }

  // Data structure to hold history analysis results
  private record HistoryResult(
      List<Map<String, Object>> history, List<Instant> labelEvents, Instant lastBotAlertTime) {}

  private HistoryResult buildHistoryTimeline(JsonNode data) {
    String issueAuthor = data.path("author").path("login").asText(null);
    List<Map<String, Object>> history = new ArrayList<>();
    List<Instant> labelEvents = new ArrayList<>();
    Instant lastBotAlertTime = null;

    Map<String, Object> createdEvent = new HashMap<>();
    createdEvent.put("type", "created");
    createdEvent.put("actor", issueAuthor);
    createdEvent.put("time", Instant.parse(data.get("createdAt").asText()));
    createdEvent.put("data", null);
    history.add(createdEvent);

    for (JsonNode c : data.path("comments").path("nodes")) {
      String actor = c.path("author").path("login").asText(null);
      String body = c.path("body").asText("");
      Instant cTime = Instant.parse(c.get("createdAt").asText());

      if (body.contains(BOT_ALERT_SIGNATURE)) {
        if (lastBotAlertTime == null || cTime.isAfter(lastBotAlertTime)) {
          lastBotAlertTime = cTime;
        }
        continue;
      }

      if (actor != null && !actor.endsWith("[bot]") && !actor.equals(BOT_NAME)) {
        Instant eTime =
            c.hasNonNull("lastEditedAt") ? Instant.parse(c.get("lastEditedAt").asText()) : null;
        Instant actualTime = (eTime != null) ? eTime : cTime;

        Map<String, Object> event = new HashMap<>();
        event.put("type", "commented");
        event.put("actor", actor);
        event.put("time", actualTime);
        event.put("data", body);
        history.add(event);
      }
    }

    for (JsonNode e : data.path("userContentEdits").path("nodes")) {
      String actor = e.path("editor").path("login").asText(null);
      if (actor != null && !actor.endsWith("[bot]") && !actor.equals(BOT_NAME)) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "edited_description");
        event.put("actor", actor);
        event.put("time", Instant.parse(e.get("editedAt").asText()));
        event.put("data", null);
        history.add(event);
      }
    }

    for (JsonNode t : data.path("timelineItems").path("nodes")) {
      String type = t.get("__typename").asText();
      String actor = t.path("actor").path("login").asText(null);
      Instant timeVal = Instant.parse(t.get("createdAt").asText());

      if ("LabeledEvent".equals(type)) {
        String labelName = t.path("label").path("name").asText();
        if (StaleBotSettings.STALE_LABEL_NAME.equals(labelName)) {
          labelEvents.add(timeVal);
        }
        continue;
      }

      if (actor != null && !actor.endsWith("[bot]") && !actor.equals(BOT_NAME)) {
        String prettyType = "RenamedTitleEvent".equals(type) ? "renamed_title" : "reopened";
        Map<String, Object> event = new HashMap<>();
        event.put("type", prettyType);
        event.put("actor", actor);
        event.put("time", timeVal);
        event.put("data", null);
        history.add(event);
      }
    }

    history.sort(Comparator.comparing(m -> (Instant) m.get("time")));
    return new HistoryResult(history, labelEvents, lastBotAlertTime);
  }

  private Map<String, Object> replayHistoryToFindState(
      List<Map<String, Object>> history, List<String> maintainers, String issueAuthor) {
    String lastActionRole = "author";
    Instant lastActivityTime = (Instant) history.get(0).get("time");
    String lastActionType = "created";
    String lastCommentText = null;
    String lastActorName = issueAuthor;

    for (Map<String, Object> event : history) {
      String actor = (String) event.get("actor");
      String type = (String) event.get("type");

      String role = "other_user";
      if (Objects.equals(actor, issueAuthor)) role = "author";
      else if (maintainers.contains(actor)) role = "maintainer";

      lastActionRole = role;
      lastActivityTime = (Instant) event.get("time");
      lastActionType = type;
      lastActorName = actor;

      if ("commented".equals(type)) {
        Object data = event.get("data");
        lastCommentText = data != null ? data.toString() : "";
      } else {
        lastCommentText = "";
      }
    }

    Map<String, Object> state = new HashMap<>();
    state.put("last_action_role", lastActionRole);
    state.put("last_activity_time", lastActivityTime);
    state.put("last_action_type", lastActionType);
    state.put("last_comment_text", lastCommentText);
    state.put("last_actor_name", lastActorName);
    return state;
  }

  @Schema(description = "Retrieves the comprehensive state of a GitHub issue using GraphQL.")
  public Map<String, Object> getIssueState(
      @Schema(description = "The GitHub issue number") int itemNumber) {
    try {
      List<String> maintainers = getCachedMaintainers();

      JsonNode rawData = fetchGraphqlData(itemNumber);
      String issueAuthor = rawData.path("author").path("login").asText(null);

      List<String> labelsList = new ArrayList<>();
      for (JsonNode l : rawData.path("labels").path("nodes")) {
        labelsList.add(l.get("name").asText());
      }

      HistoryResult historyResult = buildHistoryTimeline(rawData);

      Map<String, Object> state =
          replayHistoryToFindState(historyResult.history, maintainers, issueAuthor);
      Instant lastActivityTime = (Instant) state.get("last_activity_time");

      Instant currentTime = Instant.now();
      double daysSinceActivity =
          Duration.between(lastActivityTime, currentTime).toSeconds() / 86400.0;

      boolean isStale = labelsList.contains(StaleBotSettings.STALE_LABEL_NAME);
      double daysSinceStaleLabel = 0.0;
      if (isStale && !historyResult.labelEvents.isEmpty()) {
        Instant latestLabelTime = Collections.max(historyResult.labelEvents);
        daysSinceStaleLabel = Duration.between(latestLabelTime, currentTime).toSeconds() / 86400.0;
      }

      boolean maintainerAlertNeeded = false;
      String lastRole = (String) state.get("last_action_role");
      String lastType = (String) state.get("last_action_type");

      if (List.of("author", "other_user").contains(lastRole)
          && "edited_description".equals(lastType)) {
        if (historyResult.lastBotAlertTime != null
            && historyResult.lastBotAlertTime.isAfter(lastActivityTime)) {
          logger.info("#{}: Silent edit detected, but Bot already alerted.", itemNumber);
        } else {
          maintainerAlertNeeded = true;
          logger.info("#{}: Silent edit detected. Alert needed.", itemNumber);
        }
      }

      logger.debug(
          "#{} VERDICT: Role={}, Idle={}d",
          itemNumber,
          lastRole,
          String.format("%.2f", daysSinceActivity));

      Map<String, Object> result = new HashMap<>();
      result.put("status", "success");
      result.put("last_action_role", state.get("last_action_role"));
      result.put("last_action_type", state.get("last_action_type"));
      result.put("last_actor_name", state.get("last_actor_name"));
      result.put("maintainer_alert_needed", maintainerAlertNeeded);
      result.put("is_stale", isStale);
      result.put("days_since_activity", daysSinceActivity);
      result.put("days_since_stale_label", daysSinceStaleLabel);
      result.put("last_comment_text", state.get("last_comment_text"));
      result.put("current_labels", labelsList);
      result.put("stale_threshold_days", StaleBotSettings.STALE_HOURS_THRESHOLD / 24.0);
      result.put("close_threshold_days", StaleBotSettings.CLOSE_HOURS_AFTER_STALE_THRESHOLD / 24.0);
      result.put("maintainers", maintainers);
      result.put("issue_author", issueAuthor);
      result.put("last_comment_text", state.get("last_comment_text"));
      return result;

    } catch (Exception e) {
      logger.error("Error analyzing issue #" + itemNumber, e);
      return GitHubUtils.errorResponse("Analysis Error: " + e.getMessage());
    }
  }

  private String formatDays(double hours) {
    double days = hours / 24.0;
    if (days % 1 == 0) return String.format("%.0f", days);
    return String.format("%.1f", days);
  }

  @Schema(description = "Adds a label to the issue.")
  public Map<String, Object> addLabelToIssue(
      @Schema(description = "The GitHub issue number") int itemNumber,
      @Schema(description = "The name of the label") String labelName) {

    logger.debug("Adding label '{}' to issue #{}", labelName, itemNumber);
    String url =
        String.format(
            "%s/repos/%s/%s/issues/%d/labels",
            StaleBotSettings.GITHUB_BASE_URL,
            StaleBotSettings.OWNER,
            StaleBotSettings.REPO,
            itemNumber);
    try {
      GitHubUtils.postRequest(url, List.of(labelName));
      return Map.of("status", "success");
    } catch (Exception e) {
      return GitHubUtils.errorResponse("Error adding label: " + e.getMessage());
    }
  }

  @Schema(description = "Removes a label from the issue.")
  public Map<String, Object> removeLabelFromIssue(
      @Schema(description = "The GitHub issue number") int itemNumber,
      @Schema(description = "The name of the label") String labelName) {

    logger.debug("Removing label '{}' from issue #{}", labelName, itemNumber);
    String url =
        String.format(
            "%s/repos/%s/%s/issues/%d/labels/%s",
            StaleBotSettings.GITHUB_BASE_URL,
            StaleBotSettings.OWNER,
            StaleBotSettings.REPO,
            itemNumber,
            labelName);
    try {
      GitHubUtils.deleteRequest(url);
      return Map.of("status", "success");
    } catch (Exception e) {
      return GitHubUtils.errorResponse("Error removing label: " + e.getMessage());
    }
  }

  @Schema(description = "Marks the issue as stale with a comment and label.")
  public Map<String, Object> addStaleLabelAndComment(
      @Schema(description = "The GitHub issue number") int itemNumber) {
    String staleDays = formatDays(StaleBotSettings.STALE_HOURS_THRESHOLD);
    String closeDays = formatDays(StaleBotSettings.CLOSE_HOURS_AFTER_STALE_THRESHOLD);

    String comment =
        String.format(
            "This issue has been automatically marked as stale because there is no recent activity for %s days "
                + "after a maintainer requested clarification. It will be closed if no further activity occurs within %s days.",
            staleDays, closeDays);

    try {
      String commentUrl =
          String.format(
              "%s/repos/%s/%s/issues/%d/comments",
              StaleBotSettings.GITHUB_BASE_URL,
              StaleBotSettings.OWNER,
              StaleBotSettings.REPO,
              itemNumber);
      GitHubUtils.postRequest(commentUrl, Map.of("body", comment));

      String labelUrl =
          String.format(
              "%s/repos/%s/%s/issues/%d/labels",
              StaleBotSettings.GITHUB_BASE_URL,
              StaleBotSettings.OWNER,
              StaleBotSettings.REPO,
              itemNumber);
      GitHubUtils.postRequest(labelUrl, List.of(StaleBotSettings.STALE_LABEL_NAME));
      logger.debug(" label url : '{}' and Comment url : '{}'", commentUrl, labelUrl);

      return Map.of("status", "success");
    } catch (Exception e) {
      return GitHubUtils.errorResponse("Error marking issue as stale: " + e.getMessage());
    }
  }

  @Schema(description = "Posts a comment alerting maintainers of a silent description update.")
  public Map<String, Object> alertMaintainerOfEdit(
      @Schema(description = "The GitHub issue number") int itemNumber) {
    String comment = BOT_ALERT_SIGNATURE + ". Maintainers, please review.";
    try {
      String url =
          String.format(
              "%s/repos/%s/%s/issues/%d/comments",
              StaleBotSettings.GITHUB_BASE_URL,
              StaleBotSettings.OWNER,
              StaleBotSettings.REPO,
              itemNumber);
      GitHubUtils.postRequest(url, Map.of("body", comment));
      return Map.of("status", "success");
    } catch (Exception e) {
      return GitHubUtils.errorResponse("Error posting alert: " + e.getMessage());
    }
  }

  @Schema(description = "Closes the issue as not planned/stale.")
  public Map<String, Object> closeAsStale(
      @Schema(description = "The GitHub issue number") int itemNumber) {
    String daysStr = formatDays(StaleBotSettings.CLOSE_HOURS_AFTER_STALE_THRESHOLD);
    String comment =
        String.format(
            "This has been automatically closed because it has been marked as stale for over %s days.",
            daysStr);

    try {
      String commentUrl =
          String.format(
              "%s/repos/%s/%s/issues/%d/comments",
              StaleBotSettings.GITHUB_BASE_URL,
              StaleBotSettings.OWNER,
              StaleBotSettings.REPO,
              itemNumber);
      GitHubUtils.postRequest(commentUrl, Map.of("body", comment));

      String patchUrl =
          String.format(
              "%s/repos/%s/%s/issues/%d",
              StaleBotSettings.GITHUB_BASE_URL,
              StaleBotSettings.OWNER,
              StaleBotSettings.REPO,
              itemNumber);
      GitHubUtils.patchRequest(patchUrl, Map.of("state", "closed"));

      return Map.of("status", "success");
    } catch (Exception e) {
      return GitHubUtils.errorResponse("Error closing issue: " + e.getMessage());
    }
  }
}
