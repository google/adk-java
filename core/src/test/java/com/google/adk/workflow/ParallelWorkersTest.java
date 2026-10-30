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

package com.google.adk.workflow;

import static com.google.adk.testing.WorkflowTestNodes.emitter;
import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.interrupt;
import static com.google.adk.workflow.WorkflowResumeTesting.outputEventAt;
import static com.google.adk.workflow.WorkflowResumeTesting.pathOf;
import static com.google.adk.workflow.WorkflowResumeTesting.pathsOf;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.Context;
import com.google.adk.events.Event;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ParallelWorkersTest {

  private static final Schema INPUT_SCHEMA = Schema.builder().type("ARRAY").build();
  private static final Schema OUTPUT_SCHEMA = Schema.builder().type("STRING").build();
  private static final Schema STATE_SCHEMA = Schema.builder().type("OBJECT").build();

  /** A node with a description, config, and input, output and state schemas of its own. */
  private static final Node DESCRIBED_WORKER =
      new Node() {
        @Override
        public String name() {
          return "worker";
        }

        @Override
        public String description() {
          return "A worker with its own settings.";
        }

        @Override
        public NodeConfig config() {
          return NodeConfig.builder().timeout(Duration.ofSeconds(1)).build();
        }

        @Override
        public Optional<Schema> inputSchema() {
          return Optional.of(INPUT_SCHEMA);
        }

        @Override
        public Optional<Schema> outputSchema() {
          return Optional.of(OUTPUT_SCHEMA);
        }

        @Override
        public Optional<Schema> stateSchema() {
          return Optional.of(STATE_SCHEMA);
        }

        @Override
        public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
          return Flowable.empty();
        }
      };

  @Test
  public void parallelNode_takesTheNodesNameDescriptionAndStateSchema() {
    Node node = ParallelWorkers.parallelNode(DESCRIBED_WORKER);

    assertThat(node.name()).isEqualTo("worker");
    assertThat(node.description()).isEqualTo("A worker with its own settings.");
    assertThat(node.stateSchema()).hasValue(STATE_SCHEMA);
  }

  @Test
  public void parallelNode_rerunsOnResumeWithoutWaitingForOutput() {
    Node node = ParallelWorkers.parallelNode(DESCRIBED_WORKER);

    assertThat(node.rerunOnResume()).isTrue();
    assertThat(node.waitForOutput()).isFalse();
  }

  @Test
  public void parallelNode_leavesConfigAndSchemasToEachItem() {
    Node node = ParallelWorkers.parallelNode(DESCRIBED_WORKER);

    assertThat(node.config()).isEqualTo(NodeConfig.builder().build());
    assertThat(node.inputSchema()).isEmpty();
    assertThat(node.outputSchema()).isEmpty();
  }

  @Test
  public void parallelNode_takesRequiresAllPredecessorsFromTheNode() {
    Node join = JoinNode.builder().name("gather").build();

    assertThat(ParallelWorkers.parallelNode(join).requiresAllPredecessors()).isTrue();
    assertThat(ParallelWorkers.parallelNode(doubler()).requiresAllPredecessors()).isFalse();
  }

  @Test
  public void parallelNode_start_isRejected() {
    WorkflowConfigurationException thrown =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> ParallelWorkers.parallelNode(Start.INSTANCE));

    assertThat(thrown).hasMessageThat().contains("cannot wrap Start");
  }

  @Test
  public void parallelNode_capBelowOne_isRejected() {
    Node worker = doubler();

    WorkflowConfigurationException thrown =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> ParallelWorkers.parallelNode(worker, /* maxParallelWorkers= */ 0));

    assertThat(thrown).hasMessageThat().contains("maxParallelWorkers must be at least 1");
  }

  @Test
  public void runNode_listInput_runsEachItemOnItsOwnBranchAndOutputsInInputOrder() {
    Run run = run(ParallelWorkers.parallelNode(doubler()), ImmutableList.of(1, 2, 3));

    assertThat(run.output()).isEqualTo(ImmutableList.of(2, 4, 6));
    ImmutableList<Event> itemEvents = run.eventsUnder("worker@1/");
    assertThat(pathsOf(itemEvents))
        .containsExactly("worker@1/worker@1", "worker@1/worker@2", "worker@1/worker@3")
        .inOrder();
    assertThat(
            itemEvents.stream().map(event -> event.branch().orElse("")).collect(toImmutableList()))
        .containsExactly("worker@1", "worker@2", "worker@3")
        .inOrder();
  }

  @Test
  public void runNode_laterItemFinishesFirst_keepsInputOrder() {
    CountDownLatch secondDone = new CountDownLatch(1);
    Node worker =
        node(
            item ->
                item.equals(1)
                    ? Flowable.fromCallable(
                            () -> {
                              awaitRelease(secondDone);
                              return "res-1";
                            })
                        .subscribeOn(Schedulers.io())
                    : Flowable.just("res-2").doOnComplete(secondDone::countDown));

    Run run = run(ParallelWorkers.parallelNode(worker), ImmutableList.of(1, 2));

    assertThat(pathsOf(run.eventsUnder("worker@1/")))
        .containsExactly("worker@1/worker@2", "worker@1/worker@1")
        .inOrder();
    assertThat(run.output()).isEqualTo(ImmutableList.of("res-1", "res-2"));
  }

  @Test
  public void runNode_valueThatIsNotAList_runsAsOneItem() {
    Run run = run(ParallelWorkers.parallelNode(doubler()), 5);

    assertThat(run.output()).isEqualTo(ImmutableList.of(10));
  }

  @Test
  public void runNode_nullInput_runsAsOneItem() {
    Node worker = node(item -> Flowable.just("got " + item));

    Run run = run(ParallelWorkers.parallelNode(worker), null);

    assertThat(run.output()).isEqualTo(ImmutableList.of("got null"));
  }

  @Test
  public void runNode_itemWithoutOutput_leavesNullInItsPlace() {
    Node worker = node(item -> item.equals(2) ? Flowable.empty() : Flowable.just(item));

    Run run = run(ParallelWorkers.parallelNode(worker), ImmutableList.of(1, 2, 3));

    assertThat(run.output()).isEqualTo(Arrays.asList(1, null, 3));
  }

  @Test
  public void runNode_emptyList_runsNothingAndOutputsAnEmptyList() {
    Run run = run(ParallelWorkers.parallelNode(doubler()), ImmutableList.of());

    assertThat(run.output()).isEqualTo(ImmutableList.of());
    assertThat(run.eventsUnder("worker@1/")).isEmpty();
  }

  @Test
  public void runNode_maxParallelWorkers_startsNoMoreItemsUntilOneFinishes() {
    AtomicInteger started = new AtomicInteger();
    CompletableSubject release = CompletableSubject.create();
    Node worker =
        node(
            item -> {
              started.incrementAndGet();
              return release.andThen(Flowable.just(item));
            });
    TestObserver<Context> observer =
        start(
                ParallelWorkers.parallelNode(worker, /* maxParallelWorkers= */ 2),
                ImmutableList.of(1, 2, 3, 4, 5, 6))
            .test();
    int startedBeforeRelease = started.get();

    release.onComplete();

    assertThat(startedBeforeRelease).isEqualTo(2);
    assertThat(observer.awaitDone(10, SECONDS).values().get(0).requireNodeState().output())
        .hasValue(ImmutableList.of(1, 2, 3, 4, 5, 6));
  }

  @Test
  public void runNode_noMaxParallelWorkers_startsEveryItemAtOnce() {
    AtomicInteger started = new AtomicInteger();
    CompletableSubject release = CompletableSubject.create();
    Node worker =
        node(
            item -> {
              started.incrementAndGet();
              return release.andThen(Flowable.just(item));
            });
    TestObserver<Context> observer =
        start(ParallelWorkers.parallelNode(worker), ImmutableList.of(1, 2, 3)).test();
    int startedBeforeRelease = started.get();

    release.onComplete();

    assertThat(startedBeforeRelease).isEqualTo(3);
    assertThat(observer.awaitDone(10, SECONDS).values().get(0).requireNodeState().output())
        .hasValue(ImmutableList.of(1, 2, 3));
  }

  @Test
  public void runNode_itemConfigWithRetries_retriesOnlyTheFailedItem() {
    Map<Object, Integer> attemptsByItem = new ConcurrentHashMap<>();
    NodeConfig retryOnce =
        NodeConfig.builder()
            .retryConfig(RetryConfig.builder().maxAttempts(2).initialDelay(Duration.ZERO).build())
            .build();
    Node worker =
        node(
            retryOnce,
            item ->
                attemptsByItem.merge(item, 1, Integer::sum) == 1 && item.equals(2)
                    ? Flowable.error(new IllegalStateException("first attempt failed"))
                    : Flowable.just(item));

    Run run = run(ParallelWorkers.parallelNode(worker), ImmutableList.of(1, 2));

    assertThat(run.output()).isEqualTo(ImmutableList.of(1, 2));
    assertThat(attemptsByItem).containsExactly(1, 1, 2, 2);
  }

  @Test
  public void runNode_itemConfigWithTimeout_failsTheItemThatRunsTooLong() {
    Node worker =
        node(
            NodeConfig.builder().timeout(Duration.ofMillis(100)).build(), item -> Flowable.never());

    Run run = run(ParallelWorkers.parallelNode(worker), ImmutableList.of("slow"));

    assertThat(run.failure().orElseThrow()).isInstanceOf(NodeTimeoutException.class);
    assertThat(run.context().requireNodeState().failureNodePath()).hasValue("worker@1/worker@1");
  }

  @Test
  public void runNode_appliedTwice_fansOutOverNestedLists() {
    Node node = ParallelWorkers.parallelNode(ParallelWorkers.parallelNode(doubler()));

    Run run = run(node, ImmutableList.of(ImmutableList.of(1, 2), ImmutableList.of(3)));

    assertThat(run.output())
        .isEqualTo(ImmutableList.of(ImmutableList.of(2, 4), ImmutableList.of(6)));
  }

  @Test
  public void runNode_itemFails_failsTheNodeWithOneErrorEventAtTheItem() {
    Run run = run(ParallelWorkers.parallelNode(failsOn(2)), ImmutableList.of(1, 2, 3));

    assertThat(run.failure().orElseThrow()).hasMessageThat().isEqualTo("bad item 2");
    assertThat(run.context().requireNodeState().failureNodePath()).hasValue("worker@1/worker@2");
    assertThat(run.output()).isNull();
    assertThat(
            pathsOf(
                run.events().stream()
                    .filter(event -> event.errorCode().isPresent())
                    .collect(toImmutableList())))
        .containsExactly("worker@1/worker@2");
  }

  @Test
  public void runNode_itemFailsUnderCap_neverStartsQueuedItems() {
    Run run =
        run(
            ParallelWorkers.parallelNode(failsOn(1), /* maxParallelWorkers= */ 1),
            ImmutableList.of(1, 2, 3));

    assertThat(run.failure()).isPresent();
    assertThat(ImmutableSet.copyOf(pathsOf(run.eventsUnder("worker@1/"))))
        .containsExactly("worker@1/worker@1");
  }

  @Test
  public void runNode_itemFails_cancelsRunningSiblings() {
    AtomicBoolean siblingCancelled = new AtomicBoolean();
    Node worker =
        node(
            item ->
                item.equals("slow")
                    ? Flowable.timer(10, SECONDS).doOnCancel(() -> siblingCancelled.set(true))
                    : Flowable.error(new IllegalStateException("boom")));

    Run run = run(ParallelWorkers.parallelNode(worker), ImmutableList.of("slow", "bad"));

    assertThat(run.failure().orElseThrow()).hasMessageThat().isEqualTo("boom");
    assertThat(siblingCancelled.get()).isTrue();
  }

  @Test
  public void runNode_itemThrowsAnError_propagatesItAndCancelsRunningSiblings() {
    AtomicBoolean siblingCancelled = new AtomicBoolean();
    Node worker =
        node(
            item ->
                item.equals("slow")
                    ? Flowable.timer(10, SECONDS).doOnCancel(() -> siblingCancelled.set(true))
                    : Flowable.error(new AssertionError("fatal")));
    Node node = ParallelWorkers.parallelNode(worker);

    AssertionError thrown =
        assertThrows(AssertionError.class, () -> run(node, ImmutableList.of("slow", "fatal")));

    assertThat(thrown).hasMessageThat().isEqualTo("fatal");
    assertThat(siblingCancelled.get()).isTrue();
  }

  @Test
  public void runNode_itemInterrupts_waitsWithoutOutput() {
    Run run = run(ParallelWorkers.parallelNode(interruptsOn(2)), ImmutableList.of(1, 2));

    assertThat(run.interruptIds()).containsExactly("ask-2");
    assertThat(run.output()).isNull();
    assertThat(run.failure()).isEmpty();
  }

  @Test
  public void runNode_itemInterrupts_letsRunningItemsFinish() {
    CountDownLatch release = new CountDownLatch(1);
    Node worker =
        node(
            item ->
                item.equals("slow")
                    ? Flowable.fromCallable(
                            () -> {
                              awaitRelease(release);
                              return "done";
                            })
                        .subscribeOn(Schedulers.io())
                    : Flowable.just(interrupt("ask-" + item)));
    List<Event> events = new CopyOnWriteArrayList<>();
    TestObserver<Context> observer =
        start(ParallelWorkers.parallelNode(worker), ImmutableList.of("slow", "asks"), events)
            .test();

    release.countDown();

    NodeExecutionState state = observer.awaitDone(10, SECONDS).values().get(0).requireNodeState();
    assertThat(state.interruptIds()).containsExactly("ask-asks");
    assertThat(state.output()).isEmpty();
    assertThat(outputEventAt(events, "worker@1/worker@1").output()).hasValue("done");
  }

  @Test
  public void runNode_itemInterruptsUnderCap_neverStartsQueuedItems() {
    Run run =
        run(
            ParallelWorkers.parallelNode(interruptsOn(1), /* maxParallelWorkers= */ 1),
            ImmutableList.of(1, 2, 3));

    assertThat(run.interruptIds()).containsExactly("ask-1");
    assertThat(ImmutableSet.copyOf(pathsOf(run.eventsUnder("worker@1/"))))
        .containsExactly("worker@1/worker@1");
  }

  @Test
  public void runNode_siblingFailsAfterAnInterrupt_failsTheNode() {
    Node worker =
        node(
            item ->
                item.equals(1)
                    ? Flowable.just(interrupt("ask-1"))
                    : Flowable.error(new IllegalStateException("late failure")));

    Run run = run(ParallelWorkers.parallelNode(worker), ImmutableList.of(1, 2));

    assertThat(run.failure().orElseThrow()).hasMessageThat().isEqualTo("late failure");
  }

  @Test
  public void runNode_severalItemsInterrupt_mergesAllInterruptIds() {
    Node worker = node(item -> Flowable.just(interrupt("ask-" + item)));

    Run run = run(ParallelWorkers.parallelNode(worker), ImmutableList.of(1, 2));

    assertThat(run.interruptIds()).containsExactly("ask-1", "ask-2");
  }

  @Test
  public void resume_answeredItem_replaysTheFinishedItemsWithoutRunningThemAgain() {
    Map<Object, Integer> runsByItem = new ConcurrentHashMap<>();
    Node worker =
        node(
            item -> {
              runsByItem.merge(item, 1, Integer::sum);
              return item.equals(2) ? Flowable.just(interrupt("ask-2")) : Flowable.just(item);
            });
    Node items = emitter("items", ImmutableList.of(1, 2, 3));
    Node fanOut = ParallelWorkers.parallelNode(worker);
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, items),
                new Edge(items, fanOut),
                new Edge(fanOut, captureInput("reply"))));
    session.advance(go());

    ImmutableList<Event> events = session.turn(answer("ask-2", ImmutableMap.of("result", 20)));

    assertThat(runsByItem).containsExactly(1, 1, 2, 1, 3, 1);
    assertThat(replyInput(events)).isEqualTo(ImmutableList.of(1, 20, 3));
  }

  @Test
  public void resume_underCap_startsTheQueuedItemOnceTheAskingItemIsAnswered() {
    Map<Object, Integer> runsByItem = new ConcurrentHashMap<>();
    Node worker =
        node(
            item -> {
              runsByItem.merge(item, 1, Integer::sum);
              return item.equals(1) ? Flowable.just(item) : Flowable.just(interrupt("ask-" + item));
            });
    Node items = emitter("items", ImmutableList.of(1, 2, 3));
    Node fanOut = ParallelWorkers.parallelNode(worker, /* maxParallelWorkers= */ 2);
    WorkflowSession session =
        new WorkflowSession(
            workflow(
                new Edge(START, items),
                new Edge(items, fanOut),
                new Edge(fanOut, captureInput("reply"))));
    session.advance(go());
    ImmutableMap<Object, Integer> runsAfterFirstTurn = ImmutableMap.copyOf(runsByItem);
    session.advance(answer("ask-2", ImmutableMap.of("result", 20)));

    ImmutableList<Event> events = session.turn(answer("ask-3", ImmutableMap.of("result", 30)));

    assertThat(runsAfterFirstTurn).containsExactly(1, 1, 2, 1);
    assertThat(runsByItem).containsExactly(1, 1, 2, 1, 3, 1);
    assertThat(replyInput(events)).isEqualTo(ImmutableList.of(1, 20, 30));
  }

  @Test
  public void runNode_disposed_cancelsRunningItems() throws Exception {
    CountDownLatch started = new CountDownLatch(2);
    CountDownLatch cancelled = new CountDownLatch(2);
    Node worker =
        node(
            item ->
                Flowable.timer(10, SECONDS)
                    .doOnSubscribe(unused -> started.countDown())
                    .doOnCancel(cancelled::countDown));
    TestObserver<Context> observer =
        start(ParallelWorkers.parallelNode(worker), ImmutableList.of(1, 2)).test();
    assertThat(started.await(10, SECONDS)).isTrue();

    observer.dispose();

    observer.assertNoErrors();
    assertThat(cancelled.await(10, SECONDS)).isTrue();
  }

  @Test
  public void runNode_textContentWithJsonList_fansOutOverItsElements() {
    Run run =
        run(ParallelWorkers.parallelNode(doubler()), Content.fromParts(Part.fromText("[1, 2, 3]")));

    assertThat(run.output()).isEqualTo(ImmutableList.of(2, 4, 6));
  }

  @Test
  public void runNode_textContentWithJsonScalar_runsOnTheParsedValue() {
    Run run = run(ParallelWorkers.parallelNode(doubler()), Content.fromParts(Part.fromText("5")));

    assertThat(run.output()).isEqualTo(ImmutableList.of(10));
  }

  @Test
  public void runNode_textThatIsNotJson_runsAsOneRawTextItem() {
    Run run = run(ParallelWorkers.parallelNode(echo()), Content.fromParts(Part.fromText("hello")));

    assertThat(run.output()).isEqualTo(ImmutableList.of("hello"));
  }

  @Test
  public void runNode_contentWithNonTextPart_runsAsOneItem() {
    Content message =
        Content.fromParts(Part.fromText("[1, 2]"), Part.fromFunctionCall("f", ImmutableMap.of()));

    Run run = run(ParallelWorkers.parallelNode(echo()), message);

    assertThat(run.output()).isEqualTo(ImmutableList.of(message));
  }

  @Test
  public void runNode_contentWithAThoughtBeforeAJsonList_fansOutOverTheList() {
    Content message =
        Content.fromParts(
            Part.builder().text("Listing the numbers.").thought(true).build(),
            Part.fromText("[1, 2, 3]"));

    Run run = run(ParallelWorkers.parallelNode(doubler()), message);

    assertThat(run.output()).isEqualTo(ImmutableList.of(2, 4, 6));
  }

  @Test
  public void runNode_jsonObjectTextForAStringItem_staysRawText() {
    Node worker = echoTaking(Schema.builder().type("STRING").build());

    Run run =
        run(ParallelWorkers.parallelNode(worker), Content.fromParts(Part.fromText("{\"a\":1}")));

    assertThat(run.output()).isEqualTo(ImmutableList.of("{\"a\":1}"));
  }

  @Test
  public void runNode_jsonObjectTextForAnObjectItem_getsTheParsedMap() {
    Node worker = echoTaking(Schema.builder().type("OBJECT").build());

    Run run =
        run(ParallelWorkers.parallelNode(worker), Content.fromParts(Part.fromText("{\"a\":1}")));

    assertThat(run.output()).isEqualTo(ImmutableList.of(ImmutableMap.of("a", 1)));
  }

  @Test
  public void runNode_jsonObjectTextForAnItemTakingAStringThroughAnyOf_staysRawText() {
    Node worker =
        echoTaking(
            Schema.builder()
                .anyOf(
                    ImmutableList.of(
                        Schema.builder().type("INTEGER").build(),
                        Schema.builder().type("STRING").build()))
                .build());

    Run run =
        run(ParallelWorkers.parallelNode(worker), Content.fromParts(Part.fromText("{\"a\":1}")));

    assertThat(run.output()).isEqualTo(ImmutableList.of("{\"a\":1}"));
  }

  @Test
  public void runNode_jsonListTextForAStringItem_fansOutOverTheList() {
    Node worker = echoTaking(Schema.builder().type("STRING").build());

    Run run =
        run(
            ParallelWorkers.parallelNode(worker),
            Content.fromParts(Part.fromText("[\"a\", \"b\"]")));

    assertThat(run.output()).isEqualTo(ImmutableList.of("a", "b"));
  }

  /** The outcome of running a node as the root of a workflow run. */
  private record Run(Context context, ImmutableList<Event> events) {
    @Nullable Object output() {
      return context.requireNodeState().output().orElse(null);
    }

    Optional<Exception> failure() {
      return context.requireNodeState().failure();
    }

    ImmutableSet<String> interruptIds() {
      return context.requireNodeState().interruptIds();
    }

    ImmutableList<Event> eventsUnder(String pathPrefix) {
      return events.stream()
          .filter(event -> pathOf(event).startsWith(pathPrefix))
          .collect(toImmutableList());
    }
  }

  private static Run run(Node node, @Nullable Object nodeInput) {
    List<Event> events = new CopyOnWriteArrayList<>();
    Context context = start(node, nodeInput, events).blockingGet();
    return new Run(context, ImmutableList.copyOf(events));
  }

  /** Returns the run of {@code node} as the root of a workflow run, ignoring its events. */
  private static Single<Context> start(Node node, @Nullable Object nodeInput) {
    return start(node, nodeInput, new CopyOnWriteArrayList<>());
  }

  /**
   * Returns the run of {@code node} as the root of a workflow run, adding its events to {@code
   * events}.
   */
  private static Single<Context> start(Node node, @Nullable Object nodeInput, List<Event> events) {
    return NodeRunner.builder()
        .node(node)
        .invocationContext(TestUtils.createInvocationContext(TestUtils.createRootAgent()))
        .eventSink((event, nodeFailure) -> Completable.fromAction(() -> events.add(event)))
        .build()
        .run(nodeInput);
  }

  /** Returns a plain node named "worker", not a {@link BaseNode}, that runs {@code body}. */
  private static Node node(Function<Object, Flowable<?>> body) {
    return new TestNode("worker", (context, item) -> Flowable.defer(() -> body.apply(item)));
  }

  /** Returns a node named "worker" with {@code config} that runs {@code body}. */
  private static Node node(NodeConfig config, Function<Object, Flowable<?>> body) {
    return new Node() {
      @Override
      public String name() {
        return "worker";
      }

      @Override
      public NodeConfig config() {
        return config;
      }

      @Override
      public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
        return Flowable.defer(() -> body.apply(nodeInput));
      }
    };
  }

  /** Returns a node named "worker" that takes {@code inputSchema} and outputs its input. */
  private static Node echoTaking(Schema inputSchema) {
    return new Node() {
      @Override
      public String name() {
        return "worker";
      }

      @Override
      public Optional<Schema> inputSchema() {
        return Optional.of(inputSchema);
      }

      @Override
      public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
        return Flowable.just(nodeInput);
      }
    };
  }

  private static Node doubler() {
    return node(item -> Flowable.just((Integer) item * 2));
  }

  private static Node echo() {
    return node(Flowable::just);
  }

  private static Node failsOn(Object bad) {
    return node(
        item ->
            item.equals(bad)
                ? Flowable.error(new IllegalStateException("bad item " + item))
                : Flowable.just(item));
  }

  private static Node interruptsOn(Object asking) {
    return node(
        item ->
            item.equals(asking) ? Flowable.just(interrupt("ask-" + item)) : Flowable.just(item));
  }

  /** Waits for {@code latch} to be released, failing the item if it never is. */
  private static void awaitRelease(CountDownLatch latch) throws InterruptedException {
    if (!latch.await(10, SECONDS)) {
      throw new IllegalStateException("The latch was not released.");
    }
  }
}
