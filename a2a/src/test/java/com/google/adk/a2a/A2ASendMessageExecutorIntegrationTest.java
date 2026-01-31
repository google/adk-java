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
import io.a2a.spec.Message;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class A2ASendMessageExecutorIntegrationTest {

  @Test
  public void execute_withInMemoryServices_succeeds() {
    InMemorySessionService sessionService = new InMemorySessionService();
    BaseAgent agent = createSimpleAgent();

    A2ASendMessageExecutor executor =
        new A2ASendMessageExecutor(
            agent,
            "test-app",
            Duration.ofSeconds(30),
            sessionService,
            new InMemoryArtifactService(),
            new InMemoryMemoryService());

    Message request =
        new Message.Builder()
            .messageId("msg-1")
            .contextId("ctx-1")
            .role(Message.Role.USER)
            .parts(List.of(new TextPart("Hello")))
            .build();

    Message response = executor.execute(request).blockingGet();

    assertThat(response).isNotNull();
    assertThat(response.getContextId()).isEqualTo("ctx-1");
  }

  @Test
  public void execute_createsSessionAndProcessesRequest() {
    InMemorySessionService sessionService = new InMemorySessionService();
    BaseAgent agent = createSimpleAgent();

    A2ASendMessageExecutor executor =
        new A2ASendMessageExecutor(
            agent,
            "test-app",
            Duration.ofSeconds(30),
            sessionService,
            new InMemoryArtifactService(),
            new InMemoryMemoryService());

    Message request =
        new Message.Builder()
            .messageId("msg-1")
            .contextId("ctx-1")
            .role(Message.Role.USER)
            .parts(List.of(new TextPart("Test message")))
            .build();

    Message response = executor.execute(request).blockingGet();

    assertThat(response).isNotNull();
    assertThat(response.getParts()).isNotEmpty();
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
