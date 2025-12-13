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

package com.google.adk.tools;

import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AgentTool}. */
@RunWith(JUnit4.class)
public final class AgentToolTest {

  @Test
  public void declaration_withInputSchema_returnsDeclarationWithSchema() {
    Schema inputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("is_magic", Schema.builder().type("BOOLEAN").build()))
            .required(ImmutableList.of("is_magic"))
            .build();
    AgentTool agentTool =
        AgentTool.create(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .name("agent name")
                .description("agent description")
                .inputSchema(inputSchema)
                .build());

    FunctionDeclaration declaration = agentTool.declaration().get();

    assertThat(declaration)
        .isEqualTo(
            FunctionDeclaration.builder()
                .name("agent name")
                .description("agent description")
                .parameters(inputSchema)
                .build());
  }

  @Test
  public void declaration_withoutInputSchema_returnsDeclarationWithRequestParameter() {
    AgentTool agentTool =
        AgentTool.create(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .name("agent name")
                .description("agent description")
                .build());

    FunctionDeclaration declaration = agentTool.declaration().get();

    assertThat(declaration)
        .isEqualTo(
            FunctionDeclaration.builder()
                .name("agent name")
                .description("agent description")
                .parameters(
                    Schema.builder()
                        .type("OBJECT")
                        .properties(
                            ImmutableMap.of("request", Schema.builder().type("STRING").build()))
                        .required(ImmutableList.of("request"))
                        .build())
                .build());
  }

  @Test
  public void call_withInputSchema_invalidInput_throwsException() throws Exception {
    Schema inputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "is_magic",
                    Schema.builder().type("BOOLEAN").build(),
                    "name",
                    Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("is_magic", "name"))
            .build();
    LlmAgent testAgent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .name("agent name")
            .description("agent description")
            .inputSchema(inputSchema)
            .build();
    AgentTool agentTool = AgentTool.create(testAgent);
    ToolContext toolContext = createToolContext(testAgent);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    agentTool.runAsync(
                        ImmutableMap.of("is_magic", true, "name_invalid", "test_name"),
                        toolContext)))
        .hasMessageThat()
        .contains("Input arg: name_invalid does not match agent input schema");
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    agentTool.runAsync(
                        ImmutableMap.of("is_magic", "invalid_type", "name", "test_name"),
                        toolContext)))
        .hasMessageThat()
        .contains("Input arg: is_magic does not match agent input schema");
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> agentTool.runAsync(ImmutableMap.of("is_magic", true), toolContext)))
        .hasMessageThat()
        .contains("Input args does not contain required name");
  }

  @Test
  public void call_withOutputSchema_invalidOutput_throwsException() throws Exception {
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "is_valid",
                    Schema.builder().type("BOOLEAN").build(),
                    "message",
                    Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("is_valid", "message"))
            .build();
    LlmAgent testAgent =
        createTestAgentBuilder(
                createTestLlm(
                    LlmResponse.builder()
                        .content(
                            Content.fromParts(
                                Part.fromText(
                                    "{\"is_valid\": \"invalid type\", "
                                        + "\"message\": \"success\"}")))
                        .build()))
            .name("agent name")
            .description("agent description")
            .outputSchema(outputSchema)
            .build();
    AgentTool agentTool = AgentTool.create(testAgent);
    ToolContext toolContext = createToolContext(testAgent);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                agentTool.runAsync(ImmutableMap.of("request", "test"), toolContext).blockingGet());
    assertThat(exception)
        .hasMessageThat()
        .contains("Output arg: is_valid does not match agent output schema");
  }

  @Test
  public void call_withInputAndOutputSchema_successful() throws Exception {
    Schema inputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("is_magic", Schema.builder().type("BOOLEAN").build()))
            .required(ImmutableList.of("is_magic"))
            .build();
    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "is_valid",
                    Schema.builder().type("BOOLEAN").build(),
                    "message",
                    Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("is_valid", "message"))
            .build();
    LlmAgent testAgent =
        createTestAgentBuilder(
                createTestLlm(
                    LlmResponse.builder()
                        .content(
                            Content.fromParts(
                                Part.fromText(
                                    "{\"is_valid\": true, " + "\"message\": \"success\"}")))
                        .build()))
            .name("agent name")
            .description("agent description")
            .inputSchema(inputSchema)
            .outputSchema(outputSchema)
            .build();
    AgentTool agentTool = AgentTool.create(testAgent);
    ToolContext toolContext = createToolContext(testAgent);

    Map<String, Object> result =
        agentTool.runAsync(ImmutableMap.of("is_magic", true), toolContext).blockingGet();

    assertThat(result).containsExactly("is_valid", true, "message", "success");
  }

  @Test
  public void call_withoutSchema_returnsConcatenatedTextFromLastEvent() throws Exception {
    LlmAgent testAgent =
        createTestAgentBuilder(
                createTestLlm(
                    Flowable.just(
                        LlmResponse.builder()
                            .content(Content.fromParts(Part.fromText("Partial response")))
                            .partial(true)
                            .build()),
                    Flowable.just(
                        LlmResponse.builder()
                            .content(
                                Content.fromParts(
                                    Part.fromText("First text part. "),
                                    Part.fromText("Second text part.")))
                            .build())))
            .name("agent name")
            .description("agent description")
            .build();
    AgentTool agentTool = AgentTool.create(testAgent);
    ToolContext toolContext = createToolContext(testAgent);

    Map<String, Object> result =
        agentTool.runAsync(ImmutableMap.of("request", "magic"), toolContext).blockingGet();

    assertThat(result).containsExactly("result", "First text part. Second text part.");
  }

  @Test
  public void call_withThoughts_returnsOnlyNonThoughtText() throws Exception {
    TestLlm testLlm =
        createTestLlm(
            LlmResponse.builder()
                .content(
                    Content.builder()
                        .parts(
                            Part.fromText("Non-thought text 1. "),
                            Part.builder().text("This is a thought.").thought(true).build(),
                            Part.fromText("Non-thought text 2."))
                        .build())
                .build());
    LlmAgent testAgent =
        createTestAgentBuilder(testLlm).name("agent name").description("agent description").build();
    AgentTool agentTool = AgentTool.create(testAgent);
    ToolContext toolContext = createToolContext(testAgent);

    Map<String, Object> result =
        agentTool.runAsync(ImmutableMap.of("request", "test"), toolContext).blockingGet();

    assertThat(result).containsExactly("result", "Non-thought text 1. Non-thought text 2.");
  }

  @Test
  public void call_emptyModelResponse_returnsEmptyMap() throws Exception {
    LlmAgent testAgent =
        createTestAgentBuilder(
                createTestLlm(LlmResponse.builder().content(Content.builder().build()).build()))
            .name("agent name")
            .description("agent description")
            .build();
    AgentTool agentTool = AgentTool.create(testAgent);
    ToolContext toolContext = createToolContext(testAgent);

    Map<String, Object> result =
        agentTool.runAsync(ImmutableMap.of("request", "magic"), toolContext).blockingGet();

    assertThat(result).isEmpty();
  }

  @Test
  public void call_withInputSchema_argsAreSentToAgent() throws Exception {
    TestLlm testLlm =
        createTestLlm(
            LlmResponse.builder()
                .content(Content.fromParts(Part.fromText("test response")))
                .build());
    LlmAgent testAgent =
        createTestAgentBuilder(testLlm)
            .name("agent name")
            .description("agent description")
            .inputSchema(
                Schema.builder()
                    .type("OBJECT")
                    .properties(
                        ImmutableMap.of("is_magic", Schema.builder().type("BOOLEAN").build()))
                    .required(ImmutableList.of("is_magic"))
                    .build())
            .build();
    AgentTool agentTool = AgentTool.create(testAgent);
    ToolContext toolContext = createToolContext(testAgent);

    Map<String, Object> unused =
        agentTool.runAsync(ImmutableMap.of("is_magic", true), toolContext).blockingGet();

    assertThat(testLlm.getLastRequest().contents())
        .containsExactly(Content.fromParts(Part.fromText("{\"is_magic\":true}")));
  }

  @Test
  public void call_withoutInputSchema_requestIsSentToAgent() throws Exception {
    TestLlm testLlm =
        createTestLlm(
            LlmResponse.builder()
                .content(Content.fromParts(Part.fromText("test response")))
                .build());
    LlmAgent testAgent =
        createTestAgentBuilder(testLlm).name("agent name").description("agent description").build();
    AgentTool agentTool = AgentTool.create(testAgent);
    ToolContext toolContext = createToolContext(testAgent);

    Map<String, Object> unused =
        agentTool.runAsync(ImmutableMap.of("request", "magic"), toolContext).blockingGet();

    assertThat(testLlm.getLastRequest().contents())
        .containsExactly(Content.fromParts(Part.fromText("magic")));
  }

  @Test
  public void create_withServicesAndPlugins_initializesCorrectly() {
    LlmAgent testAgent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .name("agent name")
            .description("agent description")
            .build();

    AgentTool agentTool =
        AgentTool.create(
            testAgent,
            new InMemorySessionService(),
            new InMemoryArtifactService(),
            new InMemoryMemoryService(),
            ImmutableList.of());

    assertThat(agentTool).isNotNull();
    assertThat(agentTool.declaration()).isPresent();
  }

  @Test
  public void runAsync_withServicesAndPlugins_usesThem() {
    LlmAgent testAgent =
        createTestAgentBuilder(
                createTestLlm(
                    LlmResponse.builder()
                        .content(Content.fromParts(Part.fromText("Sub-agent executed")))
                        .build()))
            .name("sub-agent")
            .description("sub-agent description")
            .build();

    InMemorySessionService sessionService = new InMemorySessionService();
    TestPlugin testPlugin = new TestPlugin();

    AgentTool agentTool =
        AgentTool.create(
            testAgent,
            sessionService,
            new InMemoryArtifactService(),
            new InMemoryMemoryService(),
            ImmutableList.of(testPlugin));

    ToolContext toolContext = createToolContext(testAgent);

    Map<String, Object> result =
        agentTool.runAsync(ImmutableMap.of("request", "start"), toolContext).blockingGet();

    assertThat(result).containsEntry("result", "Sub-agent executed");

    assertThat(testPlugin.wasCalled.get()).isTrue();

    ListSessionsResponse sessionsResponse =
        sessionService.listSessions("sub-agent", "tmp-user").blockingGet();
    assertThat(sessionsResponse.sessions()).isNotEmpty();
  }

  private static class TestPlugin extends BasePlugin {
    final AtomicBoolean wasCalled = new AtomicBoolean(false);

    TestPlugin() {
      super("test-plugin");
    }

    @Override
    public Maybe<Content> onUserMessageCallback(
        InvocationContext invocationContext, Content userMessage) {
      wasCalled.set(true);
      return Maybe.empty();
    }
  }

  private static ToolContext createToolContext(LlmAgent agent) {
    return ToolContext.builder(
            new InvocationContext(
                /* sessionService= */ null,
                /* artifactService= */ null,
                /* memoryService= */ null,
                /* liveRequestQueue= */ Optional.empty(),
                /* branch= */ Optional.empty(),
                /* invocationId= */ InvocationContext.newInvocationContextId(),
                agent,
                Session.builder("123").build(),
                /* userContent= */ Optional.empty(),
                /* runConfig= */ null,
                /* endInvocation= */ false))
        .build();
  }
}
