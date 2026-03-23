package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for URL encoding in {@link VertexAiClient}.
 *
 * <p>Verifies that userId and sessionId values are properly URL-encoded before being concatenated
 * into API request paths, preventing query parameter injection and path traversal attacks.
 */
@RunWith(JUnit4.class)
public class VertexAiClientTest {

  private static final MediaType JSON_MEDIA_TYPE =
      MediaType.parse("application/json; charset=utf-8");

  @Mock private HttpApiClient mockApiClient;

  private VertexAiClient client;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    client = new VertexAiClient("test-project", "test-location", mockApiClient);
  }

  /** Returns a mock ApiResponse with the given JSON body. */
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

  // ---------------------------------------------------------------------------
  // listSessions: userId encoding
  // ---------------------------------------------------------------------------

  @Test
  public void listSessions_encodesUserIdWithQueryInjection() {
    String maliciousUserId = "user&extra=value";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(responseWithBody("{\"sessions\": []}"));

    client.listSessions("123", maliciousUserId).blockingGet();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    // The ampersand must be encoded as %26, not left raw
    assertThat(path).contains("user%26extra%3Dvalue");
    assertThat(path).doesNotContain("&extra=value");
  }

  @Test
  public void listSessions_encodesUserIdWithSpaces() {
    String userIdWithSpaces = "user name with spaces";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(responseWithBody("{\"sessions\": []}"));

    client.listSessions("123", userIdWithSpaces).blockingGet();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    // Spaces must be encoded (as + or %20)
    assertThat(path).doesNotContain(" name ");
    assertThat(path).contains("filter=user_id=user");
  }

  @Test
  public void listSessions_normalUserIdPassesThroughCorrectly() {
    String normalUserId = "user123";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(responseWithBody("{\"sessions\": []}"));

    client.listSessions("456", normalUserId).blockingGet();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    assertThat(path).isEqualTo("reasoningEngines/456/sessions?filter=user_id=user123");
  }

  // ---------------------------------------------------------------------------
  // getSession: sessionId encoding
  // ---------------------------------------------------------------------------

  @Test
  public void getSession_encodesSessionIdWithPathTraversal() {
    String maliciousSessionId = "../../secret";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(
            responseWithBody(
                "{\"name\": \"sessions/safe\", \"updateTime\": \"2024-12-12T12:12:12.123456Z\"}"));

    client.getSession("123", maliciousSessionId).blockingGet();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    // Path traversal characters must be encoded
    assertThat(path).doesNotContain("../../");
    assertThat(path).contains("..%2F..%2Fsecret");
  }

  @Test
  public void getSession_encodesSessionIdWithSlashes() {
    String sessionIdWithSlashes = "session/with/slashes";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(
            responseWithBody(
                "{\"name\": \"sessions/safe\", \"updateTime\": \"2024-12-12T12:12:12.123456Z\"}"));

    client.getSession("123", sessionIdWithSlashes).blockingGet();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    // Slashes in sessionId must be encoded as %2F
    assertThat(path).contains("session%2Fwith%2Fslashes");
  }

  @Test
  public void getSession_normalSessionIdPassesThroughCorrectly() {
    String normalSessionId = "abc123";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(
            responseWithBody(
                "{\"name\": \"sessions/abc123\", \"updateTime\": \"2024-12-12T12:12:12.123456Z\"}"));

    client.getSession("456", normalSessionId).blockingGet();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    assertThat(path).isEqualTo("reasoningEngines/456/sessions/abc123");
  }

  // ---------------------------------------------------------------------------
  // deleteSession: sessionId encoding
  // ---------------------------------------------------------------------------

  @Test
  public void deleteSession_encodesSessionIdWithSpecialCharacters() {
    String maliciousSessionId = "session&admin=true";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(responseWithBody(""));

    client.deleteSession("123", maliciousSessionId).blockingAwait();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    assertThat(path).doesNotContain("&admin=true");
    assertThat(path).contains("session%26admin%3Dtrue");
  }

  // ---------------------------------------------------------------------------
  // listEvents: sessionId encoding
  // ---------------------------------------------------------------------------

  @Test
  public void listEvents_encodesSessionIdWithPathTraversal() {
    String maliciousSessionId = "../other-engine/sessions/target/events";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(responseWithBody("{\"sessionEvents\": []}"));

    client.listEvents("123", maliciousSessionId).blockingGet();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    // The slashes and dots must be encoded, not treated as path separators
    assertThat(path).doesNotContain("../other-engine");
    assertThat(path).startsWith("reasoningEngines/123/sessions/");
    assertThat(path).endsWith("/events");
  }

  // ---------------------------------------------------------------------------
  // appendEvent: sessionId encoding
  // ---------------------------------------------------------------------------

  @Test
  public void appendEvent_encodesSessionIdWithSpecialCharacters() {
    String maliciousSessionId = "sess%00ion";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(responseWithBody("{}"));

    client.appendEvent("123", maliciousSessionId, "{}").blockingAwait();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    // The % must itself be encoded as %25
    assertThat(path).contains("sess%2500ion");
    assertThat(path).endsWith(":appendEvent");
  }

  @Test
  public void appendEvent_normalSessionIdPassesThroughCorrectly() {
    String normalSessionId = "session42";
    when(mockApiClient.request(anyString(), anyString(), anyString()))
        .thenReturn(responseWithBody("{}"));

    client.appendEvent("789", normalSessionId, "{}").blockingAwait();

    ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockApiClient).request(anyString(), pathCaptor.capture(), anyString());

    String path = pathCaptor.getValue();
    assertThat(path).isEqualTo("reasoningEngines/789/sessions/session42:appendEvent");
  }
}
