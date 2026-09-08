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

import static com.google.adk.flows.llmflows.Functions.REQUEST_CONFIRMATION_FUNCTION_CALL_NAME;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.util.Arrays.stream;

import com.google.adk.agents.CallerIdentity;
import com.google.adk.agents.ConfirmationPolicy;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.models.LlmRequest;
import com.google.adk.plugins.PluginManager;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class RequestConfirmationLlmRequestProcessorTest {
  private static final String AGENT_NAME = "test agent";
  private static final String ECHO_TOOL_NAME = "echo_tool";
  private static final String ORIGINAL_FUNCTION_CALL_ID = "original_fc_id";
  private static final ImmutableMap<String, Object> ORIGINAL_FUNCTION_CALL_ARGS =
      ImmutableMap.of("say", "hello");
  private static final String FUNCTION_CALL_ID = "fc_id";
  private static final ImmutableMap<String, Object> ARGS =
      ImmutableMap.of(
          "originalFunctionCall",
          ImmutableMap.of( // original function call as a map
              "id",
              Optional.of("original_fc_id"),
              "name",
              Optional.of(ECHO_TOOL_NAME),
              "args",
              Optional.of(ORIGINAL_FUNCTION_CALL_ARGS)));
  private static final FunctionCall FUNCTION_CALL =
      FunctionCall.builder()
          .id(FUNCTION_CALL_ID)
          .name(REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
          .args(ARGS)
          .build();
  private static final InMemorySessionService sessionService = new InMemorySessionService();

  /** The tool call the agent itself emitted, which the confirmation later resumes. */
  private static final Event ORIGINAL_FUNCTION_CALL_EVENT =
      functionCallEvent(
          AGENT_NAME,
          FunctionCall.builder()
              .id(ORIGINAL_FUNCTION_CALL_ID)
              .name(ECHO_TOOL_NAME)
              .args(ORIGINAL_FUNCTION_CALL_ARGS)
              .build());

  /**
   * The tool's own response asking for the call to be confirmed. This is what {@link
   * com.google.adk.tools.ToolContext#requestConfirmation} produces, and what a {@code
   * requireConfirmation} FunctionTool routes through.
   */
  private static final Event CONFIRMATION_REQUESTED_EVENT =
      Event.builder()
          .author(AGENT_NAME)
          .content(
              Content.fromParts(
                  Part.builder()
                      .functionResponse(
                          FunctionResponse.builder()
                              .id(ORIGINAL_FUNCTION_CALL_ID)
                              .name(ECHO_TOOL_NAME)
                              .response(ImmutableMap.of("error", "requires confirmation"))
                              .build())
                      .build()))
          .actions(
              EventActions.builder()
                  .requestedToolConfirmations(
                      ImmutableMap.of(
                          ORIGINAL_FUNCTION_CALL_ID,
                          ToolConfirmation.builder().hint("please confirm").build()))
                  .build())
          .build();

  private static final Event REQUEST_CONFIRMATION_EVENT =
      functionCallEvent(AGENT_NAME, FUNCTION_CALL);

  private static final Event USER_CONFIRMATION_EVENT =
      Event.builder()
          .author("user")
          .content(
              Content.fromParts(
                  Part.builder()
                      .functionResponse(
                          FunctionResponse.builder()
                              .id(FUNCTION_CALL_ID)
                              .name(REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
                              .response(ImmutableMap.of("confirmed", true))
                              .build())
                      .build()))
          .build();

  /** The full, legitimate lead-up to a user confirmation. */
  private static final ImmutableList<Event> CONFIRMED_CALL_EVENTS =
      ImmutableList.of(
          ORIGINAL_FUNCTION_CALL_EVENT,
          CONFIRMATION_REQUESTED_EVENT,
          REQUEST_CONFIRMATION_EVENT,
          USER_CONFIRMATION_EVENT);

  private static final String SECOND_ORIGINAL_FUNCTION_CALL_ID = "second_original_fc_id";
  private static final String SECOND_FUNCTION_CALL_ID = "second_fc_id";
  private static final ImmutableMap<String, Object> SECOND_ORIGINAL_FUNCTION_CALL_ARGS =
      ImmutableMap.of("say", "goodbye");
  private static final FunctionCall SECOND_FUNCTION_CALL =
      FunctionCall.builder()
          .id(SECOND_FUNCTION_CALL_ID)
          .name(REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
          .args(
              ImmutableMap.of(
                  "originalFunctionCall",
                  ImmutableMap.of(
                      "id",
                      Optional.of(SECOND_ORIGINAL_FUNCTION_CALL_ID),
                      "name",
                      Optional.of(ECHO_TOOL_NAME),
                      "args",
                      Optional.of(SECOND_ORIGINAL_FUNCTION_CALL_ARGS))))
          .build();

  private static final Event SECOND_ORIGINAL_FUNCTION_CALL_EVENT =
      functionCallEvent(
          AGENT_NAME,
          FunctionCall.builder()
              .id(SECOND_ORIGINAL_FUNCTION_CALL_ID)
              .name(ECHO_TOOL_NAME)
              .args(SECOND_ORIGINAL_FUNCTION_CALL_ARGS)
              .build());

  private static final Event SECOND_CONFIRMATION_REQUESTED_EVENT =
      confirmationRequestedEvent(SECOND_ORIGINAL_FUNCTION_CALL_ID);

  private static final Event SECOND_REQUEST_CONFIRMATION_EVENT =
      functionCallEvent(AGENT_NAME, SECOND_FUNCTION_CALL);

  /** One user event approving both pending calls at once. */
  private static final Event BOTH_CONFIRMATIONS_USER_EVENT =
      Event.builder()
          .author("user")
          .content(
              Content.fromParts(
                  confirmationResponsePart(FUNCTION_CALL_ID),
                  confirmationResponsePart(SECOND_FUNCTION_CALL_ID)))
          .build();

  private static final RequestConfirmationLlmRequestProcessor processor =
      new RequestConfirmationLlmRequestProcessor();

  @Test
  public void runAsync_withConfirmation_callsOriginalFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(CONFIRMED_CALL_EVENTS).build();

    InvocationContext context = buildInvocationContext(agent, session);

    RequestProcessor.RequestProcessingResult result =
        processor.processRequest(context, LlmRequest.builder().build()).blockingGet();

    assertThat(result).isNotNull();
    assertThat(result.events()).hasSize(1);
    Event event = result.events().iterator().next();
    assertThat(event.functionResponses()).hasSize(1);
    FunctionResponse fr = event.functionResponses().get(0);
    assertThat(fr.id()).hasValue(ORIGINAL_FUNCTION_CALL_ID);
    assertThat(fr.name()).hasValue(ECHO_TOOL_NAME);
    assertThat(fr.response()).hasValue(ImmutableMap.of("result", ORIGINAL_FUNCTION_CALL_ARGS));
  }

  @Test
  public void runAsync_withConfirmationAndToolAlreadyCalled_doesNotCallOriginalFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    // Authored by the agent, matching Functions.java:740 which builds real tool response events
    // with invocationContext.agent().name().
    Event toolResponseEvent =
        Event.builder()
            .author(AGENT_NAME)
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id(ORIGINAL_FUNCTION_CALL_ID)
                                .name(ECHO_TOOL_NAME)
                                .response(ImmutableMap.of("result", ORIGINAL_FUNCTION_CALL_ARGS))
                                .build())
                        .build()))
            .build();
    Session session =
        Session.builder("session_id")
            .events(
                ImmutableList.<Event>builder()
                    .addAll(CONFIRMED_CALL_EVENTS)
                    .add(toolResponseEvent)
                    .build())
            .build();

    InvocationContext context = buildInvocationContext(agent, session);

    RequestProcessor.RequestProcessingResult result =
        processor.processRequest(context, LlmRequest.builder().build()).blockingGet();

    assertThat(result).isNotNull();
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void runAsync_noEvents_empty() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(ImmutableList.of()).build();

    assertThat(
            processor
                .processRequest(
                    buildInvocationContext(agent, session), LlmRequest.builder().build())
                .blockingGet()
                .events())
        .isEmpty();
  }

  @Test
  public void runAsync_noUserConfirmationEvent_empty() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id").events(ImmutableList.of(REQUEST_CONFIRMATION_EVENT)).build();

    assertThat(
            processor
                .processRequest(
                    buildInvocationContext(agent, session), LlmRequest.builder().build())
                .blockingGet()
                .events())
        .isEmpty();
  }

  @Test
  public void runAsync_peerReusesPendingCallId_stillCallsOriginalFunction() {
    // A peer must not be able to veto a pending confirmation by reusing the ID of a call this
    // agent is waiting on. The history index resolves collisions last-wins, so without author
    // precedence the peer's entry shadows the agent's, the author check rejects the legitimate
    // confirmation, and the user's approval silently does nothing.
    LlmAgent agent = createAgentWithEchoTool();
    Event peerNoise =
        functionCallEvent(
            "remote_a2a_agent",
            FunctionCall.builder()
                .id(ORIGINAL_FUNCTION_CALL_ID)
                .name("peer_noise")
                .args(ImmutableMap.of("x", "y"))
                .build());
    Session session =
        Session.builder("session_id")
            .events(
                ImmutableList.of(
                    ORIGINAL_FUNCTION_CALL_EVENT,
                    CONFIRMATION_REQUESTED_EVENT,
                    REQUEST_CONFIRMATION_EVENT,
                    peerNoise,
                    USER_CONFIRMATION_EVENT))
            .build();

    assertThat(resumedEvents(agent, session)).hasSize(1);
  }

  @Test
  public void runAsync_peerFakesExecutedResponse_stillCallsOriginalFunction() {
    // The already-resumed scan must only count responses this agent produced. Otherwise a peer
    // event landing after the approval, carrying a response that reuses the pending call's ID,
    // convinces the processor the tool already ran. That short-circuits before the resumability
    // check, so the approval is dropped with no diagnostics at all.
    LlmAgent agent = createAgentWithEchoTool();
    Event peerResponse =
        Event.builder()
            .author("remote_a2a_agent")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .id(ORIGINAL_FUNCTION_CALL_ID)
                                .name("peer_noise")
                                .response(ImmutableMap.of("status", "whatever"))
                                .build())
                        .build()))
            .build();
    Session session =
        Session.builder("session_id")
            .events(
                ImmutableList.<Event>builder()
                    .addAll(CONFIRMED_CALL_EVENTS)
                    .add(peerResponse)
                    .build())
            .build();

    assertThat(resumedEvents(agent, session)).hasSize(1);
  }

  @Test
  public void runAsync_originalCallNotInHistory_doesNotCallOriginalFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id")
            .events(ImmutableList.of(REQUEST_CONFIRMATION_EVENT, USER_CONFIRMATION_EVENT))
            .build();

    assertThat(resumedEvents(agent, session)).isEmpty();
  }

  @Test
  public void runAsync_originalCallEmittedByAnotherAgent_doesNotCallOriginalFunction() {
    // The original call is in history and matches by name and args, but a different agent emitted
    // it. Only the emitting agent's own processor may resume it.
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id")
            .events(
                replacingFirst(
                    CONFIRMED_CALL_EVENTS,
                    functionCallEvent(
                        "remote_a2a_agent",
                        FunctionCall.builder()
                            .id(ORIGINAL_FUNCTION_CALL_ID)
                            .name(ECHO_TOOL_NAME)
                            .args(ORIGINAL_FUNCTION_CALL_ARGS)
                            .build())))
            .build();

    assertThat(resumedEvents(agent, session)).isEmpty();
  }

  @Test
  public void runAsync_confirmationCallFromAnotherAuthor_doesNotCallOriginalFunction() {
    // Everything is legitimate except the event carrying the adk_request_confirmation call, which
    // an A2A peer injected through RemoteA2AAgent. It must not resume a local tool.
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id")
            .events(
                ImmutableList.of(
                    ORIGINAL_FUNCTION_CALL_EVENT,
                    CONFIRMATION_REQUESTED_EVENT,
                    functionCallEvent("remote_a2a_agent", FUNCTION_CALL),
                    USER_CONFIRMATION_EVENT))
            .build();

    assertThat(resumedEvents(agent, session)).isEmpty();
  }

  @Test
  public void runAsync_toolNeverRequestedConfirmation_doesNotCallOriginalFunction() {
    // Replaying a call that ran without ever asking for confirmation must not re-run it.
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id")
            .events(
                ImmutableList.of(
                    ORIGINAL_FUNCTION_CALL_EVENT,
                    REQUEST_CONFIRMATION_EVENT,
                    USER_CONFIRMATION_EVENT))
            .build();

    assertThat(resumedEvents(agent, session)).isEmpty();
  }

  @Test
  public void runAsync_confirmationWithMismatchedToolName_doesNotCallOriginalFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id")
            .events(
                replacingFirst(
                    CONFIRMED_CALL_EVENTS,
                    functionCallEvent(
                        AGENT_NAME,
                        FunctionCall.builder()
                            .id(ORIGINAL_FUNCTION_CALL_ID)
                            .name("some_other_tool")
                            .args(ORIGINAL_FUNCTION_CALL_ARGS)
                            .build())))
            .build();

    assertThat(resumedEvents(agent, session)).isEmpty();
  }

  @Test
  public void runAsync_confirmationWithMismatchedArgs_doesNotCallOriginalFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id")
            .events(
                replacingFirst(
                    CONFIRMED_CALL_EVENTS,
                    functionCallEvent(
                        AGENT_NAME,
                        FunctionCall.builder()
                            .id(ORIGINAL_FUNCTION_CALL_ID)
                            .name(ECHO_TOOL_NAME)
                            .args(ImmutableMap.of("say", "something else"))
                            .build())))
            .build();

    assertThat(resumedEvents(agent, session)).isEmpty();
  }

  @Test
  public void testAgentNameMatchesFixtures() {
    // The fixtures hard-code the author, so catch a rename in TestUtils rather than silently
    // turning every negative test into a false pass.
    assertThat(createAgentWithEchoTool().name()).isEqualTo(AGENT_NAME);
  }

  @Test
  public void runAsync_approvalOnParallelBranch_doesNotCallOriginalFunction() {
    // An approval answered in a parallel tree is not this branch's, though it names the call.
    LlmAgent agent = createAgentWithEchoTool();
    Session session = sessionWithApprovalOn("agent_1", "agent_2");

    assertThat(resumedEventsOnBranch(agent, session, "agent_1")).isEmpty();
  }

  @Test
  public void runAsync_approvalOnSubBranch_callsOriginalFunction() {
    // The user may answer on a descendant sub-branch, so scoping must not break the normal path.
    LlmAgent agent = createAgentWithEchoTool();
    Session session = sessionWithApprovalOn("agent_1", "agent_1.child");

    ImmutableList<Event> resumed = resumedEventsOnBranch(agent, session, "agent_1");

    assertThat(resumed).hasSize(1);
    FunctionResponse response = resumed.get(0).functionResponses().get(0);
    assertThat(response.id()).hasValue(ORIGINAL_FUNCTION_CALL_ID);
    assertThat(response.name()).hasValue(ECHO_TOOL_NAME);
  }

  private static ImmutableList<Event> resumedEvents(LlmAgent agent, Session session) {
    return ImmutableList.copyOf(
        processor
            .processRequest(buildInvocationContext(agent, session), LlmRequest.builder().build())
            .blockingGet()
            .events());
  }

  /** Returns {@code events} with its first element swapped for {@code replacement}. */
  private static ImmutableList<Event> replacingFirst(
      ImmutableList<Event> events, Event replacement) {
    return ImmutableList.<Event>builder()
        .add(replacement)
        .addAll(events.subList(1, events.size()))
        .build();
  }

  private static Event functionCallEvent(String author, FunctionCall... functionCalls) {
    return Event.builder()
        .author(author)
        .content(
            Content.fromParts(
                stream(functionCalls)
                    .map(functionCall -> Part.builder().functionCall(functionCall).build())
                    .toArray(Part[]::new)))
        .build();
  }

  /** The tool's own response asking for the call with {@code originalCallId} to be confirmed. */
  private static Event confirmationRequestedEvent(String originalCallId) {
    return Event.builder()
        .author(AGENT_NAME)
        .content(
            Content.fromParts(
                Part.builder()
                    .functionResponse(
                        FunctionResponse.builder()
                            .id(originalCallId)
                            .name(ECHO_TOOL_NAME)
                            .response(ImmutableMap.of("error", "requires confirmation"))
                            .build())
                    .build()))
        .actions(
            EventActions.builder()
                .requestedToolConfirmations(
                    ImmutableMap.of(
                        originalCallId, ToolConfirmation.builder().hint("please confirm").build()))
                .build())
        .build();
  }

  /** A user's approval of the confirmation call {@code confirmationCallId}. */
  private static Part confirmationResponsePart(String confirmationCallId) {
    return Part.builder()
        .functionResponse(
            FunctionResponse.builder()
                .id(confirmationCallId)
                .name(REQUEST_CONFIRMATION_FUNCTION_CALL_NAME)
                .response(ImmutableMap.of("confirmed", true))
                .build())
        .build();
  }

  @Test
  public void runAsync_unauthenticatedSenderUnderAuthenticatedOnlyPolicy_doesNotCallFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(CONFIRMED_CALL_EVENTS).build();

    assertRefusal(
        resumedEventsWithPolicy(
            agent,
            session,
            RunConfig.builder().callerIdentity(CallerIdentity.unauthenticated()).build(),
            ConfirmationPolicy.AUTHENTICATED_ONLY));
  }

  @Test
  public void runAsync_authenticatedSenderUnderAuthenticatedOnlyPolicy_callsOriginalFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(CONFIRMED_CALL_EVENTS).build();

    ImmutableList<Event> resumed =
        resumedEventsWithPolicy(
            agent,
            session,
            RunConfig.builder().callerIdentity(CallerIdentity.authenticatedAs("operator")).build(),
            ConfirmationPolicy.AUTHENTICATED_ONLY);

    assertThat(resumed).hasSize(1);
    FunctionResponse fr = resumed.get(0).functionResponses().get(0);
    assertThat(fr.id()).hasValue(ORIGINAL_FUNCTION_CALL_ID);
    assertThat(fr.response()).hasValue(ImmutableMap.of("result", ORIGINAL_FUNCTION_CALL_ARGS));
  }

  @Test
  public void runAsync_defaultPolicyAndNoTransportIdentity_stillCallsOriginalFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(CONFIRMED_CALL_EVENTS).build();

    ImmutableList<Event> resumed =
        resumedEventsWithPolicy(
            agent, session, RunConfig.builder().build(), ConfirmationPolicy.REJECT_UNAUTHENTICATED);

    assertThat(resumed).hasSize(1);
    assertThat(resumed.get(0).functionResponses().get(0).response())
        .hasValue(ImmutableMap.of("result", ORIGINAL_FUNCTION_CALL_ARGS));
  }

  @Test
  public void runAsync_defaultPolicyAndUnauthenticatedSender_doesNotCallFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(CONFIRMED_CALL_EVENTS).build();

    assertRefusal(
        resumedEventsWithPolicy(
            agent,
            session,
            RunConfig.builder().callerIdentity(CallerIdentity.unauthenticated()).build(),
            ConfirmationPolicy.REJECT_UNAUTHENTICATED));
  }

  @Test
  public void runAsync_noCallerIdentityUnderAuthenticatedOnlyPolicy_doesNotCallFunction() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(CONFIRMED_CALL_EVENTS).build();

    assertRefusal(
        resumedEventsWithPolicy(
            agent, session, RunConfig.builder().build(), ConfirmationPolicy.AUTHENTICATED_ONLY));
  }

  @Test
  public void runAsync_refusedConfirmation_respondsWithAnError() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(CONFIRMED_CALL_EVENTS).build();

    ImmutableList<Event> events =
        resumedEventsWithPolicy(
            agent,
            session,
            RunConfig.builder().callerIdentity(CallerIdentity.unauthenticated()).build(),
            ConfirmationPolicy.REJECT_UNAUTHENTICATED);

    assertThat(events).hasSize(1);
    assertThat(events.get(0).author()).isEqualTo(AGENT_NAME);
    FunctionResponse response = events.get(0).functionResponses().get(0);
    assertThat(response.id()).hasValue(ORIGINAL_FUNCTION_CALL_ID);
    assertThat(response.name()).hasValue(ECHO_TOOL_NAME);
    assertThat(response.response().get()).containsKey("error");
    assertThat((String) response.response().get().get("error")).doesNotContain("hello");
  }

  @Test
  public void runAsync_refusedConfirmationAlreadyAnswered_isNotReExamined() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session = Session.builder("session_id").events(CONFIRMED_CALL_EVENTS).build();
    RunConfig runConfig =
        RunConfig.builder().callerIdentity(CallerIdentity.unauthenticated()).build();

    ImmutableList<Event> firstPass =
        resumedEventsWithPolicy(
            agent, session, runConfig, ConfirmationPolicy.REJECT_UNAUTHENTICATED);
    session.events().addAll(firstPass);
    ImmutableList<Event> secondPass =
        resumedEventsWithPolicy(
            agent, session, runConfig, ConfirmationPolicy.REJECT_UNAUTHENTICATED);

    assertThat(firstPass).hasSize(1);
    assertThat(secondPass).isEmpty();
  }

  @Test
  public void runAsync_multipleConfirmationsRefusedInOnePass_reportsThemInOneEvent() {
    LlmAgent agent = createAgentWithEchoTool();
    Session session =
        Session.builder("session_id")
            .events(
                ImmutableList.of(
                    SECOND_ORIGINAL_FUNCTION_CALL_EVENT,
                    SECOND_CONFIRMATION_REQUESTED_EVENT,
                    SECOND_REQUEST_CONFIRMATION_EVENT,
                    ORIGINAL_FUNCTION_CALL_EVENT,
                    CONFIRMATION_REQUESTED_EVENT,
                    REQUEST_CONFIRMATION_EVENT,
                    BOTH_CONFIRMATIONS_USER_EVENT))
            .build();

    ImmutableList<Event> events =
        resumedEventsWithPolicy(
            agent,
            session,
            RunConfig.builder().callerIdentity(CallerIdentity.unauthenticated()).build(),
            ConfirmationPolicy.REJECT_UNAUTHENTICATED);

    assertThat(events).hasSize(1);
    ImmutableList<FunctionResponse> responses = events.get(0).functionResponses();
    ImmutableList<String> refusedIds =
        responses.stream().map(fr -> fr.id().orElse("")).collect(toImmutableList());
    assertThat(refusedIds)
        .containsExactly(ORIGINAL_FUNCTION_CALL_ID, SECOND_ORIGINAL_FUNCTION_CALL_ID);
    responses.forEach(fr -> assertThat(fr.response().get()).containsKey("error"));
  }

  private static void assertRefusal(ImmutableList<Event> events) {
    assertThat(events).hasSize(1);
    FunctionResponse response = events.get(0).functionResponses().get(0);
    assertThat(response.id()).hasValue(ORIGINAL_FUNCTION_CALL_ID);
    assertThat(response.response().get()).containsKey("error");
  }

  private static ImmutableList<Event> resumedEventsWithPolicy(
      LlmAgent agent, Session session, RunConfig runConfig, ConfirmationPolicy approver) {
    InvocationContext context =
        InvocationContext.builder()
            .pluginManager(new PluginManager())
            .invocationId(InvocationContext.newInvocationContextId())
            .agent(agent)
            .session(session)
            .sessionService(sessionService)
            .runConfig(runConfig)
            .confirmationPolicy(approver)
            .build();
    return ImmutableList.copyOf(
        processor.processRequest(context, LlmRequest.builder().build()).blockingGet().events());
  }

  private static InvocationContext buildInvocationContext(LlmAgent agent, Session session) {
    return InvocationContext.builder()
        .pluginManager(new PluginManager())
        .invocationId(InvocationContext.newInvocationContextId())
        .agent(agent)
        .session(session)
        .sessionService(sessionService)
        .build();
  }

  private static InvocationContext buildInvocationContext(
      LlmAgent agent, Session session, String branch) {
    return InvocationContext.builder()
        .pluginManager(new PluginManager())
        .invocationId(InvocationContext.newInvocationContextId())
        .branch(branch)
        .agent(agent)
        .session(session)
        .sessionService(sessionService)
        .build();
  }

  /**
   * Returns the legitimate lead-up with the agent's events on {@code agentBranch} and the user's
   * approval on {@code approvalBranch}.
   */
  private static Session sessionWithApprovalOn(String agentBranch, String approvalBranch) {
    ImmutableList<Event> events =
        CONFIRMED_CALL_EVENTS.stream()
            .map(
                event ->
                    event.toBuilder()
                        .branch(event.author().equals("user") ? approvalBranch : agentBranch)
                        .build())
            .collect(toImmutableList());
    return Session.builder("session_id").events(events).build();
  }

  private static ImmutableList<Event> resumedEventsOnBranch(
      LlmAgent agent, Session session, String branch) {
    return ImmutableList.copyOf(
        processor
            .processRequest(
                buildInvocationContext(agent, session, branch), LlmRequest.builder().build())
            .blockingGet()
            .events());
  }

  private static LlmAgent createAgentWithEchoTool() {
    Content contentWithFunctionCall =
        Content.fromParts(
            Part.fromText("text"),
            Part.fromFunctionCall(ECHO_TOOL_NAME, ImmutableMap.of("arg", "value")));
    Content unreachableContent = Content.fromParts(Part.fromText("This should never be returned."));
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(contentWithFunctionCall), createLlmResponse(unreachableContent));
    return createTestAgentBuilder(testLlm).tools(new EchoTool()).maxSteps(2).build();
  }
}
