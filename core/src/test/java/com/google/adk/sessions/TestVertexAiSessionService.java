package com.google.adk.sessions;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.http.HttpEntity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link VertexAiSessionService}. */
@RunWith(JUnit4.class)
public class TestVertexAiSessionService {

  private static final ObjectMapper mapper = JsonBaseModel.getMapper();

  private static final String MOCK_SESSION_STRING_1 =
      """
      {
        "name" : "projects/test-project/locations/test-location/reasoningEngines/123/sessions/1",
        "createTime" : "2024-12-12T12:12:12.123456Z",
        "userId" : "user",
        "updateTime" : "2024-12-12T12:12:12.123456Z",
        "sessionState" : {
          "key" : {
            "value" : "testValue"
          }
        }
      }\
      """;

  private static final String MOCK_SESSION_STRING_2 =
      """
      {
        "name" : "projects/test-project/locations/test-location/reasoningEngines/123/sessions/2",
        "userId" : "user",
        "updateTime" : "2024-12-13T12:12:12.123456Z"
      }\
      """;

  private static final String MOCK_SESSION_STRING_3 =
      """
      {
        "name" : "projects/test-project/locations/test-location/reasoningEngines/123/sessions/3",
        "updateTime" : "2024-12-14T12:12:12.123456Z",
        "userId" : "user2"
      }\
      """;

  private static final String MOCK_EVENT_STRING =
      """
      [
        {
          "name" : "projects/test-project/locations/test-location/reasoningEngines/123/sessions/1/events/123",
          "invocationId" : "123",
          "author" : "user",
          "timestamp" : "2024-12-12T12:12:12.123456Z",
          "content" : {
            "parts" : [
              { "text" : "testContent" }
            ]
          },
          "actions" : {
            "stateDelta" : {
              "key" : {
                "value" : "testValue"
              }
            },
            "transferAgent" : "agent"
          },
          "eventMetadata" : {
            "partial" : false,
            "turnComplete" : true,
            "interrupted" : false,
            "branch" : "",
            "longRunningToolIds" : [ "tool1" ]
          }
        }
      ]
      """;

  private static final Session MOCK_SESSION;

