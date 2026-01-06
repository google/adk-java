package com.google.adk.a2a;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.a2a.spec.Message;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class A2ASendMessageExecutorAdvancedTest {

  private InMemorySessionService sessionService;

  @Test
  public void execute_withCustomStrategy_usesStrategy() {
    InMemorySessionService sessionService = new InMemorySessionService();

    A2ASendMessageExecutor executor = new A2ASendMessageExecutor(sessionService, "test-app");

    A2ASendMessageExecutor.AgentExecutionStrategy customStrategy =
        (userId, sessionId, userContent, runConfig, invocationId) -> {
          Event customEvent =
              Event.builder()
                  .id(UUID.randomUUID().toString())
                  .invocationId(invocationId)
                  .author("agent")
                  .content(
                      Content.builder()
                          .role("model")
                          .parts(
                              ImmutableList.of(
                                  Part.builder().text("Custom strategy response").build()))
                          .build())
                  .build();
          return Single.just(ImmutableList.of(customEvent));
        };

    Message request =
        new Message.Builder()
            .messageId("msg-1")
            .contextId("ctx-1")
            .role(Message.Role.USER)
            .parts(List.of(new TextPart("Test")))
            .build();

    Message response = executor.execute(request, customStrategy).blockingGet();

    assertThat(response).isNotNull();
    assertThat(response.getParts()).isNotEmpty();
    assertThat(((TextPart) response.getParts().get(0)).getText())
        .contains("Custom strategy response");
  }

  private A2ASendMessageExecutor createExecutorWithAgent() {
    BaseAgent agent = createSimpleAgent();
    sessionService = new InMemorySessionService();
    return new A2ASendMessageExecutor(
        agent,
        "test-app",
        Duration.ofSeconds(30),
        sessionService,
        new InMemoryArtifactService(),
        new InMemoryMemoryService());
  }

  @Test
  public void execute_withNullMessage_generatesDefaultContext() {
    A2ASendMessageExecutor executor = createExecutorWithAgent();

    Message response = executor.execute(null).blockingGet();

    assertThat(response).isNotNull();
    assertThat(response.getContextId()).isNotNull();
    assertThat(response.getContextId()).isNotEmpty();
  }

  @Test
  public void execute_withEmptyContextId_generatesNewContext() {
    A2ASendMessageExecutor executor = createExecutorWithAgent();

    Message request =
        new Message.Builder()
            .messageId("msg-1")
            .role(Message.Role.USER)
            .parts(List.of(new TextPart("Test")))
            .build();

    Message response = executor.execute(request).blockingGet();

    assertThat(response).isNotNull();
    assertThat(response.getContextId()).isNotNull();
    assertThat(response.getContextId()).isNotEmpty();
  }

  @Test
  public void execute_withProvidedContextId_preservesContext() {
    A2ASendMessageExecutor executor = createExecutorWithAgent();

    String contextId = "my-custom-context";
    Message request =
        new Message.Builder()
            .messageId("msg-1")
            .contextId(contextId)
            .role(Message.Role.USER)
            .parts(List.of(new TextPart("Test")))
            .build();

    Message response = executor.execute(request).blockingGet();

    assertThat(response).isNotNull();
    assertThat(response.getContextId()).isEqualTo(contextId);
  }

  @Test
  public void execute_multipleRequests_maintainsSession() {
    A2ASendMessageExecutor executor = createExecutorWithAgent();

    String contextId = "persistent-context";

    Message request1 =
        new Message.Builder()
            .messageId("msg-1")
            .contextId(contextId)
            .role(Message.Role.USER)
            .parts(List.of(new TextPart("First message")))
            .build();

    Message response1 = executor.execute(request1).blockingGet();
    assertThat(response1.getContextId()).isEqualTo(contextId);

    Message request2 =
        new Message.Builder()
            .messageId("msg-2")
            .contextId(contextId)
            .role(Message.Role.USER)
            .parts(List.of(new TextPart("Second message")))
            .build();

    Message response2 = executor.execute(request2).blockingGet();
    assertThat(response2.getContextId()).isEqualTo(contextId);
  }

  @Test
  public void execute_withoutRunnerConfig_throwsException() {
    InMemorySessionService sessionService = new InMemorySessionService();

    A2ASendMessageExecutor executor = new A2ASendMessageExecutor(sessionService, "test-app");

    Message request =
        new Message.Builder()
            .messageId("msg-1")
            .contextId("ctx-1")
            .role(Message.Role.USER)
            .parts(List.of(new TextPart("Test")))
            .build();

    try {
      executor.execute(request).blockingGet();
      assertThat(false).isTrue();
    } catch (IllegalStateException e) {
      assertThat(e.getMessage()).contains("Runner-based handle invoked without configured runner");
    }
  }

  @Test
  public void execute_errorInStrategy_returnsErrorResponse() {
    InMemorySessionService sessionService = new InMemorySessionService();

    A2ASendMessageExecutor executor = new A2ASendMessageExecutor(sessionService, "test-app");

    A2ASendMessageExecutor.AgentExecutionStrategy failingStrategy =
        (userId, sessionId, userContent, runConfig, invocationId) -> {
          return Single.error(new RuntimeException("Strategy failed"));
        };

    Message request =
        new Message.Builder()
            .messageId("msg-1")
            .contextId("ctx-1")
            .role(Message.Role.USER)
            .parts(List.of(new TextPart("Test")))
            .build();

    Message response = executor.execute(request, failingStrategy).blockingGet();

    assertThat(response).isNotNull();
    assertThat(response.getParts()).isNotEmpty();
    assertThat(((TextPart) response.getParts().get(0)).getText()).contains("Error:");
    assertThat(((TextPart) response.getParts().get(0)).getText()).contains("Strategy failed");
  }

  private BaseAgent createSimpleAgent() {
    return new BaseAgent("test", "test agent", ImmutableList.of(), null, null) {
      @Override
      protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
        return Flowable.just(
            Event.builder()
                .content(
                    Content.builder()
                        .role("model")
                        .parts(
                            ImmutableList.of(
                                com.google.genai.types.Part.builder().text("Response").build()))
                        .build())
                .build());
      }

      @Override
      protected Flowable<Event> runLiveImpl(InvocationContext ctx) {
        return Flowable.empty();
      }
    };
  }
}
