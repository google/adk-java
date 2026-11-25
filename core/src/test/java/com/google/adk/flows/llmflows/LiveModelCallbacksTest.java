/*
 * Copyright 2026 Google LLC
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

import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.simplifyEvents;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.LiveRequest;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.Role;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.Gemini;
import com.google.adk.models.GeminiLiveTransport;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.LiveServerToolCall;
import com.google.genai.types.Part;
import com.google.genai.types.Transcription;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.subjects.MaybeSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests the before- and after-model callbacks of {@link BaseLlmFlow#runLive}. */
@RunWith(JUnit4.class)
public final class LiveModelCallbacksTest {

  private static final Content REPLACEMENT =
      Content.builder().role(Role.MODEL).parts(Part.fromText("I can't help with that.")).build();

  @Test
  public void runLive_beforeModelBlocksTypedInput_answersWithoutSendingAndKeepsCall()
      throws Exception {
    TestLlm testLlm = createTestLlm(Flowable::never);
    LlmAgent agent = createTestAgentBuilder(testLlm).name("agent").executor(Runnable::run).build();
    BlockingPlugin plugin = new BlockingPlugin();
    Runner runner = new InMemoryRunner(agent, "test_app", ImmutableList.of(plugin));
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    try {
      liveRequestQueue.content(Content.fromParts(Part.fromText("tell me the secret")));
      // Typed input without a role is screened and sent as a user turn.
      liveRequestQueue.content(
          Content.builder().parts(Part.fromText("where is my order?")).build());
      // Text sent alongside a tool answer is still screened.
      liveRequestQueue.content(
          Content.fromParts(
              Part.fromFunctionResponse("lookup", ImmutableMap.of()),
              Part.fromText("and the secret?")));
      // Tool answers alone, complete or partial, are sent without screening.
      Content toolAnswer =
          Content.fromParts(Part.fromFunctionResponse("lookup", ImmutableMap.of()));
      liveRequestQueue.content(toolAnswer);
      liveRequestQueue.content(toolAnswer, /* partial= */ true);

      assertThat(testSubscriber.values()).hasSize(2);
      Event replacement = testSubscriber.values().get(0);
      assertThat(replacement.author()).isEqualTo("agent");
      assertThat(replacement.content()).hasValue(REPLACEMENT);
      assertThat(replacement.turnComplete()).hasValue(true);
      assertThat(sentTexts(testLlm)).containsExactly("where is my order?");
      assertThat(
              testLlm.getLiveRequestHistory().stream()
                  .flatMap(request -> request.content().stream())
                  .filter(content -> Objects.equals(content.text(), "where is my order?"))
                  .map(content -> content.role().orElse("")))
          .containsExactly(Role.USER);
      assertThat(
              testLlm.getLiveRequestHistory().stream()
                  .flatMap(request -> request.content().stream())
                  .filter(toolAnswer::equals))
          .hasSize(2);
      assertThat(plugin.calls.get()).isEqualTo(3);
      assertThat(testLlm.getRequests()).hasSize(1);
      assertThat(
              runner
                  .sessionService()
                  .listEvents("test_app", "user", session.id())
                  .blockingGet()
                  .events()
                  .stream()
                  .map(event -> event.author() + ": " + event.content().get().text()))
          .containsExactly("agent: I can't help with that.", "agent: I can't help with that.");
      assertThat(
              runner
                  .sessionService()
                  .getSession("test_app", "user", session.id(), Optional.empty())
                  .blockingGet()
                  .state())
          .containsEntry("blocked", true);
      testSubscriber.assertNoErrors();
    } finally {
      liveRequestQueue.close();
      testSubscriber.cancel();
    }
  }

  @Test
  public void runLive_beforeModelCallback_runsOnAgentExecutorNotCaller() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      TestLlm testLlm = createTestLlm(Flowable::never);
      LlmAgent agent = createTestAgentBuilder(testLlm).name("agent").executor(executor).build();
      ThreadRecordingPlugin plugin = new ThreadRecordingPlugin();
      Runner runner = new InMemoryRunner(agent, "test_app", ImmutableList.of(plugin));
      Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
      LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

