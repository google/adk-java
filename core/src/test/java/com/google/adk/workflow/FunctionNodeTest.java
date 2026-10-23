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

import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.Context;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests {@link FunctionNode} through a {@link Runner}, with the workflow as the app's root. */
@RunWith(JUnit4.class)
public final class FunctionNodeTest {

  private static final String APP = "app";
  private static final String USER = "user";

  record Order(String id, int quantity) {}

  @Test
  public void of_recordPortFedAMap_readsTheRecord() {
    // Arrange: a map output feeds a record port.
    FunctionNode<Content, Object> makeOrder =
        FunctionNodes.of(
            "make_order",
            Content.class,
            Object.class,
            (ctx, message) -> ImmutableMap.of("id", "a1", "quantity", 3));
    FunctionNode<Order, String> describe =
        FunctionNodes.of(
            "describe",
            Order.class,
            String.class,
            (ctx, order) -> order.id() + " x" + order.quantity());

    // Act
    RunResult result = run(chain(makeOrder, describe));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("a1 x3");
    assertThat(result.outputAt("wf@1/make_order@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3L));
  }

  @Test
  public void of_recordPort_readsTheSameRecordFromTheStoredJsonForm() {
    // Arrange: a session read back from JSON hands a successor its own number types.
    FunctionNode<Content, Order> makeOrder =
        FunctionNodes.of(
            "make_order", Content.class, Order.class, (ctx, message) -> new Order("a1", 3));
    FunctionNode<Order, String> describe =
        FunctionNodes.of(
            "describe",
            Order.class,
            String.class,
            (ctx, order) -> order.id() + " x" + order.quantity());
    RunResult live = run(chain(makeOrder, describe));
    Event liveOutput = live.outputEventAt("wf@1/make_order@1");
    Object stored = Event.fromJson(liveOutput.toJson()).output().get();
    Node replay = rawOutput("make_order", stored);

    // Act
    RunResult fromStored = run(chain(replay, describe));

    // Assert
    fromStored.assertCompleted();
    assertThat(fromStored.outputAt("wf@1/describe@1")).isEqualTo(live.outputAt("wf@1/describe@1"));
  }

  @Test
  public void of_primitivePort_readsTheBoxedValue() {
    // Arrange
    FunctionNode<Content, Integer> three =
        FunctionNodes.of("three", Content.class, Integer.class, (ctx, message) -> 3);
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.of("increment", int.class, Integer.class, (ctx, n) -> n + 1);

    // Act
    RunResult result = run(chain(three, increment));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/increment@1")).isEqualTo(4L);
  }

  @Test
  public void of_bodyReturningAContent_outputsItsJsonForm() {
    // Arrange
    FunctionNode<Content, Content> reply =
        FunctionNodes.of(
            "reply",
            Content.class,
            Content.class,
            (ctx, message) -> Content.fromParts(Part.fromText("hi")));

    // Act
    RunResult result = run(chain(reply));

    // Assert: a returned Content is the output, not a message.
    result.assertCompleted();
    Event event = result.outputEventAt("wf@1/reply@1");
    assertThat(event.content()).isEmpty();
    assertThat(event.output())
        .hasValue(
            ImmutableMap.of(
                "parts", ImmutableList.of(ImmutableMap.of("text", "hi")), "role", "user"));
  }

  @Test
  public void of_bodyReturningAnEventWithARecordOutput_sendsItsJsonForm() {
    // Arrange
    FunctionNode<Content, Event> order =
        FunctionNodes.of(
            "order",
            Content.class,
            Event.class,
            (ctx, message) -> Event.builder().output(new Order("a1", 3)).build());

    // Act
    RunResult result = run(chain(order));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/order@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3L));
  }

  @Test
  public void of_bodyThatThrows_failsTheNodeWithItsException() {
    // Arrange
    FunctionNode<Content, String> broken =
        FunctionNodes.of(
            "broken",
            Content.class,
            String.class,
            (ctx, message) -> {
              throw new IOException("down");
            });

    // Act
    RunResult result = run(chain(broken));

    // Assert
    Event error =
        result.events().stream().filter(e -> e.errorCode().isPresent()).collect(onlyElement());
    assertThat(error.errorCode()).hasValue(new FinishReason("IOException"));
    assertThat(error.errorMessage()).hasValue("down");
  }

