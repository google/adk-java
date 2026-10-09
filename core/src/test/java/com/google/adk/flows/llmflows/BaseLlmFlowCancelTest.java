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

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.DisposableSubscriber;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests that cancelling a {@link BaseLlmFlow#run} subscription reaches the model stream. */
@RunWith(JUnit4.class)
public final class BaseLlmFlowCancelTest {

  private static final Content FUNCTION_CALL =
      Content.fromParts(Part.fromFunctionCall("my_function", ImmutableMap.of("arg1", "value1")));
  private static final Content TEXT = Content.fromParts(Part.fromText("LLM response"));

  @Test
  public void run_disposedWhileModelStreams_cancelsModelStream() throws Exception {
    NeverEndingModel model = new NeverEndingModel();
    InvocationContext invocationContext =
        createInvocationContext(createTestAgentBuilder(model.llm).build());

    Disposable subscription =
        createBaseLlmFlow().run(invocationContext).subscribe(event -> {}, error -> {});
    assertThat(model.subscribed.await(5, SECONDS)).isTrue();
    subscription.dispose();

    assertThat(model.cancelled.await(2, SECONDS)).isTrue();
  }

  @Test
  public void runner_disposedWhileModelStreams_cancelsModelStream() throws Exception {
    NeverEndingModel model = new NeverEndingModel();
    LlmAgent agent = createTestAgentBuilder(model.llm).build();
    InMemoryRunner runner = new InMemoryRunner(agent);
    Session session = runner.sessionService().createSession(runner.appName(), "user").blockingGet();

    Disposable subscription =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText("hi")))
            .subscribe(event -> {}, error -> {});
    assertThat(model.subscribed.await(5, SECONDS)).isTrue();
    subscription.dispose();

    assertThat(model.cancelled.await(2, SECONDS)).isTrue();
  }

  @Test
  public void run_disposedBeforeFunctionCallArrives_doesNotRunTool() throws Exception {
    PublishProcessor<LlmResponse> responses = PublishProcessor.create();
    CountDownLatch cancelled = new CountDownLatch(1);
    TestLlm testLlm = createTestLlm(() -> responses.doOnCancel(cancelled::countDown));
    RecordingTool tool = new RecordingTool();
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm).tools(ImmutableList.of(tool)).build());

    Disposable subscription =
        createBaseLlmFlow().run(invocationContext).subscribe(event -> {}, error -> {});
    assertThat(responses.hasSubscribers()).isTrue();
    subscription.dispose();
    assertThat(cancelled.await(2, SECONDS)).isTrue();
    responses.onNext(createLlmResponse(FUNCTION_CALL));
    responses.onComplete();

    assertThat(tool.ran.await(200, MILLISECONDS)).isFalse();
    assertThat(testLlm.getRequests()).hasSize(1);
  }

  @Test
  public void run_consumedToCompletion_runsToolAndEmitsEveryEvent() throws Exception {
    TestLlm testLlm =
        createTestLlm(
            Flowable.just(createLlmResponse(FUNCTION_CALL)),
            Flowable.just(createLlmResponse(TEXT)));
    RecordingTool tool = new RecordingTool();
    InvocationContext invocationContext =
        createInvocationContext(
            createTestAgentBuilder(testLlm).tools(ImmutableList.of(tool)).build());

    List<Event> events = createBaseLlmFlow().run(invocationContext).toList().blockingGet();

    assertThat(tool.ran.getCount()).isEqualTo(0);
    assertThat(events).hasSize(3);
    assertThat(events.get(2).content()).hasValue(TEXT);
    assertThat(testLlm.getRequests()).hasSize(2);
  }

  @Test
  public void run_boundedDemand_deliversFinalEventAndCompletion() {
    TestLlm testLlm = createTestLlm(createLlmResponse(TEXT));
    InvocationContext invocationContext =
        createInvocationContext(createTestAgentBuilder(testLlm).build());

    TestSubscriber<Event> subscriber = createBaseLlmFlow().run(invocationContext).test(1);

    subscriber.assertValueCount(1).assertComplete();
    assertThat(testLlm.getRequests()).hasSize(1);
  }

  @Test
  public void run_boundedDemand_deliversModelErrorAfterBufferedEvent() {
    TestLlm testLlm =
        createTestLlm(
            () ->
                Flowable.concat(
                    Flowable.just(createLlmResponse(TEXT)),
                    Flowable.error(new IllegalStateException("model failed"))));
    InvocationContext invocationContext =
        createInvocationContext(createTestAgentBuilder(testLlm).build());

    TestSubscriber<Event> subscriber = createBaseLlmFlow().run(invocationContext).test(1);

    subscriber.assertValueCount(1).assertError(IllegalStateException.class);
  }

  @Test
  public void run_cancelledInOnSubscribe_doesNotLeaveModelStreaming() throws Exception {
    NeverEndingModel model = new NeverEndingModel();
    InvocationContext invocationContext =
        createInvocationContext(createTestAgentBuilder(model.llm).build());
    // A single step subscribes to the cached step directly, with no concatWith in front of it.
    BaseLlmFlow singleStepFlow =
        new BaseLlmFlow(ImmutableList.of(), ImmutableList.of(), Optional.of(1)) {};

    singleStepFlow
        .run(invocationContext)
        .subscribe(
            new DisposableSubscriber<Event>() {
              @Override
              protected void onStart() {
                cancel();
              }

              @Override
              public void onNext(Event event) {}

              @Override
              public void onError(Throwable error) {}

              @Override
              public void onComplete() {}
            });

    boolean neverSubscribed = model.subscribed.getCount() == 1;
    assertThat(neverSubscribed || model.cancelled.await(2, SECONDS)).isTrue();
  }

  @Test
  public void run_subscribedAgainAfterCancel_replaysBufferedEventsAndCompletes() throws Exception {
    PublishProcessor<LlmResponse> responses = PublishProcessor.create();
    CountDownLatch cancelled = new CountDownLatch(1);
    TestLlm testLlm = createTestLlm(() -> responses.doOnCancel(cancelled::countDown));
    InvocationContext invocationContext =
        createInvocationContext(createTestAgentBuilder(testLlm).build());
    Flowable<Event> events = createBaseLlmFlow().run(invocationContext);

    TestSubscriber<Event> first = events.test();
    responses.onNext(createLlmResponse(TEXT));
    first.assertValueCount(1).assertNotComplete();
    first.cancel();
    assertThat(cancelled.await(2, SECONDS)).isTrue();

    TestSubscriber<Event> second = events.test();

    second.assertValueCount(1).assertComplete();
    assertThat(second.values().get(0).content()).hasValue(TEXT);
    assertThat(testLlm.getRequests()).hasSize(1);
  }

  @Test
  public void parallelAgent_subAgentEscalates_cancelsSiblingModelStream() throws Exception {
    NeverEndingModel model = new NeverEndingModel();
    LlmAgent streamingAgent = createTestAgentBuilder(model.llm).name("streaming_agent").build();
    TestScheduler scheduler = new TestScheduler();
    ParallelAgent parallelAgent =
        ParallelAgent.builder()
            .name("parallel_agent")
            .subAgents(streamingAgent, new EscalatingAgent())
            .scheduler(scheduler)
            .build();
    InvocationContext invocationContext = createInvocationContext(parallelAgent);

    TestSubscriber<Event> subscriber = parallelAgent.runAsync(invocationContext).test();
    // Subscribes the branches in order: the model starts streaming, then the sibling escalates.
    scheduler.triggerActions();

    assertThat(model.subscribed.getCount()).isEqualTo(0);
    subscriber.assertValueCount(1).assertComplete();
    assertThat(subscriber.values().get(0).actions().escalate()).hasValue(true);
    assertThat(model.cancelled.await(2, SECONDS)).isTrue();
  }

  private static BaseLlmFlow createBaseLlmFlow() {
    return new BaseLlmFlow(ImmutableList.of(), ImmutableList.of(), Optional.empty()) {};
  }

  /** A model whose stream never completes and reports when it is subscribed to and cancelled. */
  private static final class NeverEndingModel {
    final CountDownLatch subscribed = new CountDownLatch(1);
    final CountDownLatch cancelled = new CountDownLatch(1);
    final TestLlm llm =
        createTestLlm(
            () ->
                Flowable.<LlmResponse>never()
                    .doOnSubscribe(subscription -> subscribed.countDown())
                    .doOnCancel(cancelled::countDown));
  }

  /** A sub-agent that escalates with its first event. */
  private static final class EscalatingAgent extends BaseAgent {
    EscalatingAgent() {
      super("escalating_agent", "escalates immediately", ImmutableList.of(), null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return Flowable.just(
          Event.builder()
              .author(name())
              .invocationId(invocationContext.invocationId())
              .branch(invocationContext.branch().orElse(null))
              .content(Content.fromParts(Part.fromText("escalating")))
              .actions(EventActions.builder().escalate(true).build())
              .build());
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      throw new UnsupportedOperationException("Not implemented");
    }
  }

  private static final class RecordingTool extends BaseTool {
    final CountDownLatch ran = new CountDownLatch(1);

    RecordingTool() {
      super("my_function", "tool description for my_function");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name(name()).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      ran.countDown();
      return Single.just(ImmutableMap.of("response", "response for my_function"));
    }
  }
}
