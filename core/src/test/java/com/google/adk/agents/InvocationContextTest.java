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

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.models.LlmCallsLimitExceededException;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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
  // Testing deprecated methods.
  public void testDeprecatedCreateMethods() {
    InvocationContext context1 =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .invocationId(testInvocationId)
            .agent(mockAgent)
            .session(session)
            .userContent(Optional.ofNullable(userContent))
            .runConfig(runConfig)
            .build();

    assertThat(context1.sessionService()).isEqualTo(mockSessionService);
    assertThat(context1.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context1.invocationId()).isEqualTo(testInvocationId);
    assertThat(context1.agent()).isEqualTo(mockAgent);
    assertThat(context1.session()).isEqualTo(session);
    assertThat(context1.userContent()).hasValue(userContent);
    assertThat(context1.runConfig()).isEqualTo(runConfig);

    InvocationContext context2 =
        InvocationContext.create(
            mockSessionService,
            mockArtifactService,
            mockAgent,
            session,
            liveRequestQueue,
            runConfig);

    assertThat(context2.sessionService()).isEqualTo(mockSessionService);
    assertThat(context2.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context2.agent()).isEqualTo(mockAgent);
    assertThat(context2.session()).isEqualTo(session);
    assertThat(context2.liveRequestQueue()).hasValue(liveRequestQueue);
    assertThat(context2.runConfig()).isEqualTo(runConfig);
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
            .liveRequestQueue(Optional.of(liveRequestQueue))
            .branch(Optional.of("test-branch"))
            .userContent(Optional.of(userContent))
            .build();

    assertThat(context.liveRequestQueue()).hasValue(liveRequestQueue);
    assertThat(context.branch()).hasValue("test-branch");
    assertThat(context.userContent()).hasValue(userContent);
  }

  @Test
  // Testing deprecated methods.
  public void testDeprecatedConstructor() {
    InvocationContext context =
        new InvocationContext(
            mockSessionService,
            mockArtifactService,
            mockMemoryService,
            pluginManager,
            Optional.of(liveRequestQueue),
            Optional.of("test-branch"),
            testInvocationId,
            mockAgent,
            session,
            Optional.of(userContent),
            runConfig,
            true);

    assertThat(context.sessionService()).isEqualTo(mockSessionService);
    assertThat(context.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context.memoryService()).isEqualTo(mockMemoryService);
    assertThat(context.pluginManager()).isEqualTo(pluginManager);
    assertThat(context.liveRequestQueue()).hasValue(liveRequestQueue);
    assertThat(context.branch()).hasValue("test-branch");
    assertThat(context.invocationId()).isEqualTo(testInvocationId);
    assertThat(context.agent()).isEqualTo(mockAgent);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.userContent()).hasValue(userContent);
    assertThat(context.runConfig()).isEqualTo(runConfig);
    assertThat(context.endInvocation()).isTrue();
  }

  @Test
  // Testing deprecated methods.
  public void testDeprecatedConstructor_11params() {
    InvocationContext context =
        new InvocationContext(
            mockSessionService,
            mockArtifactService,
            mockMemoryService,
            Optional.of(liveRequestQueue),
            Optional.of("test-branch"),
            testInvocationId,
            mockAgent,
            session,
            Optional.of(userContent),
            runConfig,
            true);

    assertThat(context.sessionService()).isEqualTo(mockSessionService);
    assertThat(context.artifactService()).isEqualTo(mockArtifactService);
    assertThat(context.memoryService()).isEqualTo(mockMemoryService);
    assertThat(context.liveRequestQueue()).hasValue(liveRequestQueue);
    assertThat(context.branch()).hasValue("test-branch");
    assertThat(context.invocationId()).isEqualTo(testInvocationId);
    assertThat(context.agent()).isEqualTo(mockAgent);
    assertThat(context.session()).isEqualTo(session);
    assertThat(context.userContent()).hasValue(userContent);
    assertThat(context.runConfig()).isEqualTo(runConfig);
    assertThat(context.endInvocation()).isTrue();
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
}
