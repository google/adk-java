package com.google.adk.sessions;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.genai.types.HttpOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client for interacting with the Vertex AI Session API. */
final class VertexAiClient {
  private static final int MAX_RETRY_ATTEMPTS = 5;
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();
  private static final Logger logger = LoggerFactory.getLogger(VertexAiClient.class);

  private final HttpApiClient apiClient;

  VertexAiClient(String project, String location, HttpApiClient apiClient) {
    this.apiClient = apiClient;
  }

  VertexAiClient() {
    this.apiClient =
        new HttpApiClient(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  VertexAiClient(
      String project,
      String location,
      Optional<GoogleCredentials> credentials,
      Optional<HttpOptions> httpOptions) {
    this.apiClient =
        new HttpApiClient(Optional.of(project), Optional.of(location), credentials, httpOptions);
  }

  @Nullable
  JsonNode createSession(
      String reasoningEngineId, String userId, ConcurrentMap<String, Object> state) {
    ConcurrentHashMap<String, Object> sessionJsonMap = new ConcurrentHashMap<>();
    sessionJsonMap.put("userId", userId);
    if (state != null) {
      sessionJsonMap.put("sessionState", state);
    }

    String sessId;
    String operationId;
    try {
      String sessionJson = objectMapper.writeValueAsString(sessionJsonMap);
      try (ApiResponse apiResponse =
          apiClient.request(
              "POST", "reasoningEngines/" + reasoningEngineId + "/sessions", sessionJson)) {
        logger.debug("Create Session response {}", apiResponse.getResponseBody());
        if (apiResponse == null || apiResponse.getResponseBody() == null) {
          return null;
        }

        JsonNode jsonResponse = getJsonResponse(apiResponse);
        if (jsonResponse == null) {
          return null;
        }
        String sessionName = jsonResponse.get("name").asText();
        List<String> parts = Splitter.on('/').splitToList(sessionName);
        sessId = parts.get(parts.size() - 3);
        operationId = Iterables.getLast(parts);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    for (int i = 0; i < MAX_RETRY_ATTEMPTS; i++) {
      try (ApiResponse lroResponse = apiClient.request("GET", "operations/" + operationId, "")) {
        JsonNode lroJsonResponse = getJsonResponse(lroResponse);
        if (lroJsonResponse != null && lroJsonResponse.get("done") != null) {
          break;
        }
      }
      try {
        SECONDS.sleep(1);
      } catch (InterruptedException e) {
        logger.warn("Error during sleep", e);
        Thread.currentThread().interrupt();
      }
    }
    return getSession(reasoningEngineId, sessId);
  }

  JsonNode listSessions(String reasoningEngineId, String userId) {
    try (ApiResponse apiResponse =
        apiClient.request(
            "GET",
            "reasoningEngines/" + reasoningEngineId + "/sessions?filter=user_id=" + userId,
            "")) {
      return getJsonResponse(apiResponse);
    }
  }

  JsonNode listEvents(String reasoningEngineId, String sessionId) {
    try (ApiResponse apiResponse =
        apiClient.request(
            "GET",
            "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId + "/events",
            "")) {
      logger.debug("List events response {}", apiResponse);
      return getJsonResponse(apiResponse);
    }
  }

  JsonNode getSession(String reasoningEngineId, String sessionId) {
    try (ApiResponse apiResponse =
        apiClient.request(
            "GET", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId, "")) {
      return getJsonResponse(apiResponse);
    }
  }

  void deleteSession(String reasoningEngineId, String sessionId) {
    try (ApiResponse response =
        apiClient.request(
            "DELETE", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId, "")) {}
  }

  void appendEvent(String reasoningEngineId, String sessionId, String eventJson) {
    try (ApiResponse response =
        apiClient.request(
            "POST",
            "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId + ":appendEvent",
            eventJson)) {
      if (response.getResponseBody().string().contains("com.google.genai.errors.ClientException")) {
        logger.warn("Failed to append event: {}", eventJson);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Parses the JSON response body from the given API response.
   *
   * @throws UncheckedIOException if parsing fails.
   */
  @Nullable
  private static JsonNode getJsonResponse(ApiResponse apiResponse) {
    if (apiResponse == null || apiResponse.getResponseBody() == null) {
      return null;
    }
    try {
      ResponseBody responseBody = apiResponse.getResponseBody();
      String responseString = responseBody.string();
      if (responseString.isEmpty()) {
        return null;
      }
      return objectMapper.readTree(responseString);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