  @Test
  public void of_blockingSiblingsWaitingForEachOther_bothFinish() {
    // Arrange: each body blocks until the other has started, which needs two threads.
    CountDownLatch leftStarted = new CountDownLatch(1);
    CountDownLatch rightStarted = new CountDownLatch(1);
    AtomicBoolean leftSawRight = new AtomicBoolean();
    AtomicBoolean rightSawLeft = new AtomicBoolean();
    FunctionNode<Content, Void> left =
        FunctionNodes.of(
            "left",
            Content.class,
            Void.class,
            (ctx, message) -> {
              leftStarted.countDown();
              leftSawRight.set(rightStarted.await(5, TimeUnit.SECONDS));
              return null;
            });
    FunctionNode<Content, Void> right =
        FunctionNodes.of(
            "right",
            Content.class,
            Void.class,
            (ctx, message) -> {
              rightStarted.countDown();
              rightSawLeft.set(leftStarted.await(5, TimeUnit.SECONDS));
              return null;
            });
    Workflow workflow =
        Workflow.builder()
            .name("wf")
            .edges(new Edge(Start.INSTANCE, left), new Edge(Start.INSTANCE, right))
            .build();

    // Act
    RunResult result = run(workflow);

    // Assert
    result.assertCompleted();
    assertThat(leftSawRight.get()).isTrue();
    assertThat(rightSawLeft.get()).isTrue();
  }

  @Test
  @SuppressWarnings("rawtypes") // A Class port for a map is raw.
  public void of_mapPortFedAMap_getsTheJsonObject() {
    // Arrange: a map output feeds a map port.
    FunctionNode<Content, Object> makeParams =
        FunctionNodes.of(
            "make_params",
            Content.class,
            Object.class,
            (ctx, message) -> ImmutableMap.of("p1", "value1", "p2", 100));
    FunctionNode<Map, String> describe =
        FunctionNodes.of(
            "describe",
            Map.class,
            String.class,
            (ctx, params) -> "p1=" + params.get("p1") + ", p2=" + params.get("p2"));

    // Act
    RunResult result = run(chain(makeParams, describe));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("p1=value1, p2=100");
  }

