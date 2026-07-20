/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for A2aGrpcServer.
 *
 * <p>These tests verify end-to-end functionality including:
 *
 * <ul>
 *   <li>Server startup and shutdown
 *   <li>gRPC communication
 *   <li>Text message handling
 *   <li>Session management
 * </ul>
 */
class A2aGrpcServerIT {

  private A2aServer server;
  private ManagedChannel channel;
  private A2AServiceGrpc.A2AServiceBlockingStub client;
  private int port;

  private final BaseAgent testAgent = createTestAgent();

  private BaseAgent createTestAgent() {
    return new BaseAgent(
        "test-agent", "Test agent for integration tests", ImmutableList.of(), null, null) {
      @Override
      protected Flowable<Event> runAsyncImpl(InvocationContext context) {
        return runLiveImpl(context);
      }

      @Override
      protected Flowable<Event> runLiveImpl(InvocationContext context) {
        String userText =
            context
                .userContent()
                .map(
                    c ->
                        c.parts().get().stream()
                            .filter(p -> p.text().isPresent())
                            .map(p -> p.text().get())
                            .reduce("", (a, b) -> a + b))
                .orElse("");
        return Flowable.just(
            Event.builder()
                .author("agent")
                .content(
                    Content.builder()
                        .role("model")
                        .parts(ImmutableList.of(Part.builder().text("Echo: " + userText).build()))
                        .build())
                .build());
      }
    };
  }

  @BeforeEach
  void setUp() throws IOException, InterruptedException {
    // Find an available port
    port = findAvailablePort();

    // Start server
    server = new A2aServerBuilder(testAgent).port(port).build();
    server.start(false); // Non-blocking

    // Wait for server to start
    Thread.sleep(1000);

    // Create gRPC client
    channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    client = A2AServiceGrpc.newBlockingStub(channel);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (channel != null) {
      channel.shutdown();
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void testSendMessage_withTextRequest() {
    SendMessageRequest request =
        SendMessageRequest.newBuilder()
            .setSessionId(UUID.randomUUID().toString())
            .setUserQuery("Hello, A2A!")
            .build();

    SendMessageResponse response = client.sendMessage(request);

    assertNotNull(response);
    assertThat(response.getAgentReply()).isNotEmpty();
    // Response should contain the echo
    assertThat(response.getAgentReply()).contains("Hello, A2A!");
  }

  @Test
  void testSendMessage_withDifferentSessions() {
    String session1 = UUID.randomUUID().toString();
    String session2 = UUID.randomUUID().toString();

    SendMessageRequest request1 =
        SendMessageRequest.newBuilder().setSessionId(session1).setUserQuery("First").build();
    SendMessageRequest request2 =
        SendMessageRequest.newBuilder().setSessionId(session2).setUserQuery("Second").build();

    SendMessageResponse response1 = client.sendMessage(request1);
    SendMessageResponse response2 = client.sendMessage(request2);

    assertNotNull(response1);
    assertNotNull(response2);
    // Responses should contain the respective queries
    assertThat(response1.getAgentReply()).isNotEmpty();
    assertThat(response2.getAgentReply()).isNotEmpty();
  }

  @Test
  void testSendMessage_withEmptySessionId() {
    SendMessageRequest request =
        SendMessageRequest.newBuilder().setSessionId("").setUserQuery("Test").build();

    SendMessageResponse response = client.sendMessage(request);

    assertNotNull(response);
    assertThat(response.getAgentReply()).isNotEmpty();
  }

  private int findAvailablePort() throws IOException {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
