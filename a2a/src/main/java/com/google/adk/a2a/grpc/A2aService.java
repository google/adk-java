/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.SessionKey;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.grpc.stub.StreamObserver;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements the A2A gRPC service, bridging requests to an ADK agent.
 *
 * <p>This service uses a Runner internally to properly handle agent execution with session
 * management, artifacts, and memory services.
 */
class A2aService extends A2AServiceGrpc.A2AServiceImplBase {

  private static final Logger logger = LoggerFactory.getLogger(A2aService.class);
  private final BaseAgent agent;
  private final Runner runner;
  private static final String DEFAULT_APP_NAME = "adk-a2a-server";

  /**
   * Constructs the service with a given ADK agent.
   *
   * <p>This constructor creates an internal Runner with in-memory services for sessions, artifacts,
   * and memory. For production use, consider using a constructor that accepts a pre-configured
   * Runner.
   *
   * @param agent The {@link BaseAgent} to handle the requests.
   */
  A2aService(BaseAgent agent) {
    this.agent = agent;
    // Create a Runner with in-memory services for simplicity
    // In production, this could be configured with persistent services
    this.runner =
        new Runner.Builder()
            .agent(agent)
            .appName(DEFAULT_APP_NAME)
            .artifactService(new InMemoryArtifactService())
            .sessionService(new InMemorySessionService())
            .memoryService(new InMemoryMemoryService())
            .build();
  }

  /**
   * Constructs the service with a pre-configured Runner.
   *
   * @param agent The {@link BaseAgent} to handle the requests.
   * @param runner The {@link Runner} instance to use for agent execution.
   */
  A2aService(BaseAgent agent, Runner runner) {
    this.agent = agent;
    this.runner = runner;
  }

