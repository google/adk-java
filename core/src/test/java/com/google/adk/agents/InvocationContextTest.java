/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import com.google.adk.apps.ResumabilityConfig;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.LlmCallsLimitExceededException;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public final class InvocationContextTest {

  @Mock private BaseSessionService mockSessionService;
  @Mock private BaseArtifactService mockArtifactService;
  @Mock private BaseMemoryService mockMemoryService;
  private final PluginManager pluginManager = new PluginManager();
  @Mock private BaseAgent mockAgent;
  private Session session;
  private Content userContent;
  private RunConfig runConfig;
  private Map<String, ActiveStreamingTool> activeStreamingTools;
  private LiveRequestQueue liveRequestQueue;
  private String testInvocationId;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    liveRequestQueue = new LiveRequestQueue();
    session = Session.builder("test-session-id").build();
    userContent = Content.builder().build();
    runConfig = RunConfig.builder().build();
    testInvocationId = "test-invocation-id";
    activeStreamingTools = new HashMap<>();
    activeStreamingTools.put("test-tool", new ActiveStreamingTool(new LiveRequestQueue()));
  }

  // The two flags select different flows: the shim runs the legacy one and is not resumable.
  @Test
  @SuppressWarnings("deprecation") // Exercises the deprecated shim.
  public void isResumable_andIsLegacyResumability_separateTheTwoModes() {
    InvocationContext shimContext =
        contextWith(ResumabilityConfig.builder().plainTextContinuationAutoResume(true).build());
    InvocationContext resumableContext =
        contextWith(ResumabilityConfig.builder().resumable(true).build());
    InvocationContext explicitlyOffContext =
        contextWith(ResumabilityConfig.builder().resumable(false).build());
    InvocationContext neitherContext = contextWith(null);

    assertThat(shimContext.isResumable()).isFalse();
    assertThat(shimContext.isLegacyResumability()).isTrue();

    assertThat(resumableContext.isResumable()).isTrue();
    assertThat(resumableContext.isLegacyResumability()).isFalse();

    // An explicit false and an absent config are the same answer, either side of the null guard.
    assertThat(explicitlyOffContext.isResumable()).isFalse();
    assertThat(neitherContext.isResumable()).isFalse();
    assertThat(neitherContext.isLegacyResumability()).isFalse();
  }

  private InvocationContext contextWith(ResumabilityConfig resumabilityConfig) {
    return InvocationContext.builder()
        .sessionService(mockSessionService)
        .artifactService(mockArtifactService)
        .pluginManager(pluginManager)
        .invocationId(testInvocationId)
        .agent(mockAgent)
        .session(session)
        .userContent(userContent)
        .runConfig(runConfig)
        .resumabilityConfig(resumabilityConfig)
        .build();
  }

  @Test
  public void testBuildWithUserContent() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    assertThat(context).isNotNull();
    assertThat(context.sessionService()).isEqualTo(mockSessionService);
    assertThat(context.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context.memoryService()).isEqualTo(mockMemoryService);
    assertThat(context.liveRequestQueue()).isEmpty();
    assertThat(context.invocationId()).isEqualTo(testInvocationId);
    assertThat(context.agent()).isEqualTo(mockAgent);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.userContent()).hasValue(userContent);
    assertThat(context.runConfig()).isEqualTo(runConfig);
    assertThat(context.endInvocation()).isFalse();
  }

  @Test
  public void testBuildWithNullUserContent() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    assertThat(context).isNotNull();
    assertThat(context.userContent()).isEmpty();
  }

  @Test
  public void testBuildWithLiveRequestQueue() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .liveRequestQueue(liveRequestQueue)
            .agent(mockAgent)
            .session(session)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    assertThat(context).isNotNull();
    assertThat(context.sessionService()).isEqualTo(mockSessionService);
    assertThat(context.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context.memoryService()).isEqualTo(mockMemoryService);
    assertThat(context.liveRequestQueue()).hasValue(liveRequestQueue);
    assertThat(context.invocationId()).startsWith("e-"); // Check format of generated ID
    assertThat(context.agent()).isEqualTo(mockAgent);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.userContent()).isEmpty();
    assertThat(context.runConfig()).isEqualTo(runConfig);
    assertThat(context.endInvocation()).isFalse();
  }

  @Test
  public void testToBuilder() {
    InvocationContext originalContext =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();
    originalContext.activeStreamingTools().putAll(activeStreamingTools);

    InvocationContext copiedContext = originalContext.toBuilder().build();

    assertThat(copiedContext).isNotNull();
    assertThat(copiedContext).isNotSameInstanceAs(originalContext);

    assertThat(copiedContext.sessionService()).isEqualTo(originalContext.sessionService());
    assertThat(copiedContext.artifactService()).isEqualTo(originalContext.artifactService());
    assertThat(copiedContext.memoryService()).isEqualTo(originalContext.memoryService());
    assertThat(copiedContext.liveRequestQueue()).isEqualTo(originalContext.liveRequestQueue());
    assertThat(copiedContext.invocationId()).isEqualTo(originalContext.invocationId());
    assertThat(copiedContext.agent()).isEqualTo(originalContext.agent());
    assertThat(copiedContext.session()).isEqualTo(originalContext.session());
    assertThat(copiedContext.userContent()).isEqualTo(originalContext.userContent());
    assertThat(copiedContext.runConfig()).isEqualTo(originalContext.runConfig());
    assertThat(copiedContext.endInvocation()).isEqualTo(originalContext.endInvocation());
    assertThat(copiedContext.activeStreamingTools())
        .isEqualTo(originalContext.activeStreamingTools());
    assertThat(copiedContext.callbackContextData())
        .isEqualTo(originalContext.callbackContextData());
  }

  @Test
  public void testBuildWithCallbackContextData() {
    ConcurrentHashMap<String, Object> data = new ConcurrentHashMap<>();
    data.put("key", "value");
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .callbackContextData(data)
            .build();

    assertThat(context.callbackContextData()).isEqualTo(data);
  }

  @Test
  public void testGetters() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    assertThat(context.sessionService()).isEqualTo(mockSessionService);
    assertThat(context.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context.memoryService()).isEqualTo(mockMemoryService);
    assertThat(context.liveRequestQueue()).isEmpty();
    assertThat(context.invocationId()).isEqualTo(testInvocationId);
    assertThat(context.agent()).isEqualTo(mockAgent);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.userContent()).hasValue(userContent);
    assertThat(context.runConfig()).isEqualTo(runConfig);
    assertThat(context.endInvocation()).isFalse();
  }

  @Test
  public void testSetAgent() {
    BaseAgent newMockAgent = mock(BaseAgent.class);
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .agent(newMockAgent)
            .build();

    assertThat(context.agent()).isEqualTo(newMockAgent);
  }

  @Test
  public void testNewInvocationContextId() {
    String id = InvocationContext.newInvocationContextId();

    assertThat(id).isNotNull();
    assertThat(id).isNotEmpty();
    assertThat(id).startsWith("e-");
    // Basic check for UUID format after "e-"
    assertThat(id.substring(2))
        .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  }

  @Test
  public void testEquals_sameObject() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    assertThat(context.equals(context)).isTrue();
  }

  @Test
  public void testEquals_null() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    assertThat(context.equals(null)).isFalse();
  }

  @Test
  public void testEquals_sameValues() {
    InvocationContext context1 =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    // Create another context with the same parameters
    InvocationContext context2 =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    assertThat(context1.equals(context2)).isTrue();
    assertThat(context2.equals(context1)).isTrue(); // Check symmetry
  }

  @Test
  public void testEquals_differentValues() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    // Create contexts with one field different
    InvocationContext contextWithDiffSessionService =
        InvocationContext.builder()
            .sessionService(mock(BaseSessionService.class)) // Different mock
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    InvocationContext contextWithDiffInvocationId =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId("another-id") // Different ID
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    InvocationContext contextWithDiffAgent =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mock(BaseAgent.class)) // Different mock
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    InvocationContext contextWithUserContentEmpty =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    InvocationContext contextWithLiveQueuePresent =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .liveRequestQueue(liveRequestQueue)
            .agent(mockAgent)
            .session(session)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    assertThat(context.equals(contextWithDiffSessionService)).isFalse();
    assertThat(context.equals(contextWithDiffInvocationId)).isFalse();
    assertThat(context.equals(contextWithDiffAgent)).isFalse();
    assertThat(context.equals(contextWithUserContentEmpty)).isFalse();
    assertThat(context.equals(contextWithLiveQueuePresent)).isFalse();

    InvocationContext contextWithDiffCallbackContextData =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .callbackContextData(new ConcurrentHashMap<>(ImmutableMap.of("key", "value")))
            .build();
    assertThat(context.equals(contextWithDiffCallbackContextData)).isFalse();
  }

  @Test
  public void testHashCode_differentValues() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    // Create contexts with one field different
    InvocationContext contextWithDiffSessionService =
        InvocationContext.builder()
            .sessionService(mock(BaseSessionService.class)) // Different mock
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    InvocationContext contextWithDiffInvocationId =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId("another-id") // Different ID
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .build();

    assertThat(context).isNotEqualTo(contextWithDiffSessionService);
    assertThat(context).isNotEqualTo(contextWithDiffInvocationId);

    InvocationContext contextWithDiffCallbackContextData =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .pluginManager(pluginManager)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(userContent)
            .runConfig(runConfig)
            .endInvocation(false)
            .callbackContextData(new ConcurrentHashMap<>(ImmutableMap.of("key", "value")))
            .build();
    assertThat(context.hashCode()).isNotEqualTo(contextWithDiffCallbackContextData.hashCode());
  }

  @Test
  public void incrementLlmCallsCount_whenLimitNotExceeded_doesNotThrow() throws Exception {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .runConfig(RunConfig.builder().setMaxLlmCalls(2).build())
            .build();

    context.incrementLlmCallsCount();
    context.incrementLlmCallsCount();
    // No exception thrown
  }

  @Test
  public void incrementLlmCallsCount_whenLimitExceeded_throwsException() throws Exception {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .runConfig(RunConfig.builder().setMaxLlmCalls(1).build())
            .build();

    context.incrementLlmCallsCount();
    LlmCallsLimitExceededException thrown =
        Assert.assertThrows(
            LlmCallsLimitExceededException.class, () -> context.incrementLlmCallsCount());
    assertThat(thrown).hasMessageThat().contains("limit of 1 exceeded");
  }

  @Test
  public void incrementLlmCallsCount_whenNoLimit_doesNotThrow() throws Exception {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .runConfig(RunConfig.builder().setMaxLlmCalls(0).build())
            .build();

    for (int i = 0; i < 100; i++) {
      context.incrementLlmCallsCount();
    }
  }

  @Test
  public void testSessionGetters() {
    Session sessionWithDetails =
        Session.builder("test-id").appName("test-app").userId("test-user").build();
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(sessionWithDetails)
            .build();

    assertThat(context.appName()).isEqualTo("test-app");
    assertThat(context.userId()).isEqualTo("test-user");
  }

  @Test
  public void testSetEndInvocation() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .build();

    assertThat(context.endInvocation()).isFalse();
    context.setEndInvocation(true);
    assertThat(context.endInvocation()).isTrue();
  }

  @Test
  // Testing deprecated methods.
  public void testBranch() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .branch("test-branch")
            .build();

    assertThat(context.branch()).hasValue("test-branch");

    context.branch("new-branch");
    assertThat(context.branch()).hasValue("new-branch");

    context.branch(null);
    assertThat(context.branch()).isEmpty();
  }

  @Test
  public void testActiveStreamingTools() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .build();

    assertThat(context.activeStreamingTools()).isEmpty();
    ActiveStreamingTool tool = new ActiveStreamingTool(new LiveRequestQueue());
    context.activeStreamingTools().put("tool1", tool);
    assertThat(context.activeStreamingTools()).containsEntry("tool1", tool);
  }

  @Test
  public void testEventsCompactionConfig() {
    EventsCompactionConfig config = new EventsCompactionConfig(5, 2);
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .eventsCompactionConfig(config)
            .build();

    assertThat(context.eventsCompactionConfig()).hasValue(config);
  }

  @Test
  // Testing deprecated methods.
  public void testBuilderOptionalParameters() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .liveRequestQueue(liveRequestQueue)
            .branch("test-branch")
            .userContent(userContent)
            .build();

    assertThat(context.liveRequestQueue()).hasValue(liveRequestQueue);
    assertThat(context.branch()).hasValue("test-branch");
    assertThat(context.userContent()).hasValue(userContent);
  }

  @Test
  public void build_missingInvocationId_null_throwsException() {
    InvocationContext.Builder builder =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .invocationId(null)
            .session(session);

    IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);
    assertThat(exception).hasMessageThat().isEqualTo("Invocation ID must be non-empty.");
  }

  @Test
  public void build_missingInvocationId_empty_throwsException() {
    InvocationContext.Builder builder =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .invocationId("")
            .session(session);

    IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);
    assertThat(exception).hasMessageThat().isEqualTo("Invocation ID must be non-empty.");
  }

  @Test
  public void build_missingAgent_throwsException() {
    InvocationContext.Builder builder =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .session(session);

    IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);
    assertThat(exception).hasMessageThat().isEqualTo("Agent must be set.");
  }

  @Test
  public void build_missingSession_throwsException() {
    InvocationContext.Builder builder =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent);

    IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);
    assertThat(exception).hasMessageThat().isEqualTo("Session must be set.");
  }

  @Test
  public void build_missingSessionService_throwsException() {
    InvocationContext.Builder builder =
        InvocationContext.builder()
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .session(session);

    IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);
    assertThat(exception).hasMessageThat().isEqualTo("Session service must be set.");
  }

  @Test
  public void eventsOnCurrentBranch_userEventOnSubBranch_isIncluded() {
    Event userOnChild = userEvent("agent_1.child");

    assertThat(contextOnBranch("agent_1", userOnChild).eventsOnCurrentBranch())
        .containsExactly(userOnChild);
  }

  @Test
  public void eventsOnCurrentBranch_agentEventOnSubBranch_isExcluded() {
    // Asymmetric with the user case on purpose: descendants' internal events stay hidden.
    Event agentOnChild = agentEvent("agent_1.child");

    assertThat(contextOnBranch("agent_1", agentOnChild).eventsOnCurrentBranch()).isEmpty();
  }

  @Test
  public void eventsOnCurrentBranch_siblingBranch_isExcluded() {
    Event userOnSibling = userEvent("agent_2");

    assertThat(contextOnBranch("agent_1", userOnSibling).eventsOnCurrentBranch()).isEmpty();
  }

  @Test
  public void eventsOnCurrentBranch_userEventOnLookalikeBranch_isExcluded() {
    // "agent_10" shares a prefix with "agent_1" but is not a sub-branch of it.
    Event userOnLookalike = userEvent("agent_10");

    assertThat(contextOnBranch("agent_1", userOnLookalike).eventsOnCurrentBranch()).isEmpty();
  }

  @Test
  public void eventsOnCurrentBranch_emptyBranch_doesNotMatchBranchedEvents() {
    // An empty string is a real branch value, not a synonym for "match everything".
    Event userOnBranch = userEvent("agent_1");

    assertThat(contextOnBranch("", userOnBranch).eventsOnCurrentBranch()).isEmpty();
  }

  @Test
  public void eventsOnCurrentBranch_noBranch_matchesEveryUserEventButNotAgentEvents() {
    Event userElsewhere = userEvent("agent_2.child");
    Event agentElsewhere = agentEvent("agent_2.child");

    assertThat(contextOnBranch(null, userElsewhere, agentElsewhere).eventsOnCurrentBranch())
        .containsExactly(userElsewhere);
  }

  @Test
  public void eventsOnCurrentBranch_userResponseToCallInSubtree_isKept() {
    Event callOnChild = callEvent("agent_1.child", "fc_1");
    Event reply = userResponseEvent("agent_1", "fc_1");

    assertThat(contextOnBranch("agent_1", callOnChild, reply).eventsOnCurrentBranch())
        .containsExactly(reply);
  }

  @Test
  public void eventsOnCurrentBranch_userResponseToCallElsewhere_isDropped() {
    // Sitting on this branch is not enough: the reply answers a parallel tree's call.
    Event callElsewhere = callEvent("agent_2", "fc_1");
    Event reply = userResponseEvent("agent_1", "fc_1");

    assertThat(contextOnBranch("agent_1", callElsewhere, reply).eventsOnCurrentBranch()).isEmpty();
  }

  @Test
  public void eventsOnCurrentBranch_userResponseToLookalikeBranchCall_isDropped() {
    // "agent_10" shares a prefix with "agent_1" but is not a sub-branch of it.
    Event callOnLookalike = callEvent("agent_10", "fc_1");
    Event reply = userResponseEvent("agent_1", "fc_1");

    assertThat(contextOnBranch("agent_1", callOnLookalike, reply).eventsOnCurrentBranch())
        .isEmpty();
  }

  @Test
  public void eventsOnCurrentBranch_severalReplies_eachJudgedAgainstItsOwnCall() {
    Event callHere = callEvent("agent_1", "fc_here");
    Event callOnChild = callEvent("agent_1.child", "fc_child");
    Event callElsewhere = callEvent("agent_2", "fc_far");
    Event replyHere = userResponseEvent("agent_1", "fc_here");
    Event replyFar = userResponseEvent("agent_1", "fc_far");
    Event replyChild = userResponseEvent("agent_1", "fc_child");

    InvocationContext context =
        contextOnBranch(
            "agent_1", callHere, callOnChild, callElsewhere, replyHere, replyFar, replyChild);

    // callHere matches exactly so it survives; the sub-branch calls do not, but their replies do.
    assertThat(context.eventsOnCurrentBranch())
        .containsExactly(callHere, replyHere, replyChild)
        .inOrder();
  }

  @Test
  public void eventsOnCurrentBranch_emptyBranchAndDotPrefixedEvent_isExcluded() {
    // Pins the empty-branch guard: without it the prefix test would admit a dot-prefixed branch.
    Event dotPrefixed = userEvent(".x");

    assertThat(contextOnBranch("", dotPrefixed).eventsOnCurrentBranch()).isEmpty();
  }

  @Test
  public void eventsOnCurrentBranch_rootAgentEventWhileOnSubBranch_isExcluded() {
    // The narrowing direction: the old scan admitted every event, this one demands equality.
    Event rootAgentEvent = agentEvent(null);

    assertThat(contextOnBranch("agent_1", rootAgentEvent).eventsOnCurrentBranch()).isEmpty();
  }

  @Test
  public void eventsOnCurrentBranch_rootUserEventWhileOnSubBranch_isIncluded() {
    // The user twin of the case above: a null-branch user event still matches.
    Event rootUserEvent = userEvent(null);

    assertThat(contextOnBranch("agent_1", rootUserEvent).eventsOnCurrentBranch())
        .containsExactly(rootUserEvent);
  }

  @Test
  public void eventsOnCurrentBranch_userResponseToUnbranchedCall_isDropped() {
    // A root-level call contributes no id, so a reply answering only it is dropped.
    Event rootCall = callEvent(null, "fc_1");
    Event reply = userResponseEvent("agent_1", "fc_1");

    assertThat(contextOnBranch("agent_1", rootCall, reply).eventsOnCurrentBranch()).isEmpty();
  }

  private InvocationContext contextOnBranch(@Nullable String branch, Event... events) {
    return InvocationContext.builder()
        .sessionService(mockSessionService)
        .artifactService(mockArtifactService)
        .memoryService(mockMemoryService)
        .pluginManager(pluginManager)
        .invocationId(testInvocationId)
        .branch(branch)
        .agent(mockAgent)
        .session(Session.builder("test-session-id").events(ImmutableList.copyOf(events)).build())
        .runConfig(runConfig)
        .build();
  }

  private static Event userEvent(@Nullable String branch) {
    return Event.builder().author("user").branch(branch).build();
  }

  private static Event agentEvent(@Nullable String branch) {
    return Event.builder().author("some_agent").branch(branch).build();
  }

  private static Event agentEvent(
      String invocationId, String author, EventActions actions, Content content) {
    Event.Builder builder =
        Event.builder().id(Event.generateEventId()).invocationId(invocationId).author(author);
    if (actions != null) {
      builder.actions(actions);
    }
    if (content != null) {
      builder.content(content);
    }
    return builder.build();
  }

  private static Event callEvent(@Nullable String branch, String callId) {
    return Event.builder()
        .author("some_agent")
        .branch(branch)
        .content(
            Content.fromParts(
                Part.builder()
                    .functionCall(FunctionCall.builder().id(callId).name("t").build())
                    .build()))
        .build();
  }

  private static Event userResponseEvent(@Nullable String branch, String callId) {
    return Event.builder()
        .author("user")
        .branch(branch)
        .content(
            Content.fromParts(
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .id(callId)
                            .name("t")
                            .response(ImmutableMap.of())
                            .build())
                    .build()))
        .build();
  }

  // ---- Resumability: runtime checkpoint state. ----

  private InvocationContext resumableContext(Session eventSession, String invocationId) {
    return InvocationContext.builder()
        .sessionService(mockSessionService)
        .artifactService(mockArtifactService)
        .agent(mockAgent)
        .session(eventSession)
        .invocationId(invocationId)
        .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
        .build();
  }

  @Test
  public void setAgentState_storesStateAndClearsEnd() {
    InvocationContext context = resumableContext(session, "inv");

    context.setAgentState("a", ImmutableMap.of("k", "v"), /* endOfAgent= */ false);

    assertThat(context.agentStates()).containsEntry("a", ImmutableMap.of("k", "v"));
    assertThat(context.endOfAgents()).containsEntry("a", false);
  }

  @Test
  public void setAgentState_endOfAgent_marksEndedAndDropsState() {
    InvocationContext context = resumableContext(session, "inv");
    context.setAgentState("a", ImmutableMap.of("k", "v"), /* endOfAgent= */ false);

    context.setAgentState("a", /* agentState= */ null, /* endOfAgent= */ true);

    assertThat(context.endOfAgents()).containsEntry("a", true);
    assertThat(context.agentStates()).doesNotContainKey("a");
  }

  @Test
  public void setAgentState_nullStateNotEnded_clearsBoth() {
    InvocationContext context = resumableContext(session, "inv");
    context.setAgentState("a", ImmutableMap.of("k", "v"), /* endOfAgent= */ false);

    context.setAgentState("a", /* agentState= */ null, /* endOfAgent= */ false);

    assertThat(context.agentStates()).doesNotContainKey("a");
    assertThat(context.endOfAgents()).doesNotContainKey("a");
  }

  @Test
  public void resetSubAgentStates_recursivelyClearsDescendants() {
    BaseAgent grandChild = SequentialAgent.builder().name("gc").build();
    BaseAgent child1 =
        SequentialAgent.builder().name("c1").subAgents(ImmutableList.of(grandChild)).build();
    BaseAgent child2 = SequentialAgent.builder().name("c2").build();
    BaseAgent parent =
        SequentialAgent.builder().name("p").subAgents(ImmutableList.of(child1, child2)).build();
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(parent)
            .session(session)
            .invocationId("inv")
            .resumabilityConfig(ResumabilityConfig.builder().resumable(true).build())
            .build();
    context.setAgentState("c1", ImmutableMap.of("k", "v"), /* endOfAgent= */ false);
    context.setAgentState("c2", ImmutableMap.of("k", "v"), /* endOfAgent= */ false);
    context.setAgentState("gc", ImmutableMap.of("k", "v"), /* endOfAgent= */ false);

    context.resetSubAgentStates("p");

    // Every descendant of p is cleared, including the grandchild reached recursively.
    assertThat(context.agentStates()).doesNotContainKey("c1");
    assertThat(context.agentStates()).doesNotContainKey("c2");
    assertThat(context.agentStates()).doesNotContainKey("gc");
  }

  @Test
  public void shouldPauseInvocation_resumableWithLongRunningCall_returnsTrue() {
    InvocationContext context = resumableContext(session, "inv");
    Event event =
        Event.builder()
            .id("e1")
            .invocationId("inv")
            .author("a")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(FunctionCall.builder().id("c1").name("tool").build())
                        .build()))
            .longRunningToolIds(ImmutableSet.of("c1"))
            .build();

    assertThat(context.shouldPauseInvocation(event)).isTrue();
  }

  @Test
  public void shouldPauseInvocation_notResumable_returnsFalse() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(session)
            .invocationId("inv")
            .build();
    Event event =
        Event.builder()
            .id("e1")
            .invocationId("inv")
            .author("a")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(FunctionCall.builder().id("c1").name("tool").build())
                        .build()))
            .longRunningToolIds(ImmutableSet.of("c1"))
            .build();

    assertThat(context.shouldPauseInvocation(event)).isFalse();
  }

  @Test
  public void shouldPauseInvocation_noLongRunningIds_returnsFalse() {
    InvocationContext context = resumableContext(session, "inv");
    Event event =
        Event.builder()
            .id("e1")
            .invocationId("inv")
            .author("a")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(FunctionCall.builder().id("c1").name("tool").build())
                        .build()))
            .build();

    assertThat(context.shouldPauseInvocation(event)).isFalse();
  }

  @Test
  public void shouldPauseInvocation_callIdNotInLongRunningSet_returnsFalse() {
    InvocationContext context = resumableContext(session, "inv");
    Event event =
        Event.builder()
            .id("e1")
            .invocationId("inv")
            .author("a")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(FunctionCall.builder().id("c1").name("tool").build())
                        .build()))
            .longRunningToolIds(ImmutableSet.of("other"))
            .build();

    assertThat(context.shouldPauseInvocation(event)).isFalse();
  }

  private static Event twoLongRunningCallsEvent() {
    return Event.builder()
        .id("m")
        .invocationId("inv")
        .author("root")
        .content(
            Content.fromParts(
                Part.builder()
                    .functionCall(FunctionCall.builder().id("a").name("approve_a").build())
                    .build(),
                Part.builder()
                    .functionCall(FunctionCall.builder().id("b").name("approve_b").build())
                    .build()))
        .longRunningToolIds(ImmutableSet.of("a", "b"))
        .build();
  }

  private static Event longRunningCallEvent(String callId, String name) {
    return Event.builder()
        .id("m-" + callId)
        .invocationId("inv")
        .author("root")
        .content(
            Content.fromParts(
                Part.builder()
                    .functionCall(FunctionCall.builder().id(callId).name(name).build())
                    .build()))
        .longRunningToolIds(ImmutableSet.of(callId))
        .build();
  }

  private static Event functionResponseEvent(String id, String name) {
    return Event.builder()
        .id("r-" + id)
        .invocationId("inv")
        .author("user")
        .content(
            Content.fromParts(
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .id(id)
                            .name(name)
                            .response(ImmutableMap.of("status", "done"))
                            .build())
                    .build()))
        .build();
  }

  @Test
  public void lastEventsPauseInvocation_callInsideWindow_returnsTrue() {
    Session eventSession = Session.builder("s").build();
    eventSession.addEvent(twoLongRunningCallsEvent());
    eventSession.addEvent(functionResponseEvent("a", "approve_a"));
    InvocationContext context = resumableContext(eventSession, "inv");

    assertThat(context.lastEventsPauseInvocation()).isTrue();
  }

  @Test
  public void lastEventsPauseInvocation_callPushedOutOfWindow_returnsFalse() {
    Session eventSession = Session.builder("s").build();
    eventSession.addEvent(twoLongRunningCallsEvent());
    eventSession.addEvent(functionResponseEvent("a", "approve_a"));
    eventSession.addEvent(functionResponseEvent("b", "approve_b"));
    InvocationContext context = resumableContext(eventSession, "inv");

    assertThat(context.lastEventsPauseInvocation()).isFalse();
  }

  // Python looks only at whether the event carries a long-running call, never at whether it was
  // answered, so a fully answered call still inside the window pauses.
  @Test
  public void lastEventsPauseInvocation_answeredCallInsideWindow_returnsTrue() {
    Session eventSession = Session.builder("s").build();
    eventSession.addEvent(longRunningCallEvent("c1", "approve_c1"));
    eventSession.addEvent(functionResponseEvent("c1", "approve_c1"));
    InvocationContext context = resumableContext(eventSession, "inv");

    assertThat(context.lastEventsPauseInvocation()).isTrue();
  }

  // The converse: an unanswered call older than the two-event window does not pause, as in Python.
  @Test
  public void lastEventsPauseInvocation_unansweredCallOlderThanWindow_returnsFalse() {
    Session eventSession = Session.builder("s").build();
    eventSession.addEvent(twoLongRunningCallsEvent());
    eventSession.addEvent(
        agentEvent("inv", "root", null, Content.fromParts(Part.fromText("thinking"))));
    eventSession.addEvent(
        agentEvent("inv", "root", null, Content.fromParts(Part.fromText("still here"))));
    InvocationContext context = resumableContext(eventSession, "inv");

    assertThat(context.lastEventsPauseInvocation()).isFalse();
  }

  @Test
  public void lastEventsPauseInvocation_noCall_returnsFalse() {
    Session eventSession = Session.builder("s").build();
    eventSession.addEvent(agentEvent("inv", "user", null, Content.fromParts(Part.fromText("hi"))));
    eventSession.addEvent(
        agentEvent("inv", "root", null, Content.fromParts(Part.fromText("answer"))));
    InvocationContext context = resumableContext(eventSession, "inv");

    assertThat(context.lastEventsPauseInvocation()).isFalse();
  }

  @Test
  public void events_filtersByInvocationAndBranch() {
    Session eventSession = Session.builder("s").build();
    Event thisInv = agentEvent("inv", "a", null, Content.fromParts(Part.fromText("x")));
    Event otherInv = agentEvent("other", "a", null, Content.fromParts(Part.fromText("y")));
    Event branchB =
        Event.builder()
            .id("e3")
            .invocationId("inv")
            .author("a")
            .branch("branchB")
            .content(Content.fromParts(Part.fromText("z")))
            .build();
    eventSession.addEvent(thisInv);
    eventSession.addEvent(otherInv);
    eventSession.addEvent(branchB);
    InvocationContext context = resumableContext(eventSession, "inv");

    assertThat(context.events(/* currentInvocation= */ true, /* currentBranch= */ false))
        .containsExactly(thisInv, branchB)
        .inOrder();
    // A null-branch event is visible on any branch; the "branchB" event is filtered out.
    assertThat(context.events(/* currentInvocation= */ true, /* currentBranch= */ true))
        .containsExactly(thisInv);
  }

  @Test
  public void populateInvocationAgentStates_notResumable_doesNothing() {
    Session eventSession = Session.builder("s").build();
    eventSession
        .events()
        .add(
            agentEvent(
                "inv",
                "a",
                EventActions.builder().agentState(ImmutableMap.of("k", "v")).build(),
                Content.fromParts(Part.fromText("x"))));
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .agent(mockAgent)
            .session(eventSession)
            .invocationId("inv")
            .build();

    context.populateInvocationAgentStates();

    assertThat(context.agentStates()).isEmpty();
    assertThat(context.endOfAgents()).isEmpty();
  }

  @Test
  public void populateInvocationAgentStates_endOfAgentEvent_marksEndedAndRemovesState() {
    Session eventSession = Session.builder("s").build();
    eventSession
        .events()
        .add(
            agentEvent(
                "inv",
                "a",
                EventActions.builder().agentState(ImmutableMap.of("k", "v")).build(),
                Content.fromParts(Part.fromText("x"))));
    eventSession
        .events()
        .add(agentEvent("inv", "a", EventActions.builder().endOfAgent(true).build(), null));
    InvocationContext context = resumableContext(eventSession, "inv");

    context.populateInvocationAgentStates();

    assertThat(context.endOfAgents()).containsEntry("a", true);
    assertThat(context.agentStates()).doesNotContainKey("a");
  }

  @Test
  public void populateInvocationAgentStates_agentStateEvent_setsStateAndClearsEnd() {
    Session eventSession = Session.builder("s").build();
    eventSession
        .events()
        .add(
            agentEvent(
                "inv",
                "a",
                EventActions.builder().agentState(ImmutableMap.of("k", "v")).build(),
                Content.fromParts(Part.fromText("x"))));
    InvocationContext context = resumableContext(eventSession, "inv");

    context.populateInvocationAgentStates();

    assertThat(context.agentStates()).containsEntry("a", ImmutableMap.of("k", "v"));
    assertThat(context.endOfAgents()).containsEntry("a", false);
  }

  @Test
  public void populateInvocationAgentStates_agentStateAndEndOfAgent_endOfAgentWins() {
    Session eventSession = Session.builder("s").build();
    eventSession
        .events()
        .add(
            agentEvent(
                "inv",
                "a",
                EventActions.builder()
                    .endOfAgent(true)
                    .agentState(ImmutableMap.of("k", "v"))
                    .build(),
                /* content= */ null));
    InvocationContext context = resumableContext(eventSession, "inv");

    context.populateInvocationAgentStates();

    assertThat(context.endOfAgents()).containsEntry("a", true);
    assertThat(context.agentStates()).doesNotContainKey("a");
  }

  // EventActions.setEndInvocation aliases endOfAgent, so only a content-less event counts as done.
  @Test
  public void populateInvocationAgentStates_endOfAgentOnContentEvent_notTreatedAsCheckpoint() {
    Session eventSession = Session.builder("s").build();
    eventSession
        .events()
        .add(
            agentEvent(
                "inv",
                "a",
                EventActions.builder().endOfAgent(true).build(),
                Content.fromParts(Part.fromText("tool ended the invocation"))));
    InvocationContext context = resumableContext(eventSession, "inv");

    context.populateInvocationAgentStates();

    assertThat(context.endOfAgents()).doesNotContainEntry("a", true);
  }

  // An after-agent callback emits content after the end-of-agent marker; Python reopens the agent
  // on it rather than leaving a resume with nothing to run.
  @Test
  public void populateInvocationAgentStates_contentAfterEndOfAgent_reopensAgent() {
    Session eventSession = Session.builder("s").build();
    eventSession
        .events()
        .add(agentEvent("inv", "a", EventActions.builder().endOfAgent(true).build(), null));
    eventSession
        .events()
        .add(agentEvent("inv", "a", null, Content.fromParts(Part.fromText("callback output"))));
    InvocationContext context = resumableContext(eventSession, "inv");

    context.populateInvocationAgentStates();

    assertThat(context.agentStates()).containsKey("a");
    assertThat(context.endOfAgents()).containsEntry("a", false);
  }

  @Test
  public void populateInvocationAgentStates_newContentFromNonUserAuthor_initializesEmptyState() {
    Session eventSession = Session.builder("s").build();
    eventSession
        .events()
        .add(agentEvent("inv", "a", null, Content.fromParts(Part.fromText("hello"))));
    InvocationContext context = resumableContext(eventSession, "inv");

    context.populateInvocationAgentStates();

    assertThat(context.agentStates()).containsKey("a");
    assertThat(context.agentStates().get("a")).isEmpty();
    assertThat(context.endOfAgents()).containsEntry("a", false);
  }

  @Test
  public void populateInvocationAgentStates_userMessage_ignoredForDefaultState() {
    Session eventSession = Session.builder("s").build();
    eventSession
        .events()
        .add(agentEvent("inv", "user", null, Content.fromParts(Part.fromText("hi"))));
    InvocationContext context = resumableContext(eventSession, "inv");

    context.populateInvocationAgentStates();

    assertThat(context.agentStates()).isEmpty();
  }

  @Test
  public void populateInvocationAgentStates_noContentNoState_ignored() {
    Session eventSession = Session.builder("s").build();
    eventSession.events().add(agentEvent("inv", "a", null, null));
    InvocationContext context = resumableContext(eventSession, "inv");

    context.populateInvocationAgentStates();

    assertThat(context.agentStates()).isEmpty();
    assertThat(context.endOfAgents()).isEmpty();
  }
}
