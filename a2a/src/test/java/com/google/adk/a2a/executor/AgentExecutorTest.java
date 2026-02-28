package com.google.adk.a2a.executor;

import static org.junit.Assert.assertThrows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.sessions.InMemorySessionService;
import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AgentExecutorTest {

  private TestAgent testAgent;

  @Before
  public void setUp() {
    testAgent = new TestAgent();
  }

  @Test
  public void createAgentExecutor_noAgent_succeeds() {
    var unused =
        new AgentExecutor.Builder()
            .app(App.builder().name("test_app").rootAgent(testAgent).build())
            .sessionService(new InMemorySessionService())
            .artifactService(new InMemoryArtifactService())
            .agentExecutorConfig(AgentExecutorConfig.builder().build())
            .build();
  }

  @Test
  public void createAgentExecutor_withAgentAndApp_throwsException() {
    assertThrows(
        IllegalStateException.class,
        () -> {
          new AgentExecutor.Builder()
              .agent(testAgent)
              .app(App.builder().name("test_app").rootAgent(testAgent).build())
              .sessionService(new InMemorySessionService())
              .agentExecutorConfig(AgentExecutorConfig.builder().build())
              .artifactService(new InMemoryArtifactService())
              .build();
        });
  }

  @Test
  public void createAgentExecutor_withEmptyAgentAndApp_throwsException() {
    assertThrows(
        IllegalStateException.class,
        () -> {
          new AgentExecutor.Builder()
              .sessionService(new InMemorySessionService())
              .artifactService(new InMemoryArtifactService())
              .agentExecutorConfig(AgentExecutorConfig.builder().build())
              .build();
        });
  }

  @Test
  public void createAgentExecutor_noAgentExecutorConfig_throwsException() {
    assertThrows(
        NullPointerException.class,
        () -> {
          new AgentExecutor.Builder()
              .app(App.builder().name("test_app").rootAgent(testAgent).build())
              .sessionService(new InMemorySessionService())
              .artifactService(new InMemoryArtifactService())
              .build();
        });
  }

  private static final class TestAgent extends BaseAgent {
    private final Flowable<Event> eventsToEmit = Flowable.empty();

    TestAgent() {
      // BaseAgent constructor: name, description, examples, tools, model
      super("test_agent", "test", ImmutableList.of(), null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return eventsToEmit;
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return eventsToEmit;
    }
  }
}
