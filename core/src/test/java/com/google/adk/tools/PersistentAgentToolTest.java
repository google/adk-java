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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class PersistentAgentToolTest {

  private InMemorySessionService sessionService;
  private InMemoryArtifactService artifactService;

  private static final Schema INPUT_SCHEMA =
      Schema.builder()
          .type("OBJECT")
          .properties(ImmutableMap.of("is_magic", Schema.builder().type("BOOLEAN").build()))
          .required(ImmutableList.of("is_magic"))
          .build();
  private static final Schema OUTPUT_SCHEMA =
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

  @Before
  public void setUp() {
    sessionService = new InMemorySessionService();
    artifactService = new InMemoryArtifactService();
  }

  @Test
  public void declaration_withInputSchema_returnsDeclarationWithSchema() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .name("agent name")
            .description("agent description")
            .inputSchema(INPUT_SCHEMA)
            .build();

    PersistentAgentTool agentTool =
        PersistentAgentTool.create(agent, "test-app", sessionService, artifactService);
    FunctionDeclaration declaration = agentTool.declaration().get();

    assertThat(declaration)
        .isEqualTo(
            FunctionDeclaration.builder()
                .name("agent name")
                .description("agent description")
                .parameters(INPUT_SCHEMA)
                .build());
  }

  @Test
  public void declaration_withoutInputSchema_returnsDeclarationWithRequestParameter() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .name("agent name")
            .description("agent description")
            .build();

    PersistentAgentTool agentTool =
        PersistentAgentTool.create(agent, "test-app", sessionService, artifactService);
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
  @SuppressWarnings("unchecked") // Unchecked cast for trace object
  public void runAsync_withoutSchema_returnsResultAndTrace() {
    LlmAgent agent =
        createTestAgentBuilder(
                createTestLlm(
                    LlmResponse.builder()
                        .content(Content.fromParts(Part.fromText("response")))
                        .build()))
            .name("agent name")
            .description("agent description")
            .build();

    PersistentAgentTool agentTool =
        PersistentAgentTool.create(agent, "test-app", sessionService, artifactService);
    ToolContext toolContext = createToolContext(agent);
    Map<String, Object> result =
        agentTool.runAsync(ImmutableMap.of("request", "magic"), toolContext).blockingGet();

    assertThat(result).containsEntry("result", "response");
    assertThat(result).containsKey("trace");
    List<Object> trace = (List<Object>) result.get("trace");
    assertThat(trace).hasSize(1);
    assertThat(trace.get(0)).isInstanceOf(Event.class);
    Event event = (Event) trace.get(0);
    assertThat(event.content().get().text()).isEqualTo("response");
  }

  @Test
  public void runAsync_withInputAndOutputSchema_successful() {
    String llmResponse = "{\"is_valid\": true, \"message\": \"success\"}";
    LlmAgent agent =
        createTestAgentBuilder(
                createTestLlm(
                    LlmResponse.builder()
                        .content(Content.fromParts(Part.fromText(llmResponse)))
                        .build()))
            .name("agent name")
            .description("agent description")
            .inputSchema(INPUT_SCHEMA)
            .outputSchema(OUTPUT_SCHEMA)
            .build();

    PersistentAgentTool agentTool =
        PersistentAgentTool.create(agent, "test-app", sessionService, artifactService);
    ToolContext toolContext = createToolContext(agent);
    Map<String, Object> result =
        agentTool.runAsync(ImmutableMap.of("is_magic", true), toolContext).blockingGet();

    assertThat(result).containsEntry("is_valid", true);
    assertThat(result).containsEntry("message", "success");
    assertThat(result).containsKey("trace");
  }

  @Test
  public void runAsync_withStateDeltaInResponse_propagatesStateDelta() throws Exception {
    AfterAgentCallback afterAgentCallback =
        (callbackContext) -> {
          callbackContext.state().put("test_key", "test_value");
          return Maybe.empty();
        };
    LlmAgent testAgent =
        createTestAgentBuilder(
                createTestLlm(
                    LlmResponse.builder()
                        .content(Content.fromParts(Part.fromText("test response")))
                        .build()))
            .name("agent name")
            .description("agent description")
            .afterAgentCallback(afterAgentCallback)
            .build();

    PersistentAgentTool agentTool =
        PersistentAgentTool.create(testAgent, "test-app", sessionService, artifactService);
    ToolContext toolContext = createToolContext(testAgent);
    assertThat(toolContext.state()).doesNotContainKey("test_key");
    Map<String, Object> unused =
        agentTool.runAsync(ImmutableMap.of("request", "magic"), toolContext).blockingGet();

    assertThat(toolContext.state()).containsEntry("test_key", "test_value");
  }

  private ToolContext createToolContext(BaseAgent agent) {
    Session session =
        sessionService
            .createSession("test-app", "test-user", (Map<String, Object>) null, "test-session")
            .blockingGet();
    return ToolContext.builder(
            InvocationContext.builder()
                .invocationId(InvocationContext.newInvocationContextId())
                .agent(agent)
                .session(session)
                .sessionService(sessionService)
                .build())
        .build();
  }
}
