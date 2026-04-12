package com.google.adk.runner;

import static com.google.adk.testing.TestUtils.createContent;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.Resumable;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for the resumability feature in the {@link Runner}.
 *
 * <p>This test suite verifies that when a session contains events from a resumable agent, the
 * runner correctly bypasses earlier workflow steps (like the root agent) and resumes execution
 * directly at the last active resumable agent.
 */
@RunWith(JUnit4.class)
public final class RunnerResumabilityTest {

  private static class TestResumableAgent extends BaseAgent implements Resumable {
    private final boolean resumable;

    public TestResumableAgent(String name, boolean resumable) {
      super(name, "", ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
      this.resumable = resumable;
    }

    @Override
    public boolean isResumable() {
      return resumable;
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext context) {
      return Flowable.just(
          Event.builder()
              .id("event-" + name())
              .author(name())
              .content(
                  Content.builder()
                      .parts(
                          ImmutableList.of(Part.builder().text("response from " + name()).build()))
                      .build())
              .build());
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext context) {
      return runAsyncImpl(context);
    }
  }

  /**
   * Verifies that {@link Runner#runAsync} picks up execution from a resumable sub-agent.
   *
   * <p>This test configures a workflow hierarchy with a root agent and a resumable sub-agent. By
   * pre-loading the session state with an initial event authored by the sub-agent, we simulate a
   * scenario where execution stopped within the sub-workflow. Calling {@code runAsync} triggers the
   * resume behavior, bypassing the default root flow.
   */
  @Test
  public void runAsync_resumesAtResumableSubAgent() {
    TestResumableAgent subAgent = new TestResumableAgent("sub_agent", true);
    LlmAgent rootAgent =
        LlmAgent.builder()
            .name("root_agent")
            .model(createTestLlm(createLlmResponse(createContent("from root"))))
            .subAgents(ImmutableList.of(subAgent))
            .build();

    Runner runner =
        Runner.builder().app(App.builder().name("test").rootAgent(rootAgent).build()).build();

    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    Event subAgentEvent =
        Event.builder()
            .id("initial-event")
            .author("sub_agent")
            .content(createContent("subagent greeting"))
            .build();

    var unused = runner.sessionService().appendEvent(session, subAgentEvent).blockingGet();

    var events =
        runner.runAsync("user", session.id(), createContent("continue")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("sub_agent: response from sub_agent");
  }

  /**
   * Verifies that {@link Runner#runAsync} does not resume execution from a non-resumable sub-agent.
   *
   * <p>This test ensures that if the sub-agent is NOT marked as resumable, even if there are
   * existing events from it in the session, the runner falls back to regular execution starting
   * from the root agent.
   */
  @Test
  public void runAsync_doesNotResumeAtNonResumableSubAgent() {
    TestResumableAgent subAgent = new TestResumableAgent("sub_agent", false);
    LlmAgent rootAgent =
        LlmAgent.builder()
            .name("root_agent")
            .model(createTestLlm(createLlmResponse(createContent("from root"))))
            .subAgents(ImmutableList.of(subAgent))
            .build();

    Runner runner =
        Runner.builder().app(App.builder().name("test").rootAgent(rootAgent).build()).build();

    Session session = runner.sessionService().createSession("test", "user").blockingGet();

    Event subAgentEvent =
        Event.builder()
            .id("initial-event")
            .author("sub_agent")
            .content(createContent("subagent greeting"))
            .build();

    var unused = runner.sessionService().appendEvent(session, subAgentEvent).blockingGet();

    var events =
        runner.runAsync("user", session.id(), createContent("continue")).toList().blockingGet();

    assertThat(simplifyEvents(events)).containsExactly("root_agent: from root");
  }
}
