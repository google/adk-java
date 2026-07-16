/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import com.google.adk.agents.BaseAgent;
import io.a2a.spec.Artifact;
import io.a2a.spec.Event;
import io.a2a.spec.Message;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.grpc.stub.StreamObserver;
import io.reactivex.rxjava3.core.Flowable;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced A2A gRPC service implementation with full task lifecycle support.
 *
 * <p>This service provides Python-like functionality:
 *
 * <ul>
 *   <li>Full A2A task lifecycle (submitted → working → completed/failed)
 *   <li>Task status updates
 *   <li>Task artifact updates
 *   <li>Proper event conversion
 * </ul>
 */
class A2aServiceEnhanced extends A2AServiceGrpc.A2AServiceImplBase {

  private static final Logger logger = LoggerFactory.getLogger(A2aServiceEnhanced.class);
  private final A2aAgentExecutor executor;

  /**
   * Constructs the service with a given ADK agent.
   *
   * @param agent The {@link BaseAgent} to handle the requests.
   */
  A2aServiceEnhanced(BaseAgent agent) {
    this.executor = new A2aAgentExecutor(agent, "adk-a2a-server");
  }

  /**
   * Constructs the service with a pre-configured executor.
   *
   * @param executor The {@link A2aAgentExecutor} instance.
   */
  A2aServiceEnhanced(A2aAgentExecutor executor) {
    this.executor = executor;
  }

  @Override
  public void sendMessage(
      SendMessageRequest request, StreamObserver<SendMessageResponse> responseObserver) {
    logger.info(
        "Received message from client: sessionId={}, query={}",
        request.getSessionId(),
        request.getUserQuery());

    try {
      // Generate task and context IDs
      String taskId = UUID.randomUUID().toString();
      String contextId =
          request.getSessionId() != null && !request.getSessionId().isEmpty()
              ? request.getSessionId()
              : UUID.randomUUID().toString();

      // Convert request to A2A Message
      Message a2aMessage =
          new Message.Builder()
              .messageId(UUID.randomUUID().toString())
              .role(Message.Role.USER)
              .parts(
                  com.google.common.collect.ImmutableList.of(new TextPart(request.getUserQuery())))
              .build();

      // Execute using A2aAgentExecutor (handles full task lifecycle)
      Flowable<Event> a2aEvents = executor.execute(a2aMessage, taskId, contextId);

      // Stream events back to client
      a2aEvents
          .doOnError(
              error -> {
                logger.error("Error executing agent", error);
                responseObserver.onError(
                    io.grpc.Status.INTERNAL
                        .withDescription("Agent execution failed: " + error.getMessage())
                        .withCause(error)
                        .asRuntimeException());
              })
          .subscribe(
              event -> {
                // Convert A2A event to gRPC response
                String responseText = convertEventToResponseText(event);
                if (responseText != null && !responseText.trim().isEmpty()) {
                  SendMessageResponse response =
                      SendMessageResponse.newBuilder().setAgentReply(responseText).build();
                  responseObserver.onNext(response);
                }
              },
              error -> {
                logger.error("Error in event stream", error);
              },
              () -> {
                logger.info("Agent execution completed for taskId={}", taskId);
                responseObserver.onCompleted();
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

  /**
   * Converts an A2A event to a response text string.
   *
   * @param event The A2A event to convert.
   * @return The response text, or null if the event should be skipped.
   */
  private String convertEventToResponseText(Event event) {
    if (event instanceof TaskStatusUpdateEvent statusEvent) {
      TaskStatus status = statusEvent.getStatus();
      if (status.state() == io.a2a.spec.TaskState.SUBMITTED) {
        return "[Task Submitted]";
      } else if (status.state() == io.a2a.spec.TaskState.WORKING) {
        return "[Task Working...]";
      } else if (status.state() == io.a2a.spec.TaskState.COMPLETED) {
        return "[Task Completed]";
      } else if (status.state() == io.a2a.spec.TaskState.FAILED) {
        Message errorMsg = status.message();
        if (errorMsg != null && !errorMsg.getParts().isEmpty()) {
          io.a2a.spec.Part<?> part = errorMsg.getParts().get(0);
          if (part instanceof TextPart) {
            return "[Error] " + ((TextPart) part).getText();
          }
        }
        return "[Task Failed]";
      }
    } else if (event instanceof TaskArtifactUpdateEvent artifactEvent) {
      // Extract text from artifact parts
      StringBuilder text = new StringBuilder();
      Artifact artifact = artifactEvent.getArtifact();
      if (artifact != null && artifact.parts() != null) {
        for (io.a2a.spec.Part<?> part : artifact.parts()) {
          if (part instanceof TextPart) {
            text.append(((TextPart) part).getText());
          }
        }
      }
      return text.length() > 0 ? text.toString() : null;
    }
    return null;
  }
}
