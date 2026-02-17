package com.google.adk.sessions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Mocks the http calls to Vertex AI API. */
class MockApiAnswer implements Answer<ApiResponse> {
  private static final ObjectMapper mapper = JsonBaseModel.getMapper();
  private static final Pattern LRO_REGEX = Pattern.compile("^operations/([^/]+)$");
  private static final Pattern SESSION_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions/([^/]+)$");
  private static final Pattern SESSIONS_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions$");
  private static final Pattern SESSIONS_FILTER_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions\\?filter=user_id=([^/]+)$");
  private static final Pattern APPEND_EVENT_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions/([^/]+):appendEvent$");
  private static final Pattern EVENTS_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions/([^/]+)/events$");
  private static final MediaType JSON_MEDIA_TYPE =
      MediaType.parse("application/json; charset=utf-8");

  private final Map<String, String> sessionMap;
  private final Map<String, String> eventMap;

  MockApiAnswer(Map<String, String> sessionMap, Map<String, String> eventMap) {
    this.sessionMap = sessionMap;
    this.eventMap = eventMap;
  }

  @Override
  public ApiResponse answer(InvocationOnMock invocation) throws Throwable {
    String httpMethod = invocation.getArgument(0);
    String path = invocation.getArgument(1);
    if (httpMethod.equals("POST") && SESSIONS_REGEX.matcher(path).matches()) {
      return handleCreateSession(path, invocation);
    } else if (httpMethod.equals("GET") && SESSION_REGEX.matcher(path).matches()) {
      return handleGetSession(path);
    } else if (httpMethod.equals("GET") && SESSIONS_FILTER_REGEX.matcher(path).matches()) {
      return handleGetSessions(path);
    } else if (httpMethod.equals("POST") && APPEND_EVENT_REGEX.matcher(path).matches()) {
      return handleAppendEvent(path, invocation);
    } else if (httpMethod.equals("GET") && EVENTS_REGEX.matcher(path).matches()) {
      return handleGetEvents(path);
    } else if (httpMethod.equals("GET") && LRO_REGEX.matcher(path).matches()) {
      return handleGetLro(path);
    } else if (httpMethod.equals("DELETE")) {
      return handleDeleteSession(path);
    }
    throw new RuntimeException(
        String.format("Unsupported HTTP method: %s, path: %s", httpMethod, path));
  }

  private static ApiResponse responseWithBody(String body) {
    return new ApiResponse() {
      @Override
      public ResponseBody getResponseBody() {
        return ResponseBody.create(JSON_MEDIA_TYPE, body);
      }

      @Override
      public void close() {}
    };
  }

  private ApiResponse handleCreateSession(String path, InvocationOnMock invocation)
      throws Exception {
    String newSessionId = "4";
    Map<String, Object> requestDict =
        mapper.readValue(
            (String) invocation.getArgument(2), new TypeReference<Map<String, Object>>() {});
    Map<String, Object> newSessionData = new HashMap<>();
    newSessionData.put("name", path + "/" + newSessionId);
    newSessionData.put("userId", requestDict.get("userId"));
    newSessionData.put("sessionState", requestDict.get("sessionState"));
    newSessionData.put("updateTime", "2024-12-12T12:12:12.123456Z");

    sessionMap.put(newSessionId, mapper.writeValueAsString(newSessionData));

    return responseWithBody(
        String.format(
            """
            {
              "name": "%s/%s/operations/111",
              "done": false
            }
            """,
            path, newSessionId));
  }

  private ApiResponse handleGetSession(String path) throws Exception {
    String sessionId = path.substring(path.lastIndexOf('/') + 1);
    if (sessionId.contains("/")) { // Ensure it's a direct session ID
      return null;
    }
    String sessionData = sessionMap.get(sessionId);
    if (sessionData != null) {
      return responseWithBody(sessionData);
    } else {
      throw new RuntimeException("Session not found: " + sessionId);
    }
  }

