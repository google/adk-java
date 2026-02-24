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

package com.google.adk.flows.llmflows;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Instruction;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class InstructionsTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  private Instructions instructionsProcessor;
  private LlmRequest initialRequest;

  @Mock private BaseArtifactService mockArtifactService;
  private InMemorySessionService sessionService;

  @Before
  public void setUp() {
    instructionsProcessor = new Instructions();
    initialRequest = LlmRequest.builder().build();
    sessionService = new InMemorySessionService();
  }

  private InvocationContext createContext(BaseAgent agent, Session session) {
    return InvocationContext.builder()
        .sessionService(sessionService)
        .artifactService(mockArtifactService)
        .invocationId("test-invocation-id")
        .agent(agent)
        .session(session)
        .build();
  }

  private Session createSession() {
    return Session.builder("test-session-id")
        .appName("test-app")
        .userId("test-user")
        .state(new ConcurrentHashMap<>())
        .build();
  }

  @Test
  public void processRequest_noInstructions_returnsOriginalRequest() {
    LlmAgent agent = LlmAgent.builder().name("agent").build();
    InvocationContext context = createContext(agent, createSession());

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions()).isEmpty();
  }

  @Test
  public void processRequest_agentInstructionString_noPlaceholders_appendsInstruction() {
    String instruction = "Agent instruction text.";
    LlmAgent agent = LlmAgent.builder().name("agent").instruction(instruction).build();
    InvocationContext context = createContext(agent, createSession());

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions()).containsExactly(instruction);
  }

  @Test
  public void
      processRequest_agentInstructionString_withStatePlaceholder_appendsResolvedInstruction() {
    Session session = createSession();
    session.state().put("name", "TestBot");
    LlmAgent agent = LlmAgent.builder().name("agent").instruction("My name is {name}.").build();
    InvocationContext context = createContext(agent, session);

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions())
        .containsExactly("My name is TestBot.");
  }

  @Test
  public void
      processRequest_agentInstructionString_withArtifactPlaceholder_appendsResolvedInstruction() {
    Session session = createSession();
    Part artifactPart = Part.fromText("Artifact content");
    when(mockArtifactService.loadArtifact(
            eq(session.appName()),
            eq(session.userId()),
            eq(session.id()),
            eq("file.txt"),
            eq(Optional.empty())))
        .thenReturn(Maybe.just(artifactPart));
    LlmAgent agent =
        LlmAgent.builder().name("agent").instruction("File content: {artifact.file.txt}").build();
    InvocationContext context = createContext(agent, session);

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions())
        .containsExactly("File content: " + artifactPart.toJson());
  }

  @Test
  public void
      processRequest_agentInstructionString_withOptionalPlaceholderMissing_appendsResolvedInstruction() {
    LlmAgent agent = LlmAgent.builder().name("agent").instruction("Value: {missing_var?}").build();
    InvocationContext context = createContext(agent, createSession());

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions()).containsExactly("Value: ");
  }

  @Test
  public void
      processRequest_agentInstructionString_withMissingNonOptionalPlaceholder_throwsException() {
    LlmAgent agent = LlmAgent.builder().name("agent").instruction("Value: {missing_var}").build();
    InvocationContext context = createContext(agent, createSession());

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> instructionsProcessor.processRequest(context, initialRequest).blockingGet());
    assertThat(exception).hasMessageThat().isEqualTo("Context variable not found: `missing_var`.");
  }

  @Test
  public void processRequest_agentInstructionProvider_appendsInstruction() {
    String instructionFromProvider = "Instruction from provider.";
    Instruction provider = new Instruction.Provider(ctx -> Single.just(instructionFromProvider));

    LlmAgent agent = LlmAgent.builder().name("agent").instruction(provider).build();
    InvocationContext context = createContext(agent, createSession());

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions())
        .containsExactly(instructionFromProvider);
  }

  @Test
  public void processRequest_agentInstructionProvider_bypassesStateInjection() {
    Session session = createSession();
    session.state().put("name", "TestBot");
    // This would throw an error if state injection was attempted.
    String instructionFromProvider = "My name is {name}. But my friend is {friend_name}.";
    Instruction provider = new Instruction.Provider(ctx -> Single.just(instructionFromProvider));

    LlmAgent agent = LlmAgent.builder().name("agent").instruction(provider).build();
    InvocationContext context = createContext(agent, createSession());

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions())
        .containsExactly(instructionFromProvider);
  }

  @Test
  public void
      processRequest_agentInstructionString_withInvalidPlaceholderSyntax_appendsInstructionWithLiteral() {
    LlmAgent agent =
        LlmAgent.builder().name("agent").instruction("Value: { invalid name } and {var.}").build();
    InvocationContext context = createContext(agent, createSession());

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions())
        .containsExactly("Value: { invalid name } and {var.}");
  }

  @Test
  public void
      processRequest_agentInstructionString_withInvalidStateName_appendsInstructionWithLiteral() {
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .instruction("Value: {app:invalid-name} and {:value} and {app:}")
            .build();
    InvocationContext context = createContext(agent, createSession());

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions())
        .containsExactly("Value: {app:invalid-name} and {:value} and {app:}");
  }

  @Test
  public void processRequest_agentWithGlobalInstruction_isAppendedToRequest() {
    LlmAgent agent =
        LlmAgent.builder().name("agent").globalInstruction("Global instruction.").build();
    InvocationContext context = createContext(agent, createSession());

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions())
        .containsExactly("Global instruction.");
  }

  @Test
  public void processRequest_agentInstructionAndGlobalInstruction_bothAreAppendedToRequest() {
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .globalInstruction("Global instruction.")
            .instruction("Agent instruction.")
            .build();
    InvocationContext context = createContext(agent, createSession());

    RequestProcessor.RequestProcessingResult result =
        instructionsProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions())
        .containsExactly("Global instruction.\n\nAgent instruction.");
  }
}
