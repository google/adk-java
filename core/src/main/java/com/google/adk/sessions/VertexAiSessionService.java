/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.sessions;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toCollection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Connects to the managed Vertex AI Session Service. */
/** TODO: Use the genai HttpApiClient and ApiResponse methods once they are public. */
public final class VertexAiSessionService implements BaseSessionService {
  private static final int MAX_RETRY_ATTEMPTS = 5;
  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();
  private static final Logger logger = LoggerFactory.getLogger(VertexAiSessionService.class);

  private final HttpApiClient apiClient;

  /**
   * Creates a new instance of the Vertex AI Session Service with a custom ApiClient for testing.
   */
  public VertexAiSessionService(String project, String location, HttpApiClient apiClient) {
    this.apiClient = apiClient;
  }

  public VertexAiSessionService() {
    this.apiClient =
        new HttpApiClient(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  public VertexAiSessionService(
      String project,
      String location,
      Optional<GoogleCredentials> credentials,
      Optional<HttpOptions> httpOptions) {
    this.apiClient =
        new HttpApiClient(Optional.of(project), Optional.of(location), credentials, httpOptions);
  }

  private static JsonNode getJsonResponse(ApiResponse apiResponse) {
    try {
      ResponseBody responseBody = apiResponse.getResponseBody();
      return objectMapper.readTree(responseBody.string());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {

    String reasoningEngineId = parseReasoningEngineId(appName);
    ConcurrentHashMap<String, Object> sessionJsonMap = new ConcurrentHashMap<>();
    sessionJsonMap.put("userId", userId);
    if (state != null) {
      sessionJsonMap.put("sessionState", state);
    }

    ApiResponse apiResponse;
    try {
      apiResponse =
          apiClient.request(
              "POST",
              "reasoningEngines/" + reasoningEngineId + "/sessions",
              objectMapper.writeValueAsString(sessionJsonMap));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    logger.debug("Create Session response {}", apiResponse.getResponseBody());
    String sessionName = "";
    String operationId = "";
    String sessId = nullToEmpty(sessionId);
    if (apiResponse.getResponseBody() != null) {
      JsonNode jsonResponse = getJsonResponse(apiResponse);
      sessionName = jsonResponse.get("name").asText();
      List<String> parts = Splitter.on('/').splitToList(sessionName);
      sessId = parts.get(parts.size() - 3);
      operationId = Iterables.getLast(parts);
    }
    for (int i = 0; i < MAX_RETRY_ATTEMPTS; i++) {
      ApiResponse lroResponse = apiClient.request("GET", "operations/" + operationId, "");
      JsonNode jsonResponse = getJsonResponse(lroResponse);
      if (jsonResponse.get("done") != null) {
        break;
      }
      try {
        SECONDS.sleep(1);
      } catch (InterruptedException e) {
        logger.warn("Error during sleep", e);
      }
    }

    ApiResponse getSessionApiResponse =
        apiClient.request(
            "GET", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessId, "");
    JsonNode getSessionResponseMap = getJsonResponse(getSessionApiResponse);
    Instant updateTimestamp = Instant.parse(getSessionResponseMap.get("updateTime").asText());
    ConcurrentMap<String, Object> sessionState = null;
    if (getSessionResponseMap != null && getSessionResponseMap.has("sessionState")) {
      JsonNode sessionStateNode = getSessionResponseMap.get("sessionState");
      if (sessionStateNode != null) {
        sessionState =
            objectMapper.convertValue(
                sessionStateNode, new TypeReference<ConcurrentMap<String, Object>>() {});
      }
    }
    return Single.just(
        Session.builder(sessId)
            .appName(appName)
            .userId(userId)
            .lastUpdateTime(updateTimestamp)
            .state(sessionState == null ? new ConcurrentHashMap<>() : sessionState)
            .build());
  }

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    String reasoningEngineId = parseReasoningEngineId(appName);

    ApiResponse apiResponse =
        apiClient.request(
            "GET",
            "reasoningEngines/" + reasoningEngineId + "/sessions?filter=user_id=" + userId,
            "");

    // Handles empty response case
    if (apiResponse.getResponseBody() == null) {
      return Single.just(ListSessionsResponse.builder().build());
    }

    JsonNode listSessionsResponseMap = getJsonResponse(apiResponse);
    List<Map<String, Object>> apiSessions =
        objectMapper.convertValue(
            listSessionsResponseMap.get("sessions"),
            new TypeReference<List<Map<String, Object>>>() {});

    List<Session> sessions = new ArrayList<>();
    for (Map<String, Object> apiSession : apiSessions) {
      String sessionId =
          Iterables.getLast(Splitter.on('/').splitToList((String) apiSession.get("name")));
      Instant updateTimestamp = Instant.parse((String) apiSession.get("updateTime"));
      Session session =
          Session.builder(sessionId)
              .appName(appName)
              .userId(userId)
              .state(new ConcurrentHashMap<>())
              .lastUpdateTime(updateTimestamp)
              .build();
      sessions.add(session);
    }
    return Single.just(ListSessionsResponse.builder().sessions(sessions).build());
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    String reasoningEngineId = parseReasoningEngineId(appName);
    ApiResponse apiResponse =
        apiClient.request(
            "GET",
            "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId + "/events",
            "");

    logger.debug("List events response {}", apiResponse);

    if (apiResponse.getResponseBody() == null) {
      return Single.just(ListEventsResponse.builder().build());
    }

    JsonNode sessionEventsNode = getJsonResponse(apiResponse).get("sessionEvents");
    if (sessionEventsNode == null || sessionEventsNode.isEmpty()) {
      return Single.just(ListEventsResponse.builder().events(new ArrayList<>()).build());
    }
    return Single.just(
        ListEventsResponse.builder()
            .events(
                objectMapper
                    .convertValue(
                        sessionEventsNode,
                        new TypeReference<List<ConcurrentMap<String, Object>>>() {})
                    .stream()
                    .map(event -> fromApiEvent(event))
                    .collect(toCollection(ArrayList::new)))
            .build());
  }

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
    String reasoningEngineId = parseReasoningEngineId(appName);
    ApiResponse apiResponse =
        apiClient.request(
            "GET", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId, "");
    JsonNode getSessionResponseMap = getJsonResponse(apiResponse);

    if (getSessionResponseMap == null) {
      return Maybe.empty();
    }

    String sessId =
        Optional.ofNullable(getSessionResponseMap.get("name"))
            .map(name -> Iterables.getLast(Splitter.on('/').splitToList(name.asText())))
            .orElse(sessionId);
    Instant updateTimestamp =
        Optional.ofNullable(getSessionResponseMap.get("updateTime"))
            .map(updateTime -> Instant.parse(updateTime.asText()))
            .orElse(null);

    ConcurrentMap<String, Object> sessionState = new ConcurrentHashMap<>();
    if (getSessionResponseMap != null && getSessionResponseMap.has("sessionState")) {
      sessionState.putAll(
          objectMapper.convertValue(
              getSessionResponseMap.get("sessionState"),
              new TypeReference<ConcurrentMap<String, Object>>() {}));
    }

    return listEvents(appName, userId, sessionId)
        .map(
            response -> {
              Session.Builder sessionBuilder =
                  Session.builder(sessId)
                      .appName(appName)
                      .userId(userId)
                      .lastUpdateTime(updateTimestamp)
                      .state(sessionState);
              List<Event> events = response.events();
              if (events.isEmpty()) {
                return sessionBuilder.build();
              }
              events =
                  events.stream()
                      .filter(
                          event ->
                              updateTimestamp == null
                                  || Instant.ofEpochMilli(event.timestamp())
                                      .isBefore(updateTimestamp))
                      .sorted(Comparator.comparing(Event::timestamp))
                      .collect(toCollection(ArrayList::new));

              if (config.isPresent()) {
                if (config.get().numRecentEvents().isPresent()) {
                  int numRecentEvents = config.get().numRecentEvents().get();
                  if (events.size() > numRecentEvents) {
                    events = events.subList(events.size() - numRecentEvents, events.size());
                  }
                } else if (config.get().afterTimestamp().isPresent()) {
                  Instant afterTimestamp = config.get().afterTimestamp().get();
                  int i = events.size() - 1;
                  while (i >= 0) {
                    if (Instant.ofEpochMilli(events.get(i).timestamp()).isBefore(afterTimestamp)) {
                      break;
                    }
                    i -= 1;
                  }
                  if (i >= 0) {
                    events = events.subList(i, events.size());
                  }
                }
              }
              return sessionBuilder.events(events).build();
            })
        .toMaybe();
  }

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    String reasoningEngineId = parseReasoningEngineId(appName);
    ApiResponse unused =
        apiClient.request(
            "DELETE", "reasoningEngines/" + reasoningEngineId + "/sessions/" + sessionId, "");
    return Completable.complete();
  }

  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    BaseSessionService.super.appendEvent(session, event);

    String reasoningEngineId = parseReasoningEngineId(session.appName());
    ApiResponse response =
        apiClient.request(
            "POST",
            "reasoningEngines/" + reasoningEngineId + "/sessions/" + session.id() + ":appendEvent",
            convertEventToJson(event));
    // TODO(b/414263934)): Improve error handling for appendEvent.
    try {
      if (response.getResponseBody().string().contains("com.google.genai.errors.ClientException")) {
        logger.warn("Failed to append event: ", event);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    response.close();
    return Single.just(event);
  }

  static String convertEventToJson(Event event) {
    Map<String, Object> metadataJson = new HashMap<>();
    metadataJson.put("partial", event.partial());
    metadataJson.put("turnComplete", event.turnComplete());
    metadataJson.put("interrupted", event.interrupted());
    metadataJson.put("branch", event.branch().orElse(null));
    metadataJson.put(
        "long_running_tool_ids",
        event.longRunningToolIds() != null ? event.longRunningToolIds().orElse(null) : null);
    if (event.groundingMetadata() != null) {
      metadataJson.put("grounding_metadata", event.groundingMetadata());
    }

    Map<String, Object> eventJson = new HashMap<>();
    eventJson.put("author", event.author());
    eventJson.put("invocationId", event.invocationId());
    eventJson.put(
        "timestamp",
        new HashMap<>(
            ImmutableMap.of(
                "seconds",
                event.timestamp() / 1000,
                "nanos",
                (event.timestamp() % 1000) * 1000000)));
    if (event.errorCode().isPresent()) {
      eventJson.put("errorCode", event.errorCode());
    }
    if (event.errorMessage().isPresent()) {
      eventJson.put("errorMessage", event.errorMessage());
    }
    eventJson.put("eventMetadata", metadataJson);

    if (event.actions() != null) {
      Map<String, Object> actionsJson = new HashMap<>();
      actionsJson.put("skipSummarization", event.actions().skipSummarization());
      actionsJson.put("stateDelta", event.actions().stateDelta());
      actionsJson.put("artifactDelta", event.actions().artifactDelta());
      actionsJson.put("transferAgent", event.actions().transferToAgent());
      actionsJson.put("escalate", event.actions().escalate());
      actionsJson.put("requestedAuthConfigs", event.actions().requestedAuthConfigs());
      eventJson.put("actions", actionsJson);
    }
    if (event.content().isPresent()) {
      eventJson.put("content", SessionUtils.encodeContent(event.content().get()));
    }
    if (event.errorCode().isPresent()) {
      eventJson.put("errorCode", event.errorCode().get());
    }
    if (event.errorMessage().isPresent()) {
      eventJson.put("errorMessage", event.errorMessage().get());
    }
    try {
      return objectMapper.writeValueAsString(eventJson);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Nullable
  @SuppressWarnings("unchecked")
  private static Content convertMapToContent(Object rawContentValue) {
    if (rawContentValue == null) {
      return null;
    }

    if (rawContentValue instanceof Map) {
      Map<String, Object> contentMap = (Map<String, Object>) rawContentValue;
      try {
        return objectMapper.convertValue(contentMap, Content.class);
      } catch (IllegalArgumentException e) {
        logger.warn("Error converting Map to Content", e);
        return null;
      }
    } else {
      logger.warn(
          "Unexpected type for 'content' in apiEvent: {}", rawContentValue.getClass().getName());
      return null;
    }
  }

  static String parseReasoningEngineId(String appName) {
    if (appName.matches("\\d+")) {
      return appName;
    }

    Matcher matcher = APP_NAME_PATTERN.matcher(appName);

    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "App name "
              + appName
              + " is not valid. It should either be the full"
              + " ReasoningEngine resource name, or the reasoning engine id.");
    }

    return matcher.group(matcher.groupCount());
  }

  @SuppressWarnings("unchecked")
  static Event fromApiEvent(Map<String, Object> apiEvent) {
    EventActions eventActions = new EventActions();
    if (apiEvent.get("actions") != null) {
      Map<String, Object> actionsMap = (Map<String, Object>) apiEvent.get("actions");
      eventActions.setSkipSummarization(
          Optional.ofNullable(actionsMap.get("skipSummarization")).map(value -> (Boolean) value));
      eventActions.setStateDelta(
          actionsMap.get("stateDelta") != null
              ? new ConcurrentHashMap<>((Map<String, Object>) actionsMap.get("stateDelta"))
              : new ConcurrentHashMap<>());
      eventActions.setArtifactDelta(
          actionsMap.get("artifactDelta") != null
              ? new ConcurrentHashMap<>((Map<String, Part>) actionsMap.get("artifactDelta"))
              : new ConcurrentHashMap<>());
      eventActions.setTransferToAgent(
          actionsMap.get("transferAgent") != null
              ? (String) actionsMap.get("transferAgent")
              : null);
      eventActions.setEscalate(
          Optional.ofNullable(actionsMap.get("escalate")).map(value -> (Boolean) value));
      eventActions.setRequestedAuthConfigs(
          Optional.ofNullable(actionsMap.get("requestedAuthConfigs"))
              .map(VertexAiSessionService::asConcurrentMapOfConcurrentMaps)
              .orElse(new ConcurrentHashMap<>()));
    }

    Event event =
        Event.builder()
            .id((String) Iterables.getLast(Splitter.on('/').split(apiEvent.get("name").toString())))
            .invocationId((String) apiEvent.get("invocationId"))
            .author((String) apiEvent.get("author"))
            .actions(eventActions)
            .content(
                Optional.ofNullable(apiEvent.get("content"))
                    .map(VertexAiSessionService::convertMapToContent)
                    .map(SessionUtils::decodeContent)
                    .orElse(null))
            .timestamp(convertToInstant(apiEvent.get("timestamp")).toEpochMilli())
            .errorCode(
                Optional.ofNullable(apiEvent.get("errorCode"))
                    .map(value -> new FinishReason((String) value)))
            .errorMessage(
                Optional.ofNullable(apiEvent.get("errorMessage")).map(value -> (String) value))
            .branch(Optional.ofNullable(apiEvent.get("branch")).map(value -> (String) value))
            .build();
    // TODO(b/414263934): Add Event branch and grounding metadata for python parity.
    if (apiEvent.get("eventMetadata") != null) {
      Map<String, Object> eventMetadata = (Map<String, Object>) apiEvent.get("eventMetadata");
      List<String> longRunningToolIdsList = (List<String>) eventMetadata.get("longRunningToolIds");

      GroundingMetadata groundingMetadata = null;
      Object rawGroundingMetadata = eventMetadata.get("groundingMetadata");
      if (rawGroundingMetadata != null) {
        groundingMetadata =
            objectMapper.convertValue(rawGroundingMetadata, GroundingMetadata.class);
      }

      event =
          event.toBuilder()
              .partial(Optional.ofNullable((Boolean) eventMetadata.get("partial")).orElse(false))
              .turnComplete(
                  Optional.ofNullable((Boolean) eventMetadata.get("turnComplete")).orElse(false))
              .interrupted(
                  Optional.ofNullable((Boolean) eventMetadata.get("interrupted")).orElse(false))
              .branch(Optional.ofNullable((String) eventMetadata.get("branch")))
              .groundingMetadata(groundingMetadata)
              .longRunningToolIds(
                  longRunningToolIdsList != null ? new HashSet<>(longRunningToolIdsList) : null)
              .build();
    }
    return event;
  }

  private static Instant convertToInstant(Object timestampObj) {
    if (timestampObj instanceof Map<?, ?> timestampMap) {
      return Instant.ofEpochSecond(
          ((Number) timestampMap.get("seconds")).longValue(),
          ((Number) timestampMap.get("nanos")).longValue());
    } else if (timestampObj != null) {
      return Instant.parse(timestampObj.toString());
    } else {
      throw new IllegalArgumentException("Timestamp not found in apiEvent");
    }
  }

  @SuppressWarnings("unchecked")
  private static ConcurrentMap<String, ConcurrentMap<String, Object>>
      asConcurrentMapOfConcurrentMaps(Object value) {
    return ((Map<String, Map<String, Object>>) value)
        .entrySet().stream()
            .collect(
                ConcurrentHashMap::new,
                (map, entry) -> map.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue())),
                ConcurrentHashMap::putAll);
  }

  private static final Pattern APP_NAME_PATTERN =
      Pattern.compile(
          "^projects/([a-zA-Z0-9-_]+)/locations/([a-zA-Z0-9-_]+)/reasoningEngines/(\\d+)$");
}