  static {
    try {
      MOCK_SESSION = getMockSession();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Session getMockSession() throws Exception {
    Map<String, Object> sessionJson =
        mapper.readValue(MOCK_SESSION_STRING_1, new TypeReference<Map<String, Object>>() {});
    Map<String, Object> eventJson =
        mapper
            .readValue(MOCK_EVENT_STRING, new TypeReference<List<Map<String, Object>>>() {})
            .get(0);
    return Session.builder("1")
        .appName("123")
        .userId("user")
        .state((Map<String, Object>) sessionJson.get("sessionState"))
        .lastUpdateTime(Instant.parse((String) sessionJson.get("updateTime")))
        .events(
            Arrays.asList(
                Event.builder()
                    .id("123")
                    .invocationId("123")
                    .author("user")
                    .timestamp(Instant.parse((String) eventJson.get("timestamp")).toEpochMilli())
                    .content(
                        Content.builder()
                            .parts(Arrays.asList(Part.fromText("testContent")))
                            .build())
                    .actions(
                        EventActions.builder()
                            .transferToAgent("agent")
                            .stateDelta((Map<String, Object>) sessionJson.get("sessionState"))
                            .build())
                    .partial(false)
                    .turnComplete(true)
                    .interrupted(false)
                    .branch("")
                    .longRunningToolIds(ImmutableSet.of("tool1"))
                    .build()))
        .build();
  }

  /** Mock for HttpApiClient to mock the http calls to Vertex AI API. */
  @Mock private HttpApiClient mockApiClient;

  // private final ObjectMapper objectMapper = new ObjectMapper();
  @Mock private ApiResponse mockGetApiResponse;
  @Mock private HttpEntity mockHttpEntity;
  private VertexAiSessionService vertexAiSessionService;
  public Map<String, String> sessionMap =
      new HashMap<String, String>(
          ImmutableMap.of(
              "1", MOCK_SESSION_STRING_1,
              "2", MOCK_SESSION_STRING_2,
              "3", MOCK_SESSION_STRING_3));
  public Map<String, String> eventMap =
      new HashMap<String, String>(ImmutableMap.of("1", MOCK_EVENT_STRING));

  private static final Pattern LRO_REGEX = Pattern.compile("^operations/([^/]+)$");
  private static final Pattern SESSION_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions/([^/]+)$");
  private static final Pattern SESSIONS_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions\\?filter=user_id=([^/]+)$");
  private static final Pattern EVENTS_REGEX =
      Pattern.compile("^reasoningEngines/([^/]+)/sessions/([^/]+)/events$");

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    vertexAiSessionService =
        new VertexAiSessionService("test-project", "test-location", mockApiClient);
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenAnswer(
            new Answer<ApiResponse>() {
              @Override
              public ApiResponse answer(InvocationOnMock invocation) throws Throwable {
                ObjectMapper mapper = new ObjectMapper();
                String httpMethod = invocation.getArgument(0);
                String path = invocation.getArgument(1);
                if (httpMethod.equals("POST")) {
                  String newSessionId = "4";
                  Map<String, Object> requestDict =
                      mapper.readValue(
                          (String) invocation.getArgument(2),
                          new TypeReference<Map<String, Object>>() {});
                  Map<String, Object> newSessionData = new HashMap<>();
                  newSessionData.put(
                      "name",
                      String.format(
                          "projects/test-project/locations/test-location/reasoningEngines/123/sessions/%s",
                          newSessionId));
                  newSessionData.put("userId", requestDict.get("userId"));
                  newSessionData.put(
                      "sessionState", requestDict.getOrDefault("sessionState", new HashMap<>()));
                  newSessionData.put("updateTime", "2024-12-12T12:12:12.123456Z");

                  sessionMap.put(newSessionId, mapper.writeValueAsString(newSessionData));

                  String operationResponse =
                      String.format(
                          """
                          {
                            "name": "projects/test_project/locations/test_location/reasoningEngines/123/sessions/%s/operations/111",
                            "done": false
                          }
                          """,
                          newSessionId);

                  when(mockGetApiResponse.getEntity()).thenReturn(mockHttpEntity);
                  when(mockHttpEntity.getContent())
                      .thenReturn(
                          new ByteArrayInputStream(
                              operationResponse.getBytes(StandardCharsets.UTF_8)));
                  return mockGetApiResponse;
                } else if (httpMethod.equals("GET") && SESSION_REGEX.matcher(path).matches()) {
                  String sessionId = path.substring(path.lastIndexOf('/') + 1);
                  if (!sessionId.contains("/")) { // Ensure it's a direct session ID
                    String sessionData = sessionMap.get(sessionId);
                    if (sessionData != null) {
                      when(mockGetApiResponse.getEntity()).thenReturn(mockHttpEntity);
                      when(mockHttpEntity.getContent())
                          .thenReturn(
                              new ByteArrayInputStream(
                                  sessionData.getBytes(StandardCharsets.UTF_8)));
                      return mockGetApiResponse;
                    } else {
                      throw new RuntimeException("Session not found: " + sessionId);
                    }
                  }
                } else if (httpMethod.equals("GET") && SESSIONS_REGEX.matcher(path).matches()) {
                  Matcher sessionsMatcher = SESSIONS_REGEX.matcher(path);
                  if (sessionsMatcher.matches()) {
                    String userId = sessionsMatcher.group(2);
                    List<String> userSessionsJson = new ArrayList<>();
                    ObjectMapper sm = new ObjectMapper();
                    for (String sessionJson : sessionMap.values()) {
                      Map<String, Object> session =
                          sm.readValue(sessionJson, new TypeReference<Map<String, Object>>() {});
                      if (session.containsKey("userId") && session.get("userId").equals(userId)) {
                        userSessionsJson.add(sessionJson);
                      }
                    }
                    String sessionsResponse =
                        String.format(
                            """
                            {
                              "sessions": [%s]
                            }
                            """,
                            String.join(",", userSessionsJson));
                    when(mockGetApiResponse.getEntity()).thenReturn(mockHttpEntity);
                    when(mockHttpEntity.getContent())
                        .thenReturn(
                            new ByteArrayInputStream(
                                sessionsResponse.getBytes(StandardCharsets.UTF_8)));
                    return mockGetApiResponse;
                  }
                } else if (httpMethod.equals("GET") && EVENTS_REGEX.matcher(path).matches()) {
                  Matcher matcher = EVENTS_REGEX.matcher(path);
                  if (matcher.matches()) {
                    String sessionId = matcher.group(2);
                    String eventData = eventMap.get(sessionId);
                    if (eventData != null) {
                      String eventsResponse =
                          String.format(
                              """
                              {
                                "sessionEvents": %s
                              }
                              """,
                              eventData);
                      when(mockGetApiResponse.getEntity()).thenReturn(mockHttpEntity);
                      when(mockHttpEntity.getContent())
                          .thenReturn(
                              new ByteArrayInputStream(
                                  eventsResponse.getBytes(StandardCharsets.UTF_8)));
                      return mockGetApiResponse;
                    } else {
                      // Return an empty list if no events are found for the session
                      String emptyEventsResponse =
                          """
                          {
                            "sessionEvents": []
                          }
                          """;
                      when(mockGetApiResponse.getEntity()).thenReturn(mockHttpEntity);
                      when(mockHttpEntity.getContent())
                          .thenReturn(
                              new ByteArrayInputStream(
                                  emptyEventsResponse.getBytes(StandardCharsets.UTF_8)));
                      return mockGetApiResponse;
                    }
                  }
                } else if (httpMethod.equals("GET") && LRO_REGEX.matcher(path).matches()) {
                  String lroResponse =
                      String.format(
                          """
                          {
                            "name": "%s",
                            "done": true
                          }
                          """,
                          path.replace("/operations/111", "")); // Simulate LRO done
                  when(mockGetApiResponse.getEntity()).thenReturn(mockHttpEntity);
                  when(mockHttpEntity.getContent())
                      .thenReturn(
                          new ByteArrayInputStream(lroResponse.getBytes(StandardCharsets.UTF_8)));
                  return mockGetApiResponse;
                } else if (httpMethod.equals("DELETE")) {
                  Matcher sessionMatcher = SESSION_REGEX.matcher(path);
                  if (sessionMatcher.matches()) {
                    String sessionIdToDelete = sessionMatcher.group(2);
                    sessionMap.remove(sessionIdToDelete);
                    when(mockGetApiResponse.getEntity()).thenReturn(mockHttpEntity);
                    when(mockHttpEntity.getContent())
                        .thenReturn(new ByteArrayInputStream(new byte[0])); // Empty response
                    return mockGetApiResponse;
                  }
                }
                return null; // Handle other cases or return null for unmocked calls
              }
            });
  }

  @Test
  public void createSession_success() throws Exception {
    ImmutableMap<String, Object> sessionStateMap = ImmutableMap.of("new_key", "new_value");
    Single<Session> sessionSingle =
        vertexAiSessionService.createSession("123", "test_user", sessionStateMap, null);
    Session createdSession = sessionSingle.blockingGet();

    // Assert that the session was created and its properties are correct
    assertThat(createdSession.userId()).isEqualTo("test_user");
    assertThat(createdSession.appName()).isEqualTo("123");
    assertThat(createdSession.state()).isEqualTo(sessionStateMap); // Check the generated IDss
    assertThat(createdSession.id()).isEqualTo("4"); // Check the generated ID

    // Verify that the session is now in the sessionMap
    assertThat(sessionMap.containsKey("4")).isTrue();
    String newSessionJson = sessionMap.get("4");
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> newSessionMap =
        mapper.readValue((String) newSessionJson, new TypeReference<Map<String, Object>>() {});
    assertThat(newSessionMap.get("userId")).isEqualTo("test_user");
    assertThat(newSessionMap.get("sessionState")).isEqualTo(sessionStateMap);
  }

  @Test
  public void getEmptySession_success() throws RuntimeException {
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              vertexAiSessionService.getSession("123", "user", "0", Optional.empty()).blockingGet();
            });
    assertThat(exception.getMessage()).contains("Session not found: 0");
  }

  @Test
  public void getAndDeleteSession_success() throws Exception {
    Session session =
        vertexAiSessionService.getSession("123", "user", "1", Optional.empty()).blockingGet();
    assertThat(session.toJson()).isEqualTo(MOCK_SESSION.toJson());
    Completable unused = vertexAiSessionService.deleteSession("123", "user", "1");
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () -> {
              vertexAiSessionService.getSession("123", "user", "1", Optional.empty()).blockingGet();
            });
    assertThat(exception.getMessage()).contains("Session not found: 1");
  }

  @Test
  public void createSessionAndGetSession_success() throws Exception {
    ImmutableMap<String, Object> sessionStateMap = ImmutableMap.of("key", "value");
    Single<Session> sessionSingle =
        vertexAiSessionService.createSession("123", "user", sessionStateMap, null);
    Session createdSession = sessionSingle.blockingGet();

    assertThat(createdSession.state()).isEqualTo(sessionStateMap);
    assertThat(createdSession.appName()).isEqualTo("123");
    assertThat(createdSession.userId()).isEqualTo("user");
    assertThat(createdSession.lastUpdateTime()).isNotNull();

    String sessionId = createdSession.id();
    Session retrievedSession =
        vertexAiSessionService.getSession("123", "user", sessionId, Optional.empty()).blockingGet();
    assertThat(retrievedSession.toJson()).isEqualTo(createdSession.toJson());
  }

  @Test
  public void listSessions_success() {
    Single<ListSessionsResponse> sessionsSingle =
        vertexAiSessionService.listSessions("123", "user");
    ListSessionsResponse sessions = sessionsSingle.blockingGet();
    ImmutableList<Session> sessionsList = sessions.sessions();
    assertThat(sessionsList).hasSize(2);
    ImmutableList<String> ids = sessionsList.stream().map(Session::id).collect(toImmutableList());
    assertThat(ids).containsExactly("1", "2");
  }

  @Test
  public void listEvents_success() {
    Single<ListEventsResponse> eventsSingle = vertexAiSessionService.listEvents("123", "user", "1");
    ListEventsResponse events = eventsSingle.blockingGet();
    assertThat(events.events()).hasSize(1);
    assertThat(events.events().get(0).id()).isEqualTo("123");
  }

  @Test
  public void appendEvent_success() {
    Session session =
        vertexAiSessionService.getSession("123", "user", "1", Optional.empty()).blockingGet();
    Event event =
        Event.builder()
            .id("456")
            .invocationId("456")
            .author("user")
            .timestamp(Instant.parse("2024-12-12T12:12:12.123456Z").toEpochMilli())
            .content(Content.builder().parts(Arrays.asList(Part.fromText("testContent"))).build())
            .build();
    var unused = vertexAiSessionService.appendEvent(session, event).blockingGet();
    ImmutableList<Event> events =
        vertexAiSessionService
            .listEvents(session.appName(), session.userId(), session.id())
            .blockingGet()
            .events();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).author()).isEqualTo("user");
  }
}
