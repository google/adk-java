package com.google.adk.a2a;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class A2ASendMessageExecutorTest {

  @Test
  public void constructor_simple_acceptsSessionService() {
    InMemorySessionService sessionService = new InMemorySessionService();

    A2ASendMessageExecutor executor = new A2ASendMessageExecutor(sessionService, "test-app");

    assertThat(executor).isNotNull();
  }

  @Test
  public void constructor_withAgent_acceptsAllServices() {
    BaseAgent agent = createSimpleAgent();
    InMemorySessionService sessionService = new InMemorySessionService();
    InMemoryArtifactService artifactService = new InMemoryArtifactService();
    InMemoryMemoryService memoryService = new InMemoryMemoryService();

    A2ASendMessageExecutor executor =
        new A2ASendMessageExecutor(
            agent,
            "test-app",
            Duration.ofSeconds(30),
            sessionService,
            artifactService,
            memoryService);

    assertThat(executor).isNotNull();
  }

  private BaseAgent createSimpleAgent() {
    return new BaseAgent("test", "test agent", ImmutableList.of(), null, null) {
      @Override
      protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
        return Flowable.empty();
      }

      @Override
      protected Flowable<Event> runLiveImpl(InvocationContext ctx) {
        return Flowable.empty();
      }
    };
  }
}