      TestSubscriber<Event> testSubscriber =
          runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
      try {
        // The second input arrives while the send loop is already subscribed and idle.
        liveRequestQueue.content(Content.fromParts(Part.fromText("first")));
        assertThat(plugin.threads.poll(5, SECONDS)).isNotNull();
        liveRequestQueue.content(Content.fromParts(Part.fromText("second")));
        Thread secondCallbackThread = plugin.threads.poll(5, SECONDS);

        assertThat(secondCallbackThread).isNotNull();
        assertThat(secondCallbackThread).isNotEqualTo(Thread.currentThread());
      } finally {
        liveRequestQueue.close();
        testSubscriber.cancel();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void runLive_afterModelBlocksOutput_restartsAndNextTurnIsAnswered() throws Exception {
    FakeLiveModel model = new FakeLiveModel();
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(model)
            .executor(Runnable::run)
            .afterModelCallbackSync((CallbackContext context, LlmResponse response) -> {
              String spoken =
                  response.outputTranscription().flatMap(Transcription::text).orElse("");
              // Built from the screened response, so it carries the accumulated transcription.
              return spoken.contains("secret code")
                  ? Optional.of(
                      response.toBuilder()
                          .content(Content.fromParts(Part.fromText("I can't help with that.")))
                          .build())
                  : Optional.empty();
            })
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    FakeLiveTransport first = model.awaitConnection(0);
    // The finished turn's text, flushed when the turn completes, does not count towards the next.
    first.push(spoken("The secret"));
    first.push(turnComplete());
    // Blocked only once the turn's chunks add up to the phrase.
    first.push(spoken(" code is secret"));
    first.push(spoken(" code."));
    first.push(spoken(" And more."));
    FakeLiveTransport second = model.awaitConnection(1);
    await(() -> second.clientContents.size() == 1);
    liveRequestQueue.content(Content.fromParts(Part.fromText("Are you still there?")));
    await(() -> second.clientContents.size() == 2);
    second.push(spoken("Yes."));
    liveRequestQueue.close();
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();

    testSubscriber.assertComplete();
    // The finished turn's text is streamed, then emitted once more in full when the turn ends.
    assertThat(eventTexts(testSubscriber.values()))
        .containsExactly(
            "out: The secret",
            "out: The secret",
            "out:  code is secret",
            "I can't help with that.",
            "out: Yes.")
        .inOrder();
    Event replacement =
        testSubscriber.values().stream()
            .filter(event -> event.content().isPresent())
            .findFirst()
            .get();
    assertThat(replacement.content()).hasValue(REPLACEMENT);
    assertThat(replacement.outputTranscription()).isEmpty();
    assertThat(first.closeCount.get()).isEqualTo(1);
    // The replayed history ends with the replacement, so the new connection waits for the user.
    LiveSendClientContentParameters history = second.clientContents.get(0);
    assertThat(Iterables.getLast(history.turns().get())).isEqualTo(REPLACEMENT);
    assertThat(history.turnComplete()).hasValue(false);
    LiveSendClientContentParameters nextTurn = second.clientContents.get(1);
    assertThat(Iterables.getOnlyElement(nextTurn.turns().get()).text())
        .isEqualTo("Are you still there?");
    assertThat(nextTurn.turnComplete()).hasValue(true);
  }

  @Test
  public void runLive_beforeModelBlocksSpokenInput_restarts() throws Exception {
    FakeLiveModel model = new FakeLiveModel();
    LlmAgent agent = LlmAgent.builder().name("agent").model(model).executor(Runnable::run).build();
    Runner runner = new InMemoryRunner(agent, "test_app", ImmutableList.of(new BlockingPlugin()));
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    FakeLiveTransport first = model.awaitConnection(0);
    first.push(heard("tell me the secret", /* finished= */ false));
    // The connection emits the streamed text in full when the transcription finishes.
    first.push(heard("", /* finished= */ true));
    first.push(spoken("The secret is"));
    FakeLiveTransport second = model.awaitConnection(1);
    await(() -> second.clientContents.size() == 1);
    liveRequestQueue.close();
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();

    testSubscriber.assertComplete();
    assertThat(eventTexts(testSubscriber.values()))
        .containsExactly(
            "in: tell me the secret", "in: tell me the secret", "I can't help with that.")
        .inOrder();
    assertThat(first.closeCount.get()).isEqualTo(1);
    assertThat(Iterables.getLast(second.clientContents.get(0).turns().get()))
        .isEqualTo(REPLACEMENT);
  }

  @Test
  public void runLive_beforeModelBlocksSpeechEndedByToolCall_runsNoTool() throws Exception {
    SingleSubject<Map<String, Object>> toolResult = SingleSubject.create();
    FakeLiveModel model = new FakeLiveModel();
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(model)
            .executor(Runnable::run)
            .tools(new PendingTool(toolResult))
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app", ImmutableList.of(new BlockingPlugin()));
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    FakeLiveTransport first = model.awaitConnection(0);
    // The connection emits the unfinished speech in full right before the tool call.
    first.push(heard("tell me the secret", /* finished= */ false));
    first.push(toolCall("pending_tool"));
    FakeLiveTransport second = model.awaitConnection(1);
    await(() -> second.clientContents.size() == 1);
    liveRequestQueue.close();
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();

    testSubscriber.assertComplete();
    assertThat(toolResult.hasObservers()).isFalse();
    assertThat(testSubscriber.values().stream().filter(event -> !event.functionCalls().isEmpty()))
        .isEmpty();
    assertThat(eventTexts(testSubscriber.values()))
        .containsExactly(
            "in: tell me the secret", "in: tell me the secret", "I can't help with that.")
        .inOrder();
    assertThat(Iterables.getLast(second.clientContents.get(0).turns().get()))
        .isEqualTo(REPLACEMENT);
  }

  @Test
  public void runLive_callbackBlocksSpokenInputAndTransfers_transfersInsteadOfRestarting()
      throws Exception {
    FakeLiveModel model = new FakeLiveModel();
    LlmAgent helper =
        LlmAgent.builder().name("helper").model(model).executor(Runnable::run).build();
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(model)
            .executor(Runnable::run)
            .subAgents(helper)
            .beforeModelCallbackSync((CallbackContext context, LlmRequest.Builder request) -> {
              context.eventActions().setTransferToAgent("helper");
              return Optional.of(LlmResponse.builder().content(REPLACEMENT).build());
            })
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    FakeLiveTransport first = model.awaitConnection(0);
    first.push(heard("put me through", /* finished= */ false));
    first.push(heard("", /* finished= */ true));
    FakeLiveTransport second = model.awaitConnection(1);
    second.push(spoken("Hello."));
    liveRequestQueue.close();
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();

    testSubscriber.assertComplete();
    assertThat(
            testSubscriber.values().stream()
                .map(event -> event.author() + ": " + eventTexts(ImmutableList.of(event))))
        .containsExactly(
            "agent: [in: put me through]",
            "agent: [in: put me through]",
            "agent: [I can't help with that.]",
            "helper: [out: Hello.]")
        .inOrder();
    assertThat(model.transports).hasSize(2);
    assertThat(first.closeCount.get()).isEqualTo(1);
  }

  @Test
  public void runLive_afterTransfer_oldConnectionIsNotReadOrRestarted() throws Exception {
    PublishProcessor<LlmResponse> agentResponses = PublishProcessor.create();
    TestLlm testLlm =
        createTestLlm(
            agentResponses,
            Flowable.just(createLlmResponse(Content.fromParts(Part.fromText("from helper")))),
            Flowable.just(createLlmResponse(Content.fromParts(Part.fromText("restarted")))));
    LlmAgent helper =
        createTestAgentBuilder(testLlm).name("helper").executor(Runnable::run).build();
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .subAgents(helper)
            .afterModelCallbackSync((CallbackContext context, LlmResponse response) ->
                response.outputTranscription().flatMap(Transcription::text).orElse("").isEmpty()
                    ? Optional.empty()
                    : Optional.of(LlmResponse.builder().content(REPLACEMENT).build()))
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, new LiveRequestQueue(), RunConfig.builder().build()).test();
    agentResponses.onNext(
        createLlmResponse(
            Content.fromParts(
                Part.fromFunctionCall(
                    "transfer_to_agent", ImmutableMap.of("agent_name", "helper")))));
    // Sent by the old connection after the transfer; screening it would block it and restart.
    agentResponses.onNext(spokenResponse("late speech"));
    agentResponses.onComplete();
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();

    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values()))
        .containsExactly(
            "agent: FunctionCall(name=transfer_to_agent, args={agent_name=helper})",
            "agent: FunctionResponse(name=transfer_to_agent, response={})",
            "helper: from helper")
        .inOrder();
  }

  @Test
  public void runLive_callbackBlocksTypedInputAndTransfers_stopsReadingOldConnection()
      throws Exception {
    TestLlm testLlm =
        createTestLlm(
            Flowable.never(),
            Flowable.just(createLlmResponse(Content.fromParts(Part.fromText("from helper")))));
    LlmAgent helper =
        createTestAgentBuilder(testLlm).name("helper").executor(Runnable::run).build();
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .subAgents(helper)
            .beforeModelCallbackSync((CallbackContext context, LlmRequest.Builder request) -> {
              context.eventActions().setTransferToAgent("helper");
              return Optional.of(LlmResponse.builder().content(REPLACEMENT).build());
            })
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    liveRequestQueue.content(Content.fromParts(Part.fromText("put me through")));
    // The old connection never ends on its own, so the run ends only if the flow stops reading it.
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();

    testSubscriber.assertComplete();
    assertThat(simplifyEvents(testSubscriber.values()))
        .containsExactly("agent: I can't help with that.", "helper: from helper")
        .inOrder();
  }

  @Test
  public void runLive_transfer_cancelsToolStillRunning() throws Exception {
    SingleSubject<Map<String, Object>> toolResult = SingleSubject.create();
    AtomicBoolean toolStarted = new AtomicBoolean();
    BaseTool pendingTool =
        new BaseTool("pending_tool", "Returns when the test says so.") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(FunctionDeclaration.builder().name(name()).build());
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            toolStarted.set(true);
            return toolResult;
          }
        };
    TestLlm testLlm =
        createTestLlm(
            Flowable.just(
                    createLlmResponse(
                        Content.fromParts(
                            Part.fromFunctionCall("pending_tool", ImmutableMap.of()))),
                    createLlmResponse(
                        Content.fromParts(
                            Part.fromFunctionCall(
                                "transfer_to_agent", ImmutableMap.of("agent_name", "helper")))))
                .concatWith(Flowable.never()),
            Flowable.just(createLlmResponse(Content.fromParts(Part.fromText("from helper")))));
    LlmAgent helper =
        createTestAgentBuilder(testLlm).name("helper").executor(Runnable::run).build();
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .tools(pendingTool)
            .subAgents(helper)
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, new LiveRequestQueue(), RunConfig.builder().build()).test();
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();

    testSubscriber.assertComplete();
    // The tool still running for the old connection is cancelled, so its response never comes.
    assertThat(toolStarted.get()).isTrue();
    assertThat(toolResult.hasObservers()).isFalse();
    assertThat(simplifyEvents(testSubscriber.values()))
        .containsExactly(
            "agent: FunctionCall(name=pending_tool, args={})",
            "agent: FunctionCall(name=transfer_to_agent, args={agent_name=helper})",
            "agent: FunctionResponse(name=transfer_to_agent, response={})",
            "helper: from helper")
        .inOrder();
  }

  @Test
  public void runLive_withoutCallbacks_modelEventsKeepFlowingWhileToolRuns() throws Exception {
    PublishProcessor<LlmResponse> responses = PublishProcessor.create();
    SingleSubject<Map<String, Object>> toolResult = SingleSubject.create();
    TestLlm testLlm = createTestLlm(() -> responses);
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .tools(new PendingTool(toolResult))
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    assertThat(responses.hasSubscribers()).isTrue();
    responses.onNext(
        LlmResponse.builder()
            .content(Content.fromParts(Part.fromFunctionCall("pending_tool", ImmutableMap.of())))
            .build());
    for (int i = 0; i < 200; i++) {
      responses.onNext(
          LlmResponse.builder().content(Content.fromParts(Part.fromText("chunk"))).build());
    }

    // All of them arrive while the tool is still running.
    testSubscriber.assertNoErrors();
    assertThat(eventTexts(testSubscriber.values()).stream().filter("chunk"::equals).count())
        .isEqualTo(200);
    toolResult.onSuccess(ImmutableMap.of());
    liveRequestQueue.close();
    responses.onComplete();
    assertThat(testSubscriber.await(5, SECONDS)).isTrue();
    testSubscriber.assertComplete();
  }

  @Test
  public void runLive_slowConsumer_blockedOutputComesAfterEverythingBeforeIt() throws Exception {
    PublishProcessor<LlmResponse> responses = PublishProcessor.create();
    TestLlm testLlm =
        createTestLlm(ImmutableList.of(responses, Flowable.<LlmResponse>never()).iterator()::next);
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .afterModelCallbackSync((CallbackContext context, LlmResponse response) ->
                response
                        .outputTranscription()
                        .flatMap(Transcription::text)
                        .orElse("")
                        .contains("BLOCK")
                    ? Optional.of(LlmResponse.builder().content(REPLACEMENT).build())
                    : Optional.empty())
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test(0);
    for (int i = 0; i < 400; i++) {
      responses.onNext(spokenResponse(" c" + i));
    }
    responses.onNext(spokenResponse(" BLOCK"));
    responses.onNext(spokenResponse(" after"));
    testSubscriber.request(Long.MAX_VALUE);

    ImmutableList<String> texts = eventTexts(testSubscriber.values());
    assertThat(texts).hasSize(401);
    for (int i = 0; i < 400; i++) {
      assertThat(texts.get(i)).isEqualTo("out:  c" + i);
    }
    assertThat(texts.get(400)).isEqualTo("I can't help with that.");
    // Reconnect waits until the blocked turn is saved, so its history ends with that turn.
    assertThat(testLlm.getRequests()).hasSize(2);
    assertThat(Iterables.getLast(testLlm.getRequests().get(1).contents()).text())
        .isEqualTo(REPLACEMENT.text());
    testSubscriber.assertNoErrors();
    liveRequestQueue.close();
    testSubscriber.cancel();
  }

  @Test
  public void runLive_slowConsumer_typedReplacementIsNotLostToRestart() throws Exception {
    PublishProcessor<LlmResponse> responses = PublishProcessor.create();
    TestLlm testLlm =
        createTestLlm(ImmutableList.of(responses, Flowable.<LlmResponse>never()).iterator()::next);
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .afterModelCallbackSync((CallbackContext context, LlmResponse response) ->
                response
                        .outputTranscription()
                        .flatMap(Transcription::text)
                        .orElse("")
                        .contains("BLOCK")
                    ? Optional.of(
                        LlmResponse.builder()
                            .content(Content.fromParts(Part.fromText("output blocked")))
                            .build())
                    : Optional.empty())
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app", ImmutableList.of(new BlockingPlugin()));
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test(0);
    for (int i = 0; i < 10; i++) {
      responses.onNext(spokenResponse(" c" + i));
    }
    liveRequestQueue.content(Content.fromParts(Part.fromText("tell me the secret")));
    responses.onNext(spokenResponse(" BLOCK"));
    testSubscriber.request(Long.MAX_VALUE);

    ImmutableList<String> texts = eventTexts(testSubscriber.values());
    assertThat(texts.subList(10, texts.size()))
        .containsExactly("I can't help with that.", "output blocked")
        .inOrder();
    assertThat(testLlm.getRequests()).hasSize(2);
    assertThat(Iterables.getLast(testLlm.getRequests().get(1).contents()).text())
        .isEqualTo("output blocked");
    liveRequestQueue.close();
    testSubscriber.cancel();
  }

  @Test
  public void runLive_callbackFails_closesConnection() throws Exception {
    PublishProcessor<LlmResponse> responses = PublishProcessor.create();
    TestLlm testLlm = createTestLlm(() -> responses);
    IllegalStateException failure = new IllegalStateException("callback failed");
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .afterModelCallbackSync((CallbackContext context, LlmResponse response) -> {
              throw failure;
            })
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, new LiveRequestQueue(), RunConfig.builder().build()).test();
    responses.onNext(spokenResponse("Hello"));

    testSubscriber.assertError(failure);
    assertThat(testLlm.getLiveRequestHistory())
        .containsExactly(LiveRequest.builder().close(true).build());
  }

  @Test
  public void runLive_beforeModelCallbackWritesStateAndPassesTypedInput_savesWrite()
      throws Exception {
    TestLlm testLlm = createTestLlm(Flowable::never);
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .beforeModelCallbackSync((CallbackContext context, LlmRequest.Builder request) -> {
              context.state().put("screened", true);
              return Optional.empty();
            })
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    try {
      liveRequestQueue.content(Content.fromParts(Part.fromText("hello")));

      assertSavedOnOwnEvent(runner, session, "screened");
      assertThat(sentTexts(testLlm)).containsExactly("hello");
      testSubscriber.assertNoErrors();
    } finally {
      liveRequestQueue.close();
      testSubscriber.cancel();
    }
  }

  @Test
  public void runLive_beforeModelCallbackWritesStateAndPassesSpokenInput_savesWrite()
      throws Exception {
    FakeLiveModel model = new FakeLiveModel();
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(model)
            .executor(Runnable::run)
            .beforeModelCallbackSync((CallbackContext context, LlmRequest.Builder request) -> {
              context.state().put("screened", true);
              return Optional.empty();
            })
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    try {
      FakeLiveTransport transport = model.awaitConnection(0);
      transport.push(heard("hello", /* finished= */ false));
      transport.push(heard("", /* finished= */ true));

      assertSavedOnOwnEvent(runner, session, "screened");
      testSubscriber.assertNoErrors();
    } finally {
      liveRequestQueue.close();
      testSubscriber.cancel();
    }
  }

  @Test
  public void runLive_afterModelCallbackWritesStateAndPassesOutput_savesWrite() throws Exception {
    PublishProcessor<LlmResponse> responses = PublishProcessor.create();
    TestLlm testLlm = createTestLlm(() -> responses);
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .afterModelCallbackSync((CallbackContext context, LlmResponse response) -> {
              context.state().put("checked", true);
              return Optional.empty();
            })
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    try {
      // The connection streams output transcription in partial responses; those responses are not
      // saved.
      responses.onNext(spokenResponse("Hello").toBuilder().partial(true).build());

      assertSavedOnOwnEvent(runner, session, "checked");
      testSubscriber.assertNoErrors();
    } finally {
      liveRequestQueue.close();
      testSubscriber.cancel();
    }
  }

  @Test
  public void runLive_restartWhileCallbackScreensInput_sendsLaterInputOnNewConnectionOnce()
      throws Exception {
    PublishProcessor<LlmResponse> responses = PublishProcessor.create();
    TestLlm testLlm =
        createTestLlm(ImmutableList.of(responses, Flowable.<LlmResponse>never()).iterator()::next);
    MaybeSubject<LlmResponse> firstScreening = MaybeSubject.create();
    LlmAgent agent =
        createTestAgentBuilder(testLlm)
            .name("agent")
            .executor(Runnable::run)
            .beforeModelCallback((CallbackContext context, LlmRequest.Builder request) ->
                Objects.equals(Iterables.getLast(request.build().contents()).text(), "first")
                    ? firstScreening
                    : Maybe.empty())
            .afterModelCallbackSync((CallbackContext context, LlmResponse response) ->
                response
                        .outputTranscription()
                        .flatMap(Transcription::text)
                        .orElse("")
                        .contains("BLOCK")
                    ? Optional.of(LlmResponse.builder().content(REPLACEMENT).build())
                    : Optional.empty())
            .build();
    Runner runner = new InMemoryRunner(agent, "test_app");
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();

    TestSubscriber<Event> testSubscriber =
        runner.runLive(session, liveRequestQueue, RunConfig.builder().build()).test();
    try {
      liveRequestQueue.content(Content.fromParts(Part.fromText("first")));
      assertThat(firstScreening.hasObservers()).isTrue();
      // Typed while the first input is still being screened.
      liveRequestQueue.content(Content.fromParts(Part.fromText("second")));
      responses.onNext(spokenResponse(" BLOCK"));
      await(() -> testLlm.getRequests().size() == 2);
      await(() -> sentTexts(testLlm).contains("second"));

      ImmutableList<LiveRequest> history = testLlm.getLiveRequestHistory();
      assertThat(sentTexts(testLlm).stream().filter("second"::equals)).hasSize(1);
      // Sent after the first connection closed, so it uses the new connection.
      assertThat(
              Iterables.indexOf(
                  history,
                  request -> request.content().map(Content::text).equals(Optional.of("second"))))
          .isGreaterThan(Iterables.indexOf(history, LiveRequest::shouldClose));
      testSubscriber.assertNoErrors();
    } finally {
      liveRequestQueue.close();
      testSubscriber.cancel();
    }
  }

  /**
   * Asserts that the session saved {@code key} on exactly one event with no content, carrying only
   * the write.
   */
  private static void assertSavedOnOwnEvent(Runner runner, Session session, String key)
      throws InterruptedException {
    await(() -> !savedWrites(runner, session, key).isEmpty());
    Event write = Iterables.getOnlyElement(savedWrites(runner, session, key));
    assertThat(write.author()).isEqualTo("agent");
    assertThat(write.content()).isEmpty();
    assertThat(savedSession(runner, session).state()).containsEntry(key, true);
  }

  private static ImmutableList<Event> savedWrites(Runner runner, Session session, String key) {
    return savedSession(runner, session).events().stream()
        .filter(event -> event.actions().stateDelta().containsKey(key))
        .collect(toImmutableList());
  }

  private static Session savedSession(Runner runner, Session session) {
    return runner
        .sessionService()
        .getSession("test_app", "user", session.id(), Optional.empty())
        .blockingGet();
  }

  private static LlmResponse spokenResponse(String text) {
    return LlmResponse.builder()
        .outputTranscription(Transcription.builder().text(text).build())
        .build();
  }

  /** A tool whose result arrives when the test provides it. */
  private static final class PendingTool extends BaseTool {
    private final SingleSubject<Map<String, Object>> result;

    PendingTool(SingleSubject<Map<String, Object>> result) {
      super("pending_tool", "Returns when the test says so.");
      this.result = result;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name(name()).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return result;
    }
  }

  /** Test plugin that records the thread on which each before-model callback runs. */
  private static final class ThreadRecordingPlugin extends BasePlugin {
    final BlockingQueue<Thread> threads = new LinkedBlockingQueue<>();

    ThreadRecordingPlugin() {
      super("thread_recording_plugin");
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(
        CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
      threads.add(Thread.currentThread());
      return Maybe.empty();
    }
  }

  /** Blocks any request whose last content mentions a secret. */
  private static final class BlockingPlugin extends BasePlugin {
    final AtomicInteger calls = new AtomicInteger();

    BlockingPlugin() {
      super("blocking_plugin");
    }

    @Override
    public Maybe<LlmResponse> beforeModelCallback(
        CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
      calls.incrementAndGet();
      List<Content> contents = llmRequest.build().contents();
      String text = contents.isEmpty() ? "" : Iterables.getLast(contents).text();
      if (text == null || !text.contains("secret")) {
        return Maybe.empty();
      }
      callbackContext.state().put("blocked", true);
      return Maybe.just(LlmResponse.builder().content(REPLACEMENT).build());
    }
  }

  private static LiveServerMessage spoken(String text) {
    return LiveServerMessage.builder()
        .serverContent(
            LiveServerContent.builder()
                .outputTranscription(Transcription.builder().text(text).build())
                .build())
        .build();
  }

  private static LiveServerMessage heard(String text, boolean finished) {
    return LiveServerMessage.builder()
        .serverContent(
            LiveServerContent.builder()
                .inputTranscription(Transcription.builder().text(text).finished(finished).build())
                .build())
        .build();
  }

  private static LiveServerMessage toolCall(String name) {
    return LiveServerMessage.builder()
        .toolCall(
            LiveServerToolCall.builder()
                .functionCalls(
                    ImmutableList.of(FunctionCall.builder().id("call-1").name(name).build()))
                .build())
        .build();
  }

  private static LiveServerMessage turnComplete() {
    return LiveServerMessage.builder()
        .serverContent(LiveServerContent.builder().turnComplete(true).build())
        .build();
  }

  /** Returns the text of each content sent to the model that has any. */
  private static ImmutableList<String> sentTexts(TestLlm testLlm) {
    return testLlm.getLiveRequestHistory().stream()
        .flatMap(request -> request.content().stream())
        .map(Content::text)
        .filter(text -> text != null && !text.isEmpty())
        .collect(toImmutableList());
  }

  /** Describes each event by its text, or by its input or output transcription. */
  private static ImmutableList<String> eventTexts(List<Event> events) {
    return events.stream()
        .map(event ->
            event
                .content()
                .map(Content::text)
                .or(() ->
                    event.inputTranscription().flatMap(Transcription::text).map(t -> "in: " + t))
                .or(() ->
                    event.outputTranscription().flatMap(Transcription::text).map(t -> "out: " + t)))
        .flatMap(Optional::stream)
        .collect(toImmutableList());
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
      Thread.sleep(50);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  /** A {@link Gemini} whose live connections run over in-process transports. */
  private static final class FakeLiveModel extends Gemini {
    final List<FakeLiveTransport> transports = new CopyOnWriteArrayList<>();

    FakeLiveModel() {
      super("gemini-live-test", "test-api-key");
    }

    @Override
    protected CompletableFuture<GeminiLiveTransport> connectLiveTransport(
        String modelName, LiveConnectConfig config) {
      FakeLiveTransport transport = new FakeLiveTransport();
      transports.add(transport);
      return CompletableFuture.completedFuture(transport);
    }

    FakeLiveTransport awaitConnection(int index) throws InterruptedException {
      await(() -> transports.size() > index && transports.get(index).receiver.isDone());
      return transports.get(index);
    }
  }

  /** Records what the connection sends and lets the test push server messages. */
  private static final class FakeLiveTransport implements GeminiLiveTransport {
    final List<LiveSendClientContentParameters> clientContents = new CopyOnWriteArrayList<>();
    final AtomicInteger closeCount = new AtomicInteger();
    final CompletableFuture<Consumer<LiveServerMessage>> receiver = new CompletableFuture<>();

    void push(LiveServerMessage message) {
      receiver.join().accept(message);
    }

    @Override
    public CompletableFuture<Void> sendClientContent(LiveSendClientContentParameters params) {
      clientContents.add(params);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendRealtimeInput(LiveSendRealtimeInputParameters params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> sendToolResponse(LiveSendToolResponseParameters params) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> receive(
        Consumer<LiveServerMessage> onMessage, Runnable onStreamEnd) {
      receiver.complete(onMessage);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> close() {
      closeCount.incrementAndGet();
      return CompletableFuture.completedFuture(null);
    }
  }
}
