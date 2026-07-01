package com.google.adk.plugins.bigquery;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BigQueryAgentAnalyticsPluginTest {
  private FakeAnalyticsWriter writer;
  private BigQueryAgentAnalyticsPlugin plugin;
  private InvocationContext invocationContext;
  private CallbackContext callbackContext;
  private TestBaseAgent agent;
  private BaseTool tool;
  private ToolContext toolContext;
  private LlmRequest.Builder llmRequestBuilder;
  private LlmResponse llmResponse;

  @Before
  public void setUp() throws Exception {
    writer = new FakeAnalyticsWriter();
    BigQueryAgentAnalyticsPlugin.BigQueryLoggerConfig config =
        BigQueryAgentAnalyticsPlugin.BigQueryLoggerConfig.builder()
            .setBatchSize(1) // flush immediately
            .setBatchFlushIntervalMs(1)
            .build();
    plugin = new BigQueryAgentAnalyticsPlugin(writer, config);

    invocationContext = mock(InvocationContext.class);
    when(invocationContext.invocationId()).thenReturn("inv-123");
    when(invocationContext.userId()).thenReturn("user-1");
    Session session = Session.builder("session-1").build();
    when(invocationContext.session()).thenReturn(session);

    agent =
        new TestBaseAgent(
            "agent-1",
            "description-1",
            ImmutableList.of(),
            ImmutableList.of(),
            () -> Flowable.empty());
    when(invocationContext.agent()).thenReturn(agent);

    callbackContext = mock(CallbackContext.class);
    when(callbackContext.invocationId()).thenReturn("inv-123");
    when(callbackContext.agentName()).thenReturn("agent-1");

    tool = mock(BaseTool.class);
    when(tool.name()).thenReturn("tool-1");

    toolContext = mock(ToolContext.class);
    when(toolContext.invocationId()).thenReturn("inv-123");
    when(toolContext.agentName()).thenReturn("agent-1");

    llmRequestBuilder = LlmRequest.builder().model("gemini-1.5");
    llmResponse = LlmResponse.builder().build();
  }

  @After
  public void tearDown() throws Exception {
    if (plugin != null) {
      plugin.close().test().await();
    }
  }

  @Test
  public void beforeRunCallback_logsInvocationStarting() throws Exception {
    plugin.beforeRunCallback(invocationContext).test().await().assertComplete();
    Thread.sleep(200);

    assertThat(writer.batch).hasSize(1);
    assertThat(writer.batch.get(0)).containsEntry("event_type", "INVOCATION_STARTING");
    assertThat(writer.batch.get(0)).containsEntry("agent", "agent-1");
    assertThat(writer.batch.get(0)).containsEntry("invocation_id", "inv-123");
    assertThat(writer.batch.get(0)).containsEntry("session_id", "session-1");
    assertThat(writer.batch.get(0)).containsEntry("user_id", "user-1");
  }

  @Test
  public void afterRunCallback_logsInvocationCompleted() throws Exception {
    plugin.afterRunCallback(invocationContext).test().await().assertComplete();
    Thread.sleep(200);

    assertThat(writer.batch).hasSize(1);
    assertThat(writer.batch.get(0)).containsEntry("event_type", "INVOCATION_COMPLETED");
  }

  @Test
  public void beforeAgentCallback_logsAgentStarting() throws Exception {
    plugin.beforeAgentCallback(agent, callbackContext).test().await().assertComplete();
    Thread.sleep(200);

    assertThat(writer.batch).hasSize(1);
    assertThat(writer.batch.get(0)).containsEntry("event_type", "AGENT_STARTING");
    assertThat(writer.batch.get(0)).containsEntry("agent", "agent-1");
  }

  @Test
  public void afterAgentCallback_logsAgentCompleted() throws Exception {
    plugin.afterAgentCallback(agent, callbackContext).test().await().assertComplete();
    Thread.sleep(200);

    assertThat(writer.batch).hasSize(1);
    assertThat(writer.batch.get(0)).containsEntry("event_type", "AGENT_COMPLETED");
  }

  @Test
  public void beforeModelCallback_logsLlmRequest() throws Exception {
    plugin.beforeModelCallback(callbackContext, llmRequestBuilder).test().await().assertComplete();
    Thread.sleep(200);

    assertThat(writer.batch).hasSize(1);
    assertThat(writer.batch.get(0)).containsEntry("event_type", "LLM_REQUEST");
    assertThat(writer.batch.get(0)).containsKey("content");
  }

  @Test
  public void afterModelCallback_logsLlmResponse() throws Exception {
    plugin.afterModelCallback(callbackContext, llmResponse).test().await().assertComplete();
    Thread.sleep(200);

    assertThat(writer.batch).hasSize(1);
    assertThat(writer.batch.get(0)).containsEntry("event_type", "LLM_RESPONSE");
  }

  @Test
  public void beforeToolCallback_logsToolCall() throws Exception {
    Map<String, Object> args = new HashMap<>();
    args.put("key", "val");
    plugin.beforeToolCallback(tool, args, toolContext).test().await().assertComplete();
    Thread.sleep(200);

    assertThat(writer.batch).hasSize(1);
    assertThat(writer.batch.get(0)).containsEntry("event_type", "TOOL_CALL");
    assertThat(writer.batch.get(0)).containsKey("content");
  }

  @Test
  public void afterToolCallback_logsToolResponse() throws Exception {
    Map<String, Object> args = new HashMap<>();
    Map<String, Object> result = new HashMap<>();
    plugin.afterToolCallback(tool, args, toolContext, result).test().await().assertComplete();
    Thread.sleep(200);

    assertThat(writer.batch).hasSize(1);
    assertThat(writer.batch.get(0)).containsEntry("event_type", "TOOL_RESPONSE");
  }

  @Test
  public void configLimits_areRespected() throws Exception {
    BigQueryAgentAnalyticsPlugin.BigQueryLoggerConfig config =
        BigQueryAgentAnalyticsPlugin.BigQueryLoggerConfig.builder()
            .setBatchSize(1)
            .setBatchFlushIntervalMs(1)
            .setMaxContentLength(20)
            .build();
    plugin = new BigQueryAgentAnalyticsPlugin(writer, config);

    Map<String, Object> args = new HashMap<>();
    args.put("key", "a_very_long_string_that_should_be_truncated_now");
    plugin.beforeToolCallback(tool, args, toolContext).test().await().assertComplete();
    Thread.sleep(200);

    assertThat(writer.batch).hasSize(1);
    String content = (String) writer.batch.get(0).get("content");
    assertThat(content).endsWith("...[TRUNCATED]");
  }
}