  @Test
  public void of_recordOutput_isItsJsonForm() {
    // Arrange
    FunctionNode<Content, Order> makeOrder =
        FunctionNodes.of(
            "make_order", Content.class, Order.class, (ctx, message) -> new Order("a1", 3));

    // Act
    RunResult result = run(chain(makeOrder));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/make_order@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3L));
  }

  @Test
  public void of_bodiesReturningAValueOrNothing_outputWhatTheyReturn() {
    // Arrange
    FunctionNode<Content, String> hello =
        FunctionNodes.of("hello", Content.class, String.class, (ctx, message) -> "Hello");
    FunctionNode<String, Void> silent =
        FunctionNodes.of("silent", String.class, Void.class, (ctx, text) -> null);

    // Act
    RunResult result = run(chain(hello, silent));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/hello@1")).isEqualTo("Hello");
    assertThat(result.eventsAt("wf@1/silent@1")).isEmpty();
  }

  @Test
  @SuppressWarnings("rawtypes") // A Class port for a list is raw.
  public void of_listPortWithoutItemType_getsTheJsonList() {
    // Arrange
    FunctionNode<Content, List> makeList =
        FunctionNodes.of(
            "make_list", Content.class, List.class, (ctx, message) -> ImmutableList.of(1, 2));
    FunctionNode<List, String> describe =
        FunctionNodes.of("describe", List.class, String.class, (ctx, list) -> "list=" + list);

    // Act
    RunResult result = run(chain(makeList, describe));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("list=[1, 2]");
  }

  @Test
  public void of_contentPort_getsTheUserMessage() {
    // Arrange
    FunctionNode<Content, String> echo =
        FunctionNodes.of("echo", Content.class, String.class, (ctx, message) -> message.text());

    // Act
    RunResult result = run(chain(echo));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/echo@1")).isEqualTo("go");
  }

  @Test
  public void of_bodyReturningARouteEvent_sendsItWithoutAnOutput() {
    // Arrange
    FunctionNode<Content, Event> route =
        FunctionNodes.of(
            "route",
            Content.class,
            Event.class,
            (ctx, message) ->
                Event.builder()
                    .actions(
                        EventActions.builder()
                            .route(ImmutableList.of(new Route.Tag("some_route")))
                            .build())
                    .build());

    // Act
    RunResult result = run(chain(route));

    // Assert
    result.assertCompleted();
    Event event = result.eventsAt("wf@1/route@1").stream().collect(onlyElement());
    assertThat(event.output()).isEmpty();
    assertThat(event.actions().route()).hasValue(ImmutableList.of(new Route.Tag("some_route")));
  }

  @Test
  public void of_bodyReturningAContentEvent_sendsTheMessageWithoutAnOutput() {
    // Arrange
    FunctionNode<Content, Event> say =
        FunctionNodes.of(
            "say",
            Content.class,
            Event.class,
            (ctx, message) ->
                Event.builder().content(Content.fromParts(Part.fromText("some content"))).build());

    // Act
    RunResult result = run(chain(say));

    // Assert
    result.assertCompleted();
    Event event = result.eventsAt("wf@1/say@1").stream().collect(onlyElement());
    assertThat(event.output()).isEmpty();
    assertThat(event.content().get().text()).isEqualTo("some content");
  }

  @Test
  public void of_bodySettingStateAndReturningNothing_sendsTheStateDelta() {
    // Arrange
    FunctionNode<Content, Void> write =
        FunctionNodes.of(
            "write",
            Content.class,
            Void.class,
            (ctx, message) -> {
              ctx.state().put("my_key", "my_value");
              return null;
            });
    FunctionNode<Void, String> read =
        FunctionNodes.of(
            "read",
            Void.class,
            String.class,
            (ctx, ignored) -> "my_key=" + ctx.state().get("my_key"));

    // Act
    RunResult result = run(chain(write, read));

    // Assert
    result.assertCompleted();
    Event written = result.eventsAt("wf@1/write@1").stream().collect(onlyElement());
    assertThat(written.actions().stateDelta()).containsExactly("my_key", "my_value");
    assertThat(written.output()).isEmpty();
    assertThat(result.outputAt("wf@1/read@1")).isEqualTo("my_key=my_value");
  }

  @Test
  public void of_bodySettingStateAndReturningAnEvent_mergesBothStateDeltas() {
    // Arrange
    FunctionNode<Content, Event> write =
        FunctionNodes.of(
            "write",
            Content.class,
            Event.class,
            (ctx, message) -> {
              ctx.state().put("from_ctx", "ctx_value");
              return Event.builder()
                  .output("result")
                  .actions(
                      EventActions.builder()
                          .stateDelta(new HashMap<>(ImmutableMap.of("from_event", "event_value")))
                          .build())
                  .build();
            });

    // Act
    RunResult result = run(chain(write));

    // Assert
    result.assertCompleted();
    Event written = result.eventsAt("wf@1/write@1").stream().collect(onlyElement());
    assertThat(written.output()).hasValue("result");
    assertThat(written.actions().stateDelta())
        .containsExactly("from_event", "event_value", "from_ctx", "ctx_value");
  }

  @Test
  public void of_inputThatDoesNotFitThePort_failsTheNodeWithoutQuotingTheValue() {
    // Arrange
    FunctionNode<Content, String> secret =
        FunctionNodes.of("secret", Content.class, String.class, (ctx, message) -> "s3cr3t");
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());

    // Act
    RunResult result = run(chain(secret, describe));

    // Assert
    Event error =
        result.events().stream().filter(e -> e.errorCode().isPresent()).collect(onlyElement());
    assertThat(error.errorCode()).hasValue(new FinishReason("NodeInputValidationException"));
    assertThat(error.errorMessage().get()).contains("input of node 'describe'");
    assertThat(error.errorMessage().get()).doesNotContain("s3cr3t");
  }

  @Test
  public void of_valueThatIsNotAContent_failsAContentPortWithoutQuotingTheValue() {
    // Arrange
    FunctionNode<Content, String> secret =
        FunctionNodes.of("secret", Content.class, String.class, (ctx, message) -> "s3cr3t");
    FunctionNode<Content, String> read =
        FunctionNodes.of("read", Content.class, String.class, (ctx, content) -> content.text());

    // Act
    RunResult result = run(chain(secret, read));

    // Assert
    Event error =
        result.events().stream().filter(e -> e.errorCode().isPresent()).collect(onlyElement());
    assertThat(error.errorCode()).hasValue(new FinishReason("NodeInputValidationException"));
    assertThat(error.errorMessage().get()).contains("input of node 'read'");
    assertThat(error.errorMessage().get()).doesNotContain("s3cr3t");
  }

  @Test
  public void of_body_runsOnTheRunnersScheduler() {
    // Arrange
    ExecutorService executor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "app-scheduler"));
    FunctionNode<Content, String> where =
        FunctionNodes.of(
            "where",
            Content.class,
            String.class,
            (ctx, message) -> Thread.currentThread().getName());

    // Act
    RunResult result;
    try {
      result = run(chain(where), Schedulers.from(executor));
    } finally {
      executor.shutdownNow();
    }

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/where@1")).isEqualTo("app-scheduler");
  }

  @Test
  public void toBuilder_copy_keepsThePortsAndTheBody() {
    // Arrange
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());
    FunctionNode<Content, Object> makeOrder =
        FunctionNodes.of(
            "make_order",
            Content.class,
            Object.class,
            (ctx, message) -> ImmutableMap.of("id", "a1", "quantity", 3));

    // Act
    RunResult result = run(chain(makeOrder, describe.toBuilder().name("copy").build()));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/copy@1")).isEqualTo("a1");
  }

  @Test
  public void builder_withoutABody_failsAtBuild() {
    // Arrange
    FunctionNode.Builder<String, String> builder =
        FunctionNodes.builder(String.class, String.class).name("empty");

    // Act
    IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);

    // Assert
    assertThat(error).hasMessageThat().contains("requires body");
  }

  /** Returns a node that emits {@code output} as it is, as a hand-written node does. */
  private static Node rawOutput(String name, Object output) {
    return new Node() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
        return Flowable.just(output);
      }
    };
  }

  /** Returns a workflow named {@code wf} that runs {@code nodes} in sequence from START. */
  private static Workflow chain(Node... nodes) {
    List<Edge> edges = new ArrayList<>();
    Node previous = Start.INSTANCE;
    for (Node node : nodes) {
      edges.add(new Edge(previous, node));
      previous = node;
    }
    return Workflow.builder().name("wf").edges(edges.toArray(new Edge[0])).build();
  }

  private static RunResult run(Node root) {
    return run(root, null);
  }

  /** Runs {@code root} as an app's root node through a {@link Runner} on a "go" message. */
  private static RunResult run(Node root, @Nullable Scheduler scheduler) {
    Runner runner =
        Runner.builder()
            .app(App.builder().name(APP).rootNode(root).build())
            .scheduler(scheduler)
            .build();
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    List<Event> events = new ArrayList<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    runner
        .runAsync(USER, session.id(), Content.fromParts(Part.fromText("go")))
        .timeout(30, TimeUnit.SECONDS)
        .blockingSubscribe(events::add, error::set);
    Session stored =
        runner.sessionService().getSession(APP, USER, session.id(), Optional.empty()).blockingGet();
    return new RunResult(ImmutableList.copyOf(events), error.get(), stored);
  }

  /** The events a run streamed, the error it failed with, if any, and the session it stored. */
  private record RunResult(
      ImmutableList<Event> events, @Nullable Throwable error, Session session) {

    void assertCompleted() {
      assertWithMessage("the run's error").that(error).isNull();
    }

    ImmutableList<Event> eventsAt(String nodePath) {
      return events.stream()
          .filter(e -> pathOf(e).equals(nodePath))
          .collect(ImmutableList.toImmutableList());
    }

    @Nullable Object outputAt(String nodePath) {
      return outputEventAt(nodePath).output().get();
    }

    Event outputEventAt(String nodePath) {
      return events.stream()
          .filter(e -> pathOf(e).equals(nodePath) && e.output().isPresent())
          .collect(onlyElement());
    }

    private static String pathOf(Event event) {
      return event.nodeInfo().map(NodeInfo::path).orElse("");
    }
  }
}
