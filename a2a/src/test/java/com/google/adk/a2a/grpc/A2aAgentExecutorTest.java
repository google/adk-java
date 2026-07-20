/**
 * @author Sandeep Belgavi
 */
package com.google.adk.a2a.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.spec.Message;
import io.a2a.spec.TaskStatusUpdateEvent;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class A2aAgentExecutorTest {

  @Mock private BaseAgent mockAgent;
  @Mock private Runner mockRunner;
  @Mock private Session mockSession;

  private A2aAgentExecutor executor;

  @BeforeEach
  void setUp() {
    when(mockRunner.appName()).thenReturn("test-app");
    com.google.adk.sessions.InMemorySessionService sessionService =
        new com.google.adk.sessions.InMemorySessionService();
    when(mockRunner.sessionService()).thenReturn(sessionService);

    when(mockSession.userId()).thenReturn("test-user");
    when(mockSession.id()).thenReturn("test-session");

    when(mockRunner.runAsync(anyString(), anyString(), any(Content.class), any(RunConfig.class)))
        .thenReturn(
            Flowable.just(
                Event.builder()
                    .id(UUID.randomUUID().toString())
                    .author("agent")
                    .content(
                        Content.builder()
                            .role("model")
                            .parts(ImmutableList.of(Part.builder().text("Hello, world!").build()))
                            .build())
                    .build()));
  }

  @Test
  void testConstructor_withAgent() {
    executor = new A2aAgentExecutor(mockAgent, "test-app");
    assertNotNull(executor);
  }

  @Test
  void testConstructor_withRunner() {
    executor = new A2aAgentExecutor(mockRunner);
    assertNotNull(executor);
  }

  @Test
  void testExecute_withTextMessage() {
    executor = new A2aAgentExecutor(mockRunner);

    Message request =
        new Message.Builder()
            .messageId(UUID.randomUUID().toString())
            .role(Message.Role.USER)
            .parts(ImmutableList.of(new TextPart("Hello")))
            .build();

    String taskId = UUID.randomUUID().toString();
    String contextId = UUID.randomUUID().toString();

    Flowable<io.a2a.spec.Event> events = executor.execute(request, taskId, contextId);
    assertNotNull(events);

    List<io.a2a.spec.Event> eventList = events.toList().blockingGet();
    assertThat(eventList).isNotEmpty();

    boolean hasStatusUpdate = false;
    for (io.a2a.spec.Event event : eventList) {
      if (event instanceof TaskStatusUpdateEvent) {
        hasStatusUpdate = true;
        break;
      }
    }
    assertThat(hasStatusUpdate).isTrue();
  }

  @Test
  void testExecute_withNullRequest_throwsException() {
    executor = new A2aAgentExecutor(mockRunner);

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          executor.execute(null, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        });
  }

  @Test
  void testExecute_withEmptyMessage_throwsException() {
    executor = new A2aAgentExecutor(mockRunner);

    assertThrows(
        IllegalArgumentException.class,
        () -> {
          Message emptyRequest =
              new Message.Builder()
                  .messageId(UUID.randomUUID().toString())
                  .role(Message.Role.USER)
                  .parts(ImmutableList.of())
                  .build();
          executor.execute(
              emptyRequest, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        });
  }
}
