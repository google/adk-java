package com.google.adk.sessions;

import static java.util.concurrent.TimeUnit.SECONDS;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
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

  Maybe<JsonNode> createSession(
      String reasoningEngineId, String userId, ConcurrentMap<String, Object> state) {
    ConcurrentHashMap<String, Object> sessionJsonMap = new ConcurrentHashMap<>();
    sessionJsonMap.put("userId", userId);
    if (state != null) {
      sessionJsonMap.put("sessionState", state);
    }

    return Single.fromCallable(() -> objectMapper.writeValueAsString(sessionJsonMap))
        .flatMap(
            sessionJson ->
                performApiRequest(
                    "POST", "reasoningEngines/" + reasoningEngineId + "/sessions", sessionJson))
        .flatMapMaybe(
            apiResponse -> {
              logger.debug("Create Session response {}", apiResponse.getResponseBody());
              return getJsonResponse(apiResponse);
            })
        .flatMap(
            jsonResponse -> {
              String sessionName = jsonResponse.get("name").asText();
              List<String> parts = Splitter.on('/').splitToList(sessionName);
              String sessId = parts.get(parts.size() - 3);
              String operationId = Iterables.getLast(parts);

              return pollOperation(operationId, 0).andThen(getSession(reasoningEngineId, sessId));
            });
  }

  /**
   * Polls the status of a long-running operation.
   *
   * @param operationId The ID of the operation to poll.
   * @param attempt The current retry attempt number (starting from 0).
   * @return A Completable that completes when the operation is done, or errors with
   *     TimeoutException if max retries are exceeded.
   */
  private Completable pollOperation(String operationId, int attempt) {
    if (attempt >= MAX_RETRY_ATTEMPTS) {
      return Completable.error(
          new TimeoutException("Operation " + operationId + " did not complete in time."));
    }
    return performApiRequest("GET", "operations/" + operationId, "")
        .flatMapMaybe(VertexAiClient::getJsonResponse)
        .flatMapCompletable(
            lroJsonResponse -> {
              if (lroJsonResponse != null && lroJsonResponse.get("done") != null) {
                return Completable.complete(); // Operation is done
              } else {
                // Not done, retry after a delay
                return Completable.timer(1, SECONDS)
                    .andThen(pollOperation(operationId, attempt + 1));
              }
            });
  }

  Maybe<JsonNode> listSessions(String reasoningEngineId, String userId) {
    return performApiRequest(
            "GET",
            "reasoningEngines/" + reasoningEngineId + "/sessions?filter=user_id=" + userId,
            "")
        .flatMapMaybe(VertexAiClient::getJsonResponse);
  }

  Maybe<JsonNode> listEvents(String reasoningEngineId, String sessionId) {
    return performApiRequest(
            "GET",
            "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId + "/events",
            "")
        .doOnSuccess(apiResponse -> logger.debug("List events response {}", apiResponse))
        .flatMapMaybe(VertexAiClient::getJsonResponse);
  }

  Maybe<JsonNode> getSession(String reasoningEngineId, String sessionId) {
    return performApiRequest(
            "GET", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId, "")
        .flatMapMaybe(apiResponse -> getJsonResponse(apiResponse));
  }

  Completable deleteSession(String reasoningEngineId, String sessionId) {
    return performApiRequest(
            "DELETE", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId, "")
        .doOnSuccess(ApiResponse::close)
        .ignoreElement();
  }

  Completable appendEvent(String reasoningEngineId, String sessionId, String eventJson) {
    return performApiRequest(
            "POST",
            "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId + ":appendEvent",
            eventJson)
        .flatMapCompletable(
            response -> {
              try (response) {
                ResponseBody responseBody = response.getResponseBody();
                if (responseBody != null) {
                  String responseString = responseBody.string();
                  if (responseString.contains("com.google.genai.errors.ClientException")) {
                    logger.warn("Failed to append event: {}", eventJson);
                  }
                }
                return Completable.complete();
              } catch (IOException e) {
                return Completable.error(new UncheckedIOException(e));
              }
            });
  }

  /**
   * Performs an API request and returns a Single emitting the ApiResponse.
   *
   * <p>Note: The caller is responsible for closing the returned {@link ApiResponse}.
   */
  private Single<ApiResponse> performApiRequest(String method, String path, String body) {
    return Single.fromCallable(
        () -> {
          return apiClient.request(method, path, body);
        });
  }

  /**
   * Parses the JSON response body from the given API response.
   *
   * @throws UncheckedIOException if parsing fails.
   */
  @Nullable
  private static Maybe<JsonNode> getJsonResponse(ApiResponse apiResponse) {
    try {
      if (apiResponse == null || apiResponse.getResponseBody() == null) {
        return Maybe.empty();
      }
      try {
        ResponseBody responseBody = apiResponse.getResponseBody();
        String responseString = responseBody.string(); // Read body here
        if (responseString.isEmpty()) {
          return Maybe.empty();
        }
        return Maybe.just(objectMapper.readTree(responseString));
      } catch (IOException e) {
        return Maybe.error(new UncheckedIOException(e));
      }
    } finally {
      apiResponse.close();
    }
  }
}
