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
import static org.mockito.Mockito.mock;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.ResumabilityConfig;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
  public void testCopyOf() {
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
  }

  @Test
  public void isResumable_whenResumabilityConfigIsNotResumable_isFalse() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .session(session)
            .resumabilityConfig(new ResumabilityConfig(false))
            .build();
    assertThat(context.isResumable()).isFalse();
  }

  @Test
  public void isResumable_whenResumabilityConfigIsResumable_isTrue() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .session(session)
            .resumabilityConfig(new ResumabilityConfig(true))
            .build();
    assertThat(context.isResumable()).isTrue();
  }

  @Test
  public void shouldPauseInvocation_whenNotResumable_isFalse() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .session(session)
            .resumabilityConfig(new ResumabilityConfig(false))
            .build();
    Event event =
        Event.builder()
            .longRunningToolIds(Optional.of(ImmutableSet.of("fc1")))
            .content(
                Content.builder()
                    .parts(
                        ImmutableList.of(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder().name("tool1").id("fc1").build())
                                .build()))
                    .build())
            .build();
    assertThat(context.shouldPauseInvocation(event)).isFalse();
  }

  @Test
  public void shouldPauseInvocation_whenResumableAndNoLongRunningToolIds_isFalse() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .session(session)
            .resumabilityConfig(new ResumabilityConfig(true))
            .build();
    Event event =
        Event.builder()
            .content(
                Content.builder()
                    .parts(
                        ImmutableList.of(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder().name("tool1").id("fc1").build())
                                .build()))
                    .build())
            .build();
    assertThat(context.shouldPauseInvocation(event)).isFalse();
  }

  @Test
  public void shouldPauseInvocation_whenResumableAndNoFunctionCalls_isFalse() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .session(session)
            .resumabilityConfig(new ResumabilityConfig(true))
            .build();
    Event event = Event.builder().longRunningToolIds(Optional.of(ImmutableSet.of("fc1"))).build();
    assertThat(context.shouldPauseInvocation(event)).isFalse();
  }

  @Test
  public void shouldPauseInvocation_whenResumableAndNoMatchingFunctionCallId_isFalse() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .session(session)
            .resumabilityConfig(new ResumabilityConfig(true))
            .build();
    Event event =
        Event.builder()
            .longRunningToolIds(Optional.of(ImmutableSet.of("fc2")))
            .content(
                Content.builder()
                    .parts(
                        ImmutableList.of(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder().name("tool1").id("fc1").build())
                                .build()))
                    .build())
            .build();
    assertThat(context.shouldPauseInvocation(event)).isFalse();
  }

  @Test
  public void shouldPauseInvocation_whenResumableAndMatchingFunctionCallId_isTrue() {
    InvocationContext context =
        InvocationContext.builder()
            .sessionService(mockSessionService)
            .artifactService(mockArtifactService)
            .memoryService(mockMemoryService)
            .agent(mockAgent)
            .session(session)
            .resumabilityConfig(new ResumabilityConfig(true))
            .build();
    Event event =
        Event.builder()
            .longRunningToolIds(Optional.of(ImmutableSet.of("fc1")))
            .content(
                Content.builder()
                    .parts(
                        ImmutableList.of(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder().name("tool1").id("fc1").build())
                                .build()))
                    .build())
            .build();
    assertThat(context.shouldPauseInvocation(event)).isTrue();
  }
}
