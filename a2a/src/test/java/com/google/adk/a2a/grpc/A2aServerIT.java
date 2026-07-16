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
 * Integration tests for A2aServer (basic server functionality).
 *
 * <p>These tests verify end-to-end functionality without registry.
 */
class A2aServerIT {

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

    // Start server without registry
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
  void testA2aServer_basicFunctionality() {
    SendMessageRequest request =
        SendMessageRequest.newBuilder()
            .setSessionId(UUID.randomUUID().toString())
            .setUserQuery("hello")
            .build();
    SendMessageResponse response = client.sendMessage(request);

    // Verify the response from the server
    assertNotNull(response);
    assertThat(response.getAgentReply()).isNotEmpty();
  }

  private int findAvailablePort() throws IOException {
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
