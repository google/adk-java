/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.common.collect.ImmutableList;
import io.a2a.spec.Artifact;
import io.a2a.spec.Message;
import io.a2a.spec.TaskArtifactUpdateEvent;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.grpc.stub.StreamObserver;
import io.reactivex.rxjava3.core.Flowable;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class A2aServiceEnhancedTest {

  @Mock private BaseAgent mockAgent;
  @Mock private A2aAgentExecutor mockExecutor;
  @Mock private StreamObserver<SendMessageResponse> mockResponseObserver;

  private A2aServiceEnhanced service;

  @BeforeEach
  void setUp() {
    service = new A2aServiceEnhanced(mockAgent);
  }

  @Test
  void testConstructor_withAgent() {
    A2aServiceEnhanced newService = new A2aServiceEnhanced(mockAgent);
    assertNotNull(newService);
  }

  @Test
  void testConstructor_withExecutor() {
    A2aServiceEnhanced newService = new A2aServiceEnhanced(mockExecutor);
    assertNotNull(newService);
  }

  @Test
  void testSendMessage_withTextRequest() {
    // Setup
    SendMessageRequest request =
        SendMessageRequest.newBuilder().setSessionId("test-session").setUserQuery("Hello").build();

    // Mock executor to return task lifecycle events
    Message a2aMessage =
        new Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart("Hello")))
            .build();

    TaskStatusUpdateEvent submittedEvent =
        new TaskStatusUpdateEvent(
            "task-1",
            new TaskStatus(TaskState.SUBMITTED, a2aMessage, null),
            "context-1",
            false,
            null);

    TaskStatusUpdateEvent workingEvent =
        new TaskStatusUpdateEvent(
            "task-1", new TaskStatus(TaskState.WORKING, null, null), "context-1", false, null);

    TaskArtifactUpdateEvent artifactEvent =
        new TaskArtifactUpdateEvent.Builder()
            .taskId("task-1")
            .contextId("context-1")
            .artifact(
                new Artifact.Builder()
                    .artifactId(UUID.randomUUID().toString())
                    .parts(ImmutableList.of(new TextPart("Hello, world!")))
                    .build())
            .lastChunk(false)
            .build();

    TaskStatusUpdateEvent completedEvent =
        new TaskStatusUpdateEvent(
            "task-1", new TaskStatus(TaskState.COMPLETED, null, null), "context-1", true, null);

    // Use executor-based service
    A2aServiceEnhanced serviceWithExecutor = new A2aServiceEnhanced(mockExecutor);
    when(mockExecutor.execute(any(Message.class), anyString(), anyString()))
        .thenReturn(Flowable.just(submittedEvent, workingEvent, artifactEvent, completedEvent));

    // Execute
    serviceWithExecutor.sendMessage(request, mockResponseObserver);

    // Verify
    verify(mockResponseObserver, atLeastOnce()).onNext(any(SendMessageResponse.class));
    verify(mockResponseObserver).onCompleted();
  }

  @Test
  void testSendMessage_withError() {
    SendMessageRequest request =
        SendMessageRequest.newBuilder().setSessionId("test-session").setUserQuery("Hello").build();

    A2aServiceEnhanced serviceWithExecutor = new A2aServiceEnhanced(mockExecutor);
    when(mockExecutor.execute(any(Message.class), anyString(), anyString()))
        .thenReturn(Flowable.error(new RuntimeException("Test error")));

    serviceWithExecutor.sendMessage(request, mockResponseObserver);

    verify(mockResponseObserver).onError(any(Throwable.class));
  }
}