  private ApiResponse handleGetSessions(String path) throws Exception {
    Matcher sessionsMatcher = SESSIONS_FILTER_REGEX.matcher(path);
    if (!sessionsMatcher.matches()) {
      return null;
    }
    String userId = sessionsMatcher.group(2);
    List<String> userSessionsJson = new ArrayList<>();
    for (String sessionJson : sessionMap.values()) {
      Map<String, Object> session =
          mapper.readValue(sessionJson, new TypeReference<Map<String, Object>>() {});
      if (session.containsKey("userId") && session.get("userId").equals(userId)) {
        userSessionsJson.add(sessionJson);
      }
    }
    return responseWithBody(
        String.format(
            """
            {
              "sessions": [%s]
            }
            """,
            String.join(",", userSessionsJson)));
  }

  private ApiResponse handleAppendEvent(String path, InvocationOnMock invocation) {
    Matcher appendEventMatcher = APPEND_EVENT_REGEX.matcher(path);
    if (!appendEventMatcher.matches()) {
      return null;
    }
    String sessionId = appendEventMatcher.group(2);
    String eventDataString = eventMap.get(sessionId);
    String newEventDataString = (String) invocation.getArgument(2);
    try {
      ConcurrentMap<String, Object> newEventData =
          mapper.readValue(
              newEventDataString, new TypeReference<ConcurrentMap<String, Object>>() {});

      List<ConcurrentMap<String, Object>> eventsData = new ArrayList<>();
      if (eventDataString != null) {
        eventsData.addAll(
            mapper.readValue(
                eventDataString, new TypeReference<List<ConcurrentMap<String, Object>>>() {}));
      }

      newEventData.put(
          "name", path.replaceFirst(":appendEvent$", "/events/" + Event.generateEventId()));

      eventsData.add(newEventData);

      eventMap.put(sessionId, mapper.writeValueAsString(eventsData));

      // Apply stateDelta to session state
      extractObjectMap(newEventData, "actions")
          .flatMap(actions -> extractObjectMap(actions, "stateDelta"))
          .ifPresent(
              stateDelta -> {
                try {
                  applyStateDelta(sessionId, stateDelta);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              });
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return responseWithBody(newEventDataString);
  }

  private ApiResponse handleGetEvents(String path) throws Exception {
    Matcher matcher = EVENTS_REGEX.matcher(path);
    if (!matcher.matches()) {
      return null;
    }
    String sessionId = matcher.group(2);
    String eventData = eventMap.get(sessionId);
    if (eventData != null) {
      return responseWithBody(
          String.format(
              """
              {
                "sessionEvents": %s
              }
              """,
              eventData));
    } else {
      // Return an empty list if no events are found for the session
      return responseWithBody("{}");
    }
  }

  private ApiResponse handleGetLro(String path) {
    return responseWithBody(
        String.format(
            """
            {
              "name": "%s",
              "done": true
            }
            """,
            path.replace("/operations/111", ""))); // Simulate LRO done
  }

  private ApiResponse handleDeleteSession(String path) {
    Matcher sessionMatcher = SESSION_REGEX.matcher(path);
    if (!sessionMatcher.matches()) {
      return null;
    }
    String sessionIdToDelete = sessionMatcher.group(2);
    sessionMap.remove(sessionIdToDelete);
    return responseWithBody("");
  }

  private void applyStateDelta(String sessionId, Map<String, Object> stateDelta) throws Exception {
    String sessionDataString = sessionMap.get(sessionId);
    if (sessionDataString == null) {
      return;
    }
    Map<String, Object> sessionData =
        mapper.readValue(sessionDataString, new TypeReference<Map<String, Object>>() {});
    Map<String, Object> sessionState =
        extractObjectMap(sessionData, "sessionState").map(HashMap::new).orElseGet(HashMap::new);

    for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
      if (entry.getValue() == null) {
        sessionState.remove(entry.getKey());
      } else {
        sessionState.put(entry.getKey(), entry.getValue());
      }
    }
    sessionData.put("sessionState", sessionState);
    sessionMap.put(sessionId, mapper.writeValueAsString(sessionData));
  }

  @SuppressWarnings("unchecked") // Safe because map values are Maps read from JSON.
  private Optional<Map<String, Object>> extractObjectMap(Map<String, Object> map, String key) {
    return Optional.ofNullable((Map<String, Object>) map.get(key));
  }
}
