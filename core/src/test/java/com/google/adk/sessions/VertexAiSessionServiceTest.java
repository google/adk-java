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
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link VertexAiSessionService}. */
@RunWith(JUnit4.class)
public class VertexAiSessionServiceTest {

  private static final ObjectMapper mapper = JsonBaseModel.getMapper();
  private static final MediaType JSON_MEDIA_TYPE =
      MediaType.parse("application/json; charset=utf-8");

  private static ApiResponse apiResponseJson(String json) {
    return new ApiResponse() {
      @Override
      public ResponseBody getResponseBody() {
        return ResponseBody.create(JSON_MEDIA_TYPE, json);
      }

      @Override
      public void close() {}
    };
  }

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
            "role" : "user",
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

  @SuppressWarnings("unchecked")
  private static Session getMockSession() throws Exception {
    Map<String, Object> sessionJson =
        mapper.readValue(MOCK_SESSION_STRING_1, new TypeReference<Map<String, Object>>() {});
    Map<String, Object> eventJson =
        mapper
            .readValue(MOCK_EVENT_STRING, new TypeReference<List<Map<String, Object>>>() {})
            .get(0);
    Map<String, Object> sessionState = (Map<String, Object>) sessionJson.get("sessionState");
    return Session.builder("1")
        .appName("123")
        .userId("user")
        .state(sessionState == null ? null : new ConcurrentHashMap<>(sessionState))
        .lastUpdateTime(Instant.parse((String) sessionJson.get("updateTime")))
        .events(
            Arrays.asList(
                Event.builder()
                    .id("123")
                    .invocationId("123")
                    .author("user")
                    .timestamp(Instant.parse((String) eventJson.get("timestamp")).toEpochMilli())
                    .content(Content.fromParts(Part.fromText("testContent")))
                    .actions(
                        EventActions.builder()
                            .transferToAgent("agent")
                            .stateDelta(
                                sessionState == null ? null : new ConcurrentHashMap<>(sessionState))
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

  private VertexAiSessionService vertexAiSessionService;
  private Map<String, String> sessionMap = null;
  private Map<String, String> eventMap = null;

  @Before
  public void setUp() throws Exception {
    sessionMap =
        new HashMap<>(
            ImmutableMap.of(
                "1", MOCK_SESSION_STRING_1,
                "2", MOCK_SESSION_STRING_2,
                "3", MOCK_SESSION_STRING_3));
    eventMap = new HashMap<>(ImmutableMap.of("1", MOCK_EVENT_STRING));

    MockitoAnnotations.openMocks(this);
    vertexAiSessionService =
        new VertexAiSessionService("test-project", "test-location", mockApiClient);
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenAnswer(new MockApiAnswer(sessionMap, eventMap));
  }

  @Test
  public void createSession_success() throws Exception {
    Map<String, Object> sessionStateMap = new HashMap<>(ImmutableMap.of("new_key", "new_value"));
    Single<Session> sessionSingle =
        vertexAiSessionService.createSession("123", "test_user", sessionStateMap, null);
    Session createdSession = sessionSingle.blockingGet();

    // Assert that the session was created and its properties are correct
    assertThat(createdSession.userId()).isEqualTo("test_user");
    assertThat(createdSession.appName()).isEqualTo("123");
    assertThat(createdSession.state()).isEqualTo(sessionStateMap); // Check the generated IDss
    assertThat(createdSession.id()).isEqualTo("4"); // Check the generated ID

    // Verify that the session is now in the sessionMap
    assertThat(sessionMap).containsKey("4");
    String newSessionJson = sessionMap.get("4");
    Map<String, Object> newSessionMap =
        mapper.readValue(newSessionJson, new TypeReference<Map<String, Object>>() {});
    assertThat(newSessionMap.get("userId")).isEqualTo("test_user");
    assertThat(newSessionMap.get("sessionState")).isEqualTo(sessionStateMap);
  }

  @Test
  public void createSession_getSession_success() throws Exception {
    Map<String, Object> sessionStateMap = new HashMap<>(ImmutableMap.of("new_key", "new_value"));
    Single<Session> sessionSingle =
        vertexAiSessionService.createSession("789", "test_user", sessionStateMap, null);
    Session createdSession = sessionSingle.blockingGet();
    Session session =
        vertexAiSessionService
            .getSession("456", "test_user", createdSession.id(), Optional.empty())
            .blockingGet();

    // Verify that the session is now in the sessionMap
    assertThat(sessionMap).containsKey("4");
    assertThat(session.userId()).isEqualTo("test_user");
    assertThat(session.events()).isEmpty();
  }

  @Test
  public void createSession_noState_success() throws Exception {
    Single<Session> sessionSingle = vertexAiSessionService.createSession("123", "test_user");
    Session createdSession = sessionSingle.blockingGet();

    // Assert that the session was created and its properties are correct
    assertThat(createdSession.state()).isEmpty();

    // Verify that the session is now in the sessionMap
    assertThat(sessionMap).containsKey("4");
    String newSessionJson = sessionMap.get("4");
    Map<String, Object> newSessionMap =
        mapper.readValue(newSessionJson, new TypeReference<Map<String, Object>>() {});
    assertThat(newSessionMap.get("sessionState")).isNull();
  }

  @Test
  public void getEmptySession_success() {
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () ->
                vertexAiSessionService
                    .getSession("123", "user", "0", Optional.empty())
                    .blockingGet());
    assertThat(exception).hasMessageThat().contains("Session not found: 0");
  }

  @Test
  public void getAndDeleteSession_success() throws Exception {
    Session session =
        vertexAiSessionService.getSession("123", "user", "1", Optional.empty()).blockingGet();
    assertThat(session.toJson()).isEqualTo(getMockSession().toJson());
    vertexAiSessionService.deleteSession("123", "user", "1").blockingAwait();
    RuntimeException exception =
        assertThrows(
            RuntimeException.class,
            () ->
                vertexAiSessionService
                    .getSession("123", "user", "1", Optional.empty())
                    .blockingGet());
    assertThat(exception).hasMessageThat().contains("Session not found: 1");
  }

  @Test
  public void createSessionAndGetSession_success() throws Exception {
    Map<String, Object> sessionStateMap = new HashMap<>(ImmutableMap.of("key", "value"));
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
    String userId = "userA";
    Session session = vertexAiSessionService.createSession("987", userId, null, null).blockingGet();
    Event event =
        Event.builder()
            .invocationId("456")
            .author(userId)
            .timestamp(Instant.parse("2024-12-12T12:12:12.123456Z").toEpochMilli())
            .content(Content.fromParts(Part.fromText("appendEvent_success")))
            .build();
    var unused = vertexAiSessionService.appendEvent(session, event).blockingGet();
    ImmutableList<Event> events =
        vertexAiSessionService
            .listEvents(session.appName(), session.userId(), session.id())
            .blockingGet()
            .events();
    assertThat(events).hasSize(1);

    Event retrievedEvent = events.get(0);
    assertThat(retrievedEvent.author()).isEqualTo(userId);
    assertThat(retrievedEvent.content().get().text()).isEqualTo("appendEvent_success");
    assertThat(retrievedEvent.content().get().role()).hasValue("user");
    assertThat(retrievedEvent.invocationId()).isEqualTo("456");
    assertThat(retrievedEvent.timestamp())
        .isEqualTo(Instant.parse("2024-12-12T12:12:12.123456Z").toEpochMilli());
  }

  @Test
  public void listSessions_empty() {
    assertThat(vertexAiSessionService.listSessions("789", "user1").blockingGet().sessions())
        .isEmpty();
  }

  @Test
  public void listSessions_missingSessionsField_returnsEmpty() {
    when(mockApiClient.request("GET", "reasoningEngines/123/sessions?filter=user_id=userX", ""))
        .thenReturn(apiResponseJson("{}"));

    assertThat(vertexAiSessionService.listSessions("123", "userX").blockingGet().sessions())
        .isEmpty();
  }

  @Test
  public void listSessions_nullSessionsField_returnsEmpty() {
    when(mockApiClient.request("GET", "reasoningEngines/123/sessions?filter=user_id=userY", ""))
        .thenReturn(apiResponseJson("{\"sessions\": null}"));

    assertThat(vertexAiSessionService.listSessions("123", "userY").blockingGet().sessions())
        .isEmpty();
  }

  @Test
  public void listEvents_empty() {
    assertThat(vertexAiSessionService.listEvents("789", "user1", "3").blockingGet().events())
        .isEmpty();
  }

  @Test
  public void listEmptySession_success() {
    assertThat(
            vertexAiSessionService
                .getSession("789", "user1", "3", Optional.empty())
                .blockingGet()
                .events())
        .isEmpty();
  }

  @Test
  public void appendEvent_withStateRemoved_updatesSessionState() {
    String userId = "userB";
    Map<String, Object> initialState =
        new HashMap<>(ImmutableMap.of("key1", "value1", "key2", "value2"));
    Session session =
        vertexAiSessionService.createSession("987", userId, initialState, null).blockingGet();

    ConcurrentMap<String, Object> stateDelta =
        new ConcurrentHashMap<>(ImmutableMap.of("key2", State.REMOVED));
    Event event =
        Event.builder()
            .invocationId("456")
            .author(userId)
            .timestamp(Instant.parse("2024-12-12T12:12:12.123456Z").toEpochMilli())
            .actions(EventActions.builder().stateDelta(stateDelta).build())
            .build();
    var unused = vertexAiSessionService.appendEvent(session, event).blockingGet();

    Session updatedSession =
        vertexAiSessionService
            .getSession(session.appName(), session.userId(), session.id(), Optional.empty())
            .blockingGet();

    assertThat(updatedSession.state()).containsExactly("key1", "value1");
    assertThat(updatedSession.state()).doesNotContainKey("key2");
  }
}
