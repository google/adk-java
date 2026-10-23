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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
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

  record Section(@JsonProperty("section_name") String sectionName, String content) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
  @JsonSubTypes(@JsonSubTypes.Type(value = Circle.class, name = "circle"))
  interface Shape {}

  record Circle(double radius) implements Shape {}

  /** An interface port type without a creator, which has no single JSON shape. */
  interface Decision {}

  /** An Immutables-style value type: abstract, read through the concrete class it names. */
  @JsonDeserialize(as = SimpleRange.class)
  abstract static class Range {
    @JsonProperty("low")
    abstract int low();

    @JsonProperty("high")
    abstract int high();
  }

  static final class SimpleRange extends Range {
    private final int low;
    private final int high;

    @JsonCreator
    SimpleRange(@JsonProperty("low") int low, @JsonProperty("high") int high) {
      this.low = low;
      this.high = high;
    }

    @Override
    int low() {
      return low;
    }

    @Override
    int high() {
      return high;
    }
  }

  /** An AutoValue-style value type: abstract, with a static creator. */
  abstract static class Point {
    @JsonProperty("x")
    abstract int x();

    @JsonCreator
    static Point create(@JsonProperty("x") int x) {
      return new AutoPoint(x);
    }
  }

  static final class AutoPoint extends Point {
    private final int x;

    AutoPoint(int x) {
      this.x = x;
    }

    @Override
    int x() {
      return x;
    }
  }

  /** A value type that Jackson writes as a single string through {@code @JsonValue}. */
  static final class OrderId {
    private final String value;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    OrderId(String value) {
      this.value = value;
    }

    @JsonValue
    String value() {
      return value;
    }
  }

  /** A class Jackson writes as an object and reads through a delegating map creator. */
  static final class Bag {
    private final ImmutableMap<String, Object> values;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    Bag(Map<String, Object> values) {
      this.values = ImmutableMap.copyOf(values);
    }

    public Object getName() {
      return values.get("name");
    }
  }

  /** A class Jackson reads but cannot write, since it has no getters. */
  static final class Token {
    @SuppressWarnings("unused")
    private final String secret;

    @JsonCreator
    Token(@JsonProperty("secret") String secret) {
      this.secret = secret;
    }
  }

  /** A class Jackson cannot create: its only constructor takes unannotated parameters. */
  static final class NoCreator {
    public final String label;

    public NoCreator(String label, int unused) {
      this.label = label;
    }
  }

  /** A class Jackson cannot write, since it has no properties. */
  static final class NoProperties {}

  /** A class Jackson writes as an object but can create only from a string. */
  static final class StringOnly {
    private final String value;

    StringOnly(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

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

  @Test
  public void of_listOfRecordsPort_readsEachElementAsARecord() {
    // Arrange: a list of maps feeds a list-of-records port.
    AtomicReference<List<Section>> received = new AtomicReference<>();
    FunctionNode<Content, Object> upstream =
        FunctionNodes.of(
            "upstream",
            Content.class,
            Object.class,
            (ctx, message) ->
                ImmutableList.of(
                    ImmutableMap.of("section_name", "s1", "content", "c1"),
                    ImmutableMap.of("section_name", "s2", "content", "c2")));
    FunctionNode<List<Section>, String> aggregate =
        FunctionNodes.of(
            "aggregate",
            new TypeReference<List<Section>>() {},
            String.class,
            (ctx, sections) -> {
              received.set(sections);
              return "Done";
            });

    // Act
    RunResult result = run(chain(upstream, aggregate));

    // Assert
    result.assertCompleted();
    assertThat(received.get())
        .containsExactly(new Section("s1", "c1"), new Section("s2", "c2"))
        .inOrder();
  }

  @Test
  public void of_mapOfRecordsPort_readsEachValueAsARecord() {
    // Arrange: a map of maps feeds a map-of-records port.
    AtomicReference<Map<String, Section>> received = new AtomicReference<>();
    FunctionNode<Content, Object> upstream =
        FunctionNodes.of(
            "upstream",
            Content.class,
            Object.class,
            (ctx, message) ->
                ImmutableMap.of(
                    "one", ImmutableMap.of("section_name", "s1", "content", "c1"),
                    "two", ImmutableMap.of("section_name", "s2", "content", "c2")));
    FunctionNode<Map<String, Section>, String> aggregate =
        FunctionNodes.builder(new TypeReference<Map<String, Section>>() {}, String.class)
            .name("aggregate")
            .body(
                (ctx, sections) -> {
                  received.set(sections);
                  return "Done";
                })
            .build();

    // Act
    RunResult result = run(chain(upstream, aggregate));

    // Assert
    result.assertCompleted();
    assertThat(received.get())
        .containsExactly("one", new Section("s1", "c1"), "two", new Section("s2", "c2"));
  }

  @Test
  public void of_numberIntoAListPort_failsTheNode() {
    // Arrange: a number feeds a list port.
    FunctionNode<Content, Integer> number =
        FunctionNodes.of("number", Content.class, Integer.class, (ctx, message) -> 123);
    FunctionNode<List<Section>, String> first =
        FunctionNodes.of(
            "first",
            new TypeReference<List<Section>>() {},
            String.class,
            (ctx, sections) -> sections.get(0).sectionName());

    // Act
    RunResult result = run(chain(number, first));

    // Assert
    Event error =
        result.events().stream().filter(e -> e.errorCode().isPresent()).collect(onlyElement());
    assertThat(error.errorCode()).hasValue(new FinishReason("NodeInputValidationException"));
  }

  @Test
  public void of_polymorphicPort_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Shape.class, String.class, (ctx, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().contains("is polymorphic (@JsonTypeInfo)");
  }

  @Test
  public void of_numberPort_failsAtBuildSuggestingAConcreteNumber() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Number.class, String.class, (ctx, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().contains("Declare the port as Long, Double or BigDecimal");
  }

  @Test
  public void of_charSequencePort_failsAtBuildSuggestingString() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", CharSequence.class, String.class, (ctx, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().contains("Declare the port as String");
  }

  @Test
  public void of_outputTypeWithoutProperties_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Content.class, NoProperties.class, (ctx, in) -> null));

    // Assert
    assertThat(error).hasMessageThat().contains("cannot write as JSON");
  }

  @Test
  public void of_inputTypeCreatableOnlyFromAString_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", StringOnly.class, String.class, (ctx, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().contains("only from a string or number");
  }

  @Test
  public void builder_mapPortWithAnUnreadableKey_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.builder(new TypeReference<Map<Order, String>>() {}, String.class));

    // Assert
    assertThat(error).hasMessageThat().contains("cannot read as a map key");
  }

  @Test
  public void of_abstractTypesWithACreator_roundTrip() {
    // Arrange
    FunctionNode<Content, Range> pick =
        FunctionNodes.of("pick", Content.class, Range.class, (ctx, m) -> new SimpleRange(2, 5));
    FunctionNode<Range, Point> place =
        FunctionNodes.of(
            "place", Range.class, Point.class, (ctx, r) -> Point.create(r.high() - r.low()));
    FunctionNode<Point, Integer> readX =
        FunctionNodes.of("read_x", Point.class, Integer.class, (ctx, point) -> point.x());

    // Act
    RunResult result = run(chain(pick, place, readX));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/pick@1")).isEqualTo(ImmutableMap.of("low", 2L, "high", 5L));
    assertThat(result.outputAt("wf@1/read_x@1")).isEqualTo(3L);
  }

  @Test
  public void of_valueTypeWrittenAsAString_roundTrips() {
    // Arrange
    FunctionNode<Content, OrderId> makeId =
        FunctionNodes.of("make_id", Content.class, OrderId.class, (ctx, m) -> new OrderId("A-1"));
    FunctionNode<OrderId, String> readId =
        FunctionNodes.of("read_id", OrderId.class, String.class, (ctx, id) -> "id " + id.value());

    // Act
    RunResult result = run(chain(makeId, readId));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/make_id@1")).isEqualTo("A-1");
    assertThat(result.outputAt("wf@1/read_id@1")).isEqualTo("id A-1");
  }

  @Test
  public void of_genericOutputPort_writesEachElementInJsonForm() {
    // Arrange
    FunctionNode<Content, List<Section>> sections =
        FunctionNodes.of(
            "sections",
            Content.class,
            new TypeReference<List<Section>>() {},
            (ctx, m) -> ImmutableList.of(new Section("s1", "c1")));

    // Act
    RunResult result = run(chain(sections));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/sections@1"))
        .isEqualTo(ImmutableList.of(ImmutableMap.of("section_name", "s1", "content", "c1")));
  }

  @Test
  public void of_inputTypeJacksonCannotCreate_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", NoCreator.class, String.class, (ctx, in) -> in.label));

    // Assert
    assertThat(error).hasMessageThat().contains("cannot create from JSON");
  }

  @Test
  public void of_inputTypeJacksonCannotWrite_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Token.class, String.class, (ctx, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().contains("The input of node 'n'");
    assertThat(error).hasMessageThat().contains("cannot write as JSON");
  }

  @Test
  public void of_listOfAnUncreatableType_failsAtBuildNamingTheElement() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () ->
                FunctionNodes.of(
                    "n", new TypeReference<List<NoCreator>>() {}, String.class, (ctx, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().startsWith("An element of the input of node 'n'");
  }

  @Test
  public void of_optionalPort_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () ->
                FunctionNodes.of(
                    "n", new TypeReference<Optional<String>>() {}, String.class, (ctx, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().contains("Declare it without Optional");
  }

  @Test
  public void of_listOfOptionals_failsAtBuildNamingTheElement() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () ->
                FunctionNodes.of(
                    "n",
                    new TypeReference<List<Optional<Order>>>() {},
                    String.class,
                    (ctx, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().startsWith("An element of the input of node 'n'");
    assertThat(error).hasMessageThat().contains("Declare it without Optional");
  }

  @Test
  public void of_typeReadThroughADelegatingMapCreator_roundTrips() {
    // Arrange
    FunctionNode<Content, Bag> makeBag =
        FunctionNodes.of(
            "make_bag",
            Content.class,
            Bag.class,
            (ctx, m) -> new Bag(ImmutableMap.of("name", "x")));
    FunctionNode<Bag, String> readBag =
        FunctionNodes.of("read_bag", Bag.class, String.class, (ctx, bag) -> "bag " + bag.getName());

    // Act
    RunResult result = run(chain(makeBag, readBag));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/make_bag@1")).isEqualTo(ImmutableMap.of("name", "x"));
    assertThat(result.outputAt("wf@1/read_bag@1")).isEqualTo("bag x");
  }

  @Test
  public void of_interfaceOutputWithoutACreator_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", String.class, Decision.class, (ctx, in) -> null));

    // Assert
    assertThat(error)
        .hasMessageThat()
        .contains("interface or abstract class without a Jackson creator");
  }

  @Test
  public void of_untypedAndAdkPorts_needNoJacksonShape() {
    // Arrange
    FunctionNode<Content, Object> echo =
        FunctionNodes.of("echo", Content.class, Object.class, (ctx, message) -> message.text());
    FunctionNode<Object, Void> sink =
        FunctionNodes.of("sink", Object.class, Void.class, (ctx, in) -> null);

    // Act
    RunResult result = run(chain(echo, sink));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/echo@1")).isEqualTo("go");
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