  @Override
  public void sendMessage(
      SendMessageRequest request, StreamObserver<SendMessageResponse> responseObserver) {
    String userQuery = request.getUserQuery();
    String sessionId =
        request.getSessionId() != null && !request.getSessionId().isEmpty()
            ? request.getSessionId()
            : "new-session";

    // Console output for validation
    System.out.println("\n" + "=".repeat(80));
    System.out.println("🔵 A2A REQUEST RECEIVED");
    System.out.println("=".repeat(80));
    System.out.println("Session ID: " + sessionId);
    System.out.println("Agent: " + agent.name());
    System.out.println("Query: " + userQuery);
    System.out.println("=".repeat(80) + "\n");

    logger.info("Received message from client: sessionId={}, query={}", sessionId, userQuery);

    try {
      // Extract session ID from request or generate a new one
      String userId = "default-user";
      final String actualSessionId =
          (sessionId == null || sessionId.equals("new-session"))
              ? UUID.randomUUID().toString()
              : sessionId;

      // Create user content from the request
      Content userContent = Content.fromParts(Part.fromText(request.getUserQuery()));

      // Get or create session - Runner requires session to exist
      Maybe<Session> maybeSession =
          runner
              .sessionService()
              .getSession(runner.appName(), userId, actualSessionId, Optional.empty());

      Session session =
          maybeSession
              .switchIfEmpty(
                  runner
                      .sessionService()
                      .createSession(runner.appName(), userId, null, null)
                      .toMaybe())
              .blockingGet();

      // Initialize session state with default values if missing
      // This prevents errors when agent instruction references state variables
      // The state map is mutable, so we can update it directly
      Map<String, Object> sessionState = session.state();
      if (sessionState.isEmpty() || !sessionState.containsKey("currentDate")) {
        sessionState.put("currentDate", LocalDate.now().toString());
        sessionState.put("sourceCityName", "");
        sessionState.put("destinationCityName", "");
        sessionState.put("dateOfJourney", "");
        sessionState.put("mriSessionId", actualSessionId);
        sessionState.put("userMsg", request.getUserQuery());
        // Track A2A handovers (using _temp prefix for non-persistent tracking)
        sessionState.put("_temp_a2aCallCount", 0);
        sessionState.put("_temp_a2aCalls", new java.util.ArrayList<Map<String, Object>>());
      } else {
        // Ensure required fields exist even if state is not empty
        if (!sessionState.containsKey("mriSessionId")) {
          sessionState.put("mriSessionId", actualSessionId);
        }
        if (!sessionState.containsKey("userMsg")) {
          sessionState.put("userMsg", request.getUserQuery());
        }
        if (!sessionState.containsKey("currentDate")) {
          sessionState.put("currentDate", LocalDate.now().toString());
        }
        // Ensure empty strings for city names if not present
        if (!sessionState.containsKey("sourceCityName")) {
          sessionState.put("sourceCityName", "");
        }
        if (!sessionState.containsKey("destinationCityName")) {
          sessionState.put("destinationCityName", "");
        }
        if (!sessionState.containsKey("dateOfJourney")) {
          sessionState.put("dateOfJourney", "");
        }
        // Initialize A2A tracking if not present
        if (!sessionState.containsKey("_temp_a2aCallCount")) {
          sessionState.put("_temp_a2aCallCount", 0);
        }
        if (!sessionState.containsKey("_temp_a2aCalls")) {
          sessionState.put("_temp_a2aCalls", new java.util.ArrayList<Map<String, Object>>());
        }
      }

      // Configure run settings for streaming
      RunConfig runConfig =
          RunConfig.builder()
              .setStreamingMode(RunConfig.StreamingMode.SSE)
              .setMaxLlmCalls(20)
              .build();

      SessionKey sessionKey = new SessionKey(session.appName(), session.userId(), session.id());
      Flowable<Event> eventStream = runner.runAsync(sessionKey, userContent, runConfig);

      // Collect all events and aggregate into a single response
      // Since sendMessage is unary RPC, we need to send a single response
      eventStream
          .doOnError(
              error -> {
                logger.error("Error executing agent", error);
                responseObserver.onError(
                    io.grpc.Status.INTERNAL
                        .withDescription("Agent execution failed: " + error.getMessage())
                        .withCause(error)
                        .asRuntimeException());
              })
          .toList()
          .subscribe(
              events -> {
                // Aggregate all events into a single response
                StringBuilder aggregatedResponse = new StringBuilder();
                for (Event event : events) {
                  String content = event.stringifyContent();
                  if (content != null && !content.trim().isEmpty()) {
                    if (aggregatedResponse.length() > 0) {
                      aggregatedResponse.append("\n");
                    }
                    aggregatedResponse.append(content);
                  }
                }

                String finalResponse = aggregatedResponse.toString();
                if (finalResponse.isEmpty()) {
                  finalResponse = "(No response generated)";
                }

                // Console output for validation
                System.out.println("\n" + "=".repeat(80));
                System.out.println("🟢 A2A RESPONSE SENT");
                System.out.println("=".repeat(80));
                System.out.println("Session ID: " + actualSessionId);
                System.out.println("Agent: " + agent.name());
                System.out.println("Response Length: " + finalResponse.length() + " characters");
                System.out.println("Response Preview (first 500 chars):");
                System.out.println(
                    finalResponse.substring(0, Math.min(500, finalResponse.length())));
                if (finalResponse.length() > 500) {
                  System.out.println("... (truncated)");
                }
                System.out.println("=".repeat(80) + "\n");

                logger.info(
                    "Agent execution completed for sessionId={}, response length={}",
                    actualSessionId,
                    finalResponse.length());

                SendMessageResponse response =
                    SendMessageResponse.newBuilder().setAgentReply(finalResponse).build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
              },
              error -> {
                logger.error("Error collecting events", error);
                responseObserver.onError(
                    io.grpc.Status.INTERNAL
                        .withDescription("Error collecting agent response: " + error.getMessage())
                        .withCause(error)
                        .asRuntimeException());
              });

    } catch (Exception e) {
      logger.error("Unexpected error processing request", e);
      responseObserver.onError(
          io.grpc.Status.INTERNAL
              .withDescription("Unexpected error: " + e.getMessage())
              .withCause(e)
              .asRuntimeException());
    }
  }
}
