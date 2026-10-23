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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.google.adk.agents.Context;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private static final Schema ANY = Schema.builder().build();
  private static final Schema STRING = typed(Type.Known.STRING);
  private static final Schema INTEGER = typed(Type.Known.INTEGER);
  private static final Schema ORDER =
      object(ImmutableMap.of("id", STRING, "quantity", INTEGER), "id", "quantity");

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

  record Note(
      String title,
      int pages,
      Optional<String> subtitle,
      @Nullable String body,
      @OtherNullness.Nullable String footer) {}

  /** A record whose JSON form renames one property, ignores one and does not require two. */
  record Draft(
      @JsonProperty("section_name") String sectionName,
      @JsonProperty(isRequired = OptBoolean.FALSE) String content,
      @JsonProperty(isRequired = OptBoolean.FALSE) int priority,
      @JsonIgnore String notes) {}

  enum Status {
    OPEN,
    SHIPPED
  }

  record Shipment(Status status, Order order, List<Order> history, Map<String, Order> byId) {}

  enum Color {
    @JsonProperty("red")
    RED,
    @JsonProperty("dark-blue")
    BLUE
  }

  /** An enum that Jackson writes as a number. */
  enum Level {
    LOW(1),
    HIGH(2);

    private final int code;

    Level(int code) {
      this.code = code;
    }

    @JsonValue
    int code() {
      return code;
    }
  }

  /** A record that also writes the entries of a map as keys of its own. */
  record Tagged(String name) {
    @JsonAnyGetter
    ImmutableMap<String, Object> extra() {
      return ImmutableMap.of("k", 1);
    }
  }

  /** A class whose any-setter takes keys it does not declare. */
  static final class Catchall {
    public String id;

    @JsonAnySetter
    void put(String key, Object value) {}
  }

  /** A class with two any-setters, which Jackson rejects. */
  static final class TwoCatchalls {
    public String id;

    @JsonAnySetter
    void put(String key, Object value) {}

    @JsonAnySetter
    void add(String key, Object value) {}
  }

  record Flat(String label, @JsonUnwrapped Order order) {}

  @JsonFormat(shape = JsonFormat.Shape.ARRAY)
  record Pair(int x, int y) {}

  /** A polymorphic base type with a property of its own. */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
  abstract static class Pet {
    public String name;
  }

  @JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class)
  record Ref(String label) {}

  /** A record whose properties have no fixed JSON type. */
  record Irregular(
      @JsonSerialize(using = ToStringSerializer.class) long id,
      @JsonSerialize(contentUsing = ToStringSerializer.class) List<Integer> codes,
      @JsonFormat(shape = JsonFormat.Shape.STRING) int count,
      @JsonTypeInfo(use = JsonTypeInfo.Id.NAME) Order typed,
      @JsonTypeInfo(use = JsonTypeInfo.Id.NAME) List<Order> typedOrders,
      @JsonIdentityInfo(generator = ObjectIdGenerators.IntSequenceGenerator.class)
          @JsonIdentityReference(alwaysAsId = true)
          Order byId,
      Tagged tagged,
      Catchall catchall,
      TwoCatchalls twoCatchalls,
      Flat flat,
      Pair pair,
      Ref ref,
      Pet pet,
      Level level,
      Instant at,
      Object raw,
      List<Pet> pets) {}

  record Measure(Number amount, CharSequence unit) {}

  record FullOrder(String id, double total, String note) {}

  record BasicOrder(String id, double total) {}

  record TreeNode(String label, List<TreeNode> children) {}

  record Box<T>(T item) {}

  /** Holds a declaration annotation named like the {@code @Nullable} of other packages. */
  static final class OtherNullness {
    @Retention(RetentionPolicy.RUNTIME)
    @interface Nullable {}

    private OtherNullness() {}
  }

  @Test
  public void of_recordPortFedAMap_readsTheRecord() {
    // Arrange: a map output feeds a record port (Python's input injection into a model).
    FunctionNode<Content, Object> makeOrder =
        FunctionNodes.of(
            "make_order",
            Content.class,
            Object.class,
            (scope, message) -> ImmutableMap.of("id", "a1", "quantity", 3));
    FunctionNode<Order, String> describe =
        FunctionNodes.of(
            "describe",
            Order.class,
            String.class,
            (scope, order) -> order.id() + " x" + order.quantity());

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
            "make_order", Content.class, Order.class, (scope, message) -> new Order("a1", 3));
    FunctionNode<Order, String> describe =
        FunctionNodes.of(
            "describe",
            Order.class,
            String.class,
            (scope, order) -> order.id() + " x" + order.quantity());
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
        FunctionNodes.of("three", Content.class, Integer.class, (scope, message) -> 3);
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.of("increment", int.class, Integer.class, (scope, n) -> n + 1);

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
            (scope, message) -> Content.fromParts(Part.fromText("hi")));

    // Act
    RunResult result = run(chain(reply));

    // Assert: unlike Python, a returned Content is the output, not a message.
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
            (scope, message) -> Event.builder().output(new Order("a1", 3)).build());

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
            (scope, message) -> {
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
            (scope, message) -> {
              leftStarted.countDown();
              leftSawRight.set(rightStarted.await(5, TimeUnit.SECONDS));
              return null;
            });
    FunctionNode<Content, Void> right =
        FunctionNodes.of(
            "right",
            Content.class,
            Void.class,
            (scope, message) -> {
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
    // Arrange: Python's input injection into a dict.
    FunctionNode<Content, Object> makeParams =
        FunctionNodes.of(
            "make_params",
            Content.class,
            Object.class,
            (scope, message) -> ImmutableMap.of("p1", "value1", "p2", 100));
    FunctionNode<Map, String> describe =
        FunctionNodes.of(
            "describe",
            Map.class,
            String.class,
            (scope, params) -> "p1=" + params.get("p1") + ", p2=" + params.get("p2"));

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
            "make_order", Content.class, Order.class, (scope, message) -> new Order("a1", 3));

    // Act
    RunResult result = run(chain(makeOrder));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/make_order@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3L));
  }

  @Test
  public void of_bodiesReturningAValueOrNothing_outputWhatTheyReturn() {
    // Arrange: Python's various function nodes, minus generators.
    FunctionNode<Content, String> hello =
        FunctionNodes.of("hello", Content.class, String.class, (scope, message) -> "Hello");
    FunctionNode<String, Void> silent =
        FunctionNodes.of("silent", String.class, Void.class, (scope, text) -> null);

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
            "make_list", Content.class, List.class, (scope, message) -> ImmutableList.of(1, 2));
    FunctionNode<List, String> describe =
        FunctionNodes.of("describe", List.class, String.class, (scope, list) -> "list=" + list);

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
        FunctionNodes.of("echo", Content.class, String.class, (scope, message) -> message.text());

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
            (scope, message) ->
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
            (scope, message) ->
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
            (scope, message) -> {
              scope.context().state().put("my_key", "my_value");
              return null;
            });
    FunctionNode<Void, String> read =
        FunctionNodes.of(
            "read",
            Void.class,
            String.class,
            (scope, ignored) -> "my_key=" + scope.context().state().get("my_key"));

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
            (scope, message) -> {
              scope.context().state().put("from_ctx", "ctx_value");
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
    // Arrange: an Object output has no schema, so the build leaves this edge to run time.
    FunctionNode<Content, Object> secret =
        FunctionNodes.of("secret", Content.class, Object.class, (scope, message) -> "s3cr3t");
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (scope, order) -> order.id());

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
    // Arrange: an Object output has no schema, so the build leaves this edge to run time.
    FunctionNode<Content, Object> secret =
        FunctionNodes.of("secret", Content.class, Object.class, (scope, message) -> "s3cr3t");
    FunctionNode<Content, String> read =
        FunctionNodes.of("read", Content.class, String.class, (scope, content) -> content.text());

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
            (scope, message) -> Thread.currentThread().getName());

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
        FunctionNodes.of("describe", Order.class, String.class, (scope, order) -> order.id());
    FunctionNode<Content, Object> makeOrder =
        FunctionNodes.of(
            "make_order",
            Content.class,
            Object.class,
            (scope, message) -> ImmutableMap.of("id", "a1", "quantity", 3));

    // Act
    RunResult result = run(chain(makeOrder, describe.toBuilder().name("copy").build()));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/copy@1")).isEqualTo("a1");
  }

  @Test
  public void toBuilder_copy_keepsOnlyTheExplicitSchema() {
    // Arrange
    Schema explicit = Schema.builder().type(Type.Known.OBJECT).build();
    FunctionNode<Order, Order> node =
        FunctionNodes.builder(Order.class, Order.class)
            .name("n")
            .inputSchema(explicit)
            .body((scope, order) -> order)
            .build();

    // Act
    FunctionNode<Order, Order> copy = node.toBuilder().name("copy").build();

    // Assert: the explicit schema wins over the inferred one in the node and in its copy.
    assertThat(node.inputSchema()).hasValue(explicit);
    assertThat(copy.inputSchema()).hasValue(explicit);
    assertThat(copy.inputSchemaInferred()).isFalse();
    assertThat(copy.outputSchema()).hasValue(ORDER);
    assertThat(copy.outputSchemaInferred()).isTrue();
  }

  @Test
  public void of_inputThatBreaksTheInferredSchema_stillRuns() {
    // Arrange: the inferred Order schema requires a quantity and has no note.
    Node makeOrder = rawOutput("make_order", ImmutableMap.of("id", "a1", "note", "rush"));
    FunctionNode<Order, String> describe =
        FunctionNodes.of(
            "describe",
            Order.class,
            String.class,
            (scope, order) -> order.id() + " x" + order.quantity());

    // Act
    RunResult result = run(chain(makeOrder, describe));

    // Assert: guards against enforcing inferred schemas, which only describe a port.
    assertThat(describe.inputSchemaInferred()).isTrue();
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("a1 x0");
  }

  @Test
  public void of_recordWithOrWithoutAnOptionalKey_readsTheValueOrEmpty() {
    // Arrange: the inferred Note schema does not require subtitle, so it may arrive without one.
    FunctionNode<Note, String> read =
        FunctionNodes.of(
            "read", Note.class, String.class, (scope, note) -> String.valueOf(note.subtitle()));
    Node without = rawOutput("make", ImmutableMap.of("title", "t", "pages", 1));
    Node with = rawOutput("make", ImmutableMap.of("title", "t", "pages", 1, "subtitle", "s"));

    // Act
    RunResult missing = run(chain(without, read));
    RunResult present = run(chain(with, read));

    // Assert
    missing.assertCompleted();
    present.assertCompleted();
    assertThat(missing.outputAt("wf@1/read@1")).isEqualTo("Optional.empty");
    assertThat(present.outputAt("wf@1/read@1")).isEqualTo("Optional[s]");
  }

  @Test
  public void inputSchema_scalarAndArrayTypes_mapToTheirJsonTypes() {
    // Arrange: as in ADK Kotlin, a long is an INTEGER, unlike in a tool declaration.
    Schema number = typed(Type.Known.NUMBER);
    ImmutableMap<Class<?>, Schema> expected =
        ImmutableMap.<Class<?>, Schema>builder()
            .put(String.class, STRING)
            .put(byte[].class, STRING)
            .put(char[].class, STRING)
            .put(String[].class, Schema.builder().type(Type.Known.ARRAY).items(STRING).build())
            .put(boolean.class, typed(Type.Known.BOOLEAN))
            .put(int.class, INTEGER)
            .put(long.class, INTEGER)
            .put(Double.class, number)
            .put(
                Measure.class,
                object(ImmutableMap.of("amount", number, "unit", STRING), "amount", "unit"))
            .buildOrThrow();

    // Act
    ImmutableMap<Class<?>, Schema> actual =
        expected.keySet().stream()
            .collect(toImmutableMap(type -> type, type -> inputSchema(type).orElseThrow()));

    // Assert
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  public void inputSchema_optionalOrNullableProperties_areNullableAndNotRequired() {
    // Act
    Optional<Schema> schema = inputSchema(Note.class);

    // Assert: any annotation named Nullable counts, a type-use one included.
    Schema text = nullable(STRING);
    ImmutableMap<String, Schema> properties =
        ImmutableMap.of(
            "title", STRING, "pages", INTEGER, "subtitle", text, "body", text, "footer", text);
    assertThat(schema).hasValue(object(properties, "title", "pages"));
  }

  @Test
  public void outputSchema_jacksonAnnotations_renameIgnoreOrUnrequireProperties() {
    // Act
    Optional<Schema> schema = outputSchema(Draft.class);

    // Assert: a primitive marked not required is optional too, as a missing one reads as zero.
    assertThat(schema)
        .hasValue(
            object(
                ImmutableMap.of("section_name", STRING, "content", STRING, "priority", INTEGER),
                "section_name"));
  }

  @Test
  public void inputSchema_nestedRecordEnumCollectionAndMap_describeEachLevel() {
    // Act
    Optional<Schema> shipment = inputSchema(Shipment.class);
    Optional<Schema> list = inputSchema(new TypeReference<List<Order>>() {});
    Optional<Schema> set = inputSchema(new TypeReference<Set<Order>>() {});
    Optional<Schema> objects = inputSchema(new TypeReference<List<Object>>() {});

    // Assert
    Schema status = Schema.builder().type(Type.Known.STRING).enum_("OPEN", "SHIPPED").build();
    Schema orders = Schema.builder().type(Type.Known.ARRAY).items(ORDER).build();
    ImmutableMap<String, Schema> properties =
        ImmutableMap.of(
            "status", status, "order", ORDER, "history", orders, "byId", typed(Type.Known.OBJECT));
    assertThat(shipment).hasValue(object(properties, "status", "order", "history", "byId"));
    assertThat(list).hasValue(orders);
    assertThat(set).hasValue(orders);
    assertThat(objects).hasValue(typed(Type.Known.ARRAY));
  }

  @Test
  public void inputSchema_enums_listTheValuesTheMapperWrites() {
    // Act
    Optional<Schema> color = inputSchema(Color.class);
    Optional<Schema> level = inputSchema(Level.class);

    // Assert: an enum written as a number has no string values to list, so it has no schema.
    assertThat(color)
        .hasValue(Schema.builder().type(Type.Known.STRING).enum_("red", "dark-blue").build());
    assertThat(level).isEmpty();
  }

  @Test
  public void outputSchema_propertiesOfNoFixedJsonType_takeAnyValue() {
    // Act
    Optional<Schema> irregular = outputSchema(Irregular.class);
    Optional<Schema> pair = outputSchema(Pair.class);

    // Assert: each property takes any value, but a list whose elements carry type ids is a list.
    ImmutableList<String> names =
        Arrays.stream(Irregular.class.getRecordComponents())
            .map(RecordComponent::getName)
            .collect(ImmutableList.toImmutableList());
    ImmutableMap<String, Schema> properties =
        Maps.toMap(
            names,
            name ->
                name.equals("typedOrders") || name.equals("pets") ? typed(Type.Known.ARRAY) : ANY);
    assertThat(irregular).hasValue(object(properties, names.toArray(String[]::new)));
    assertThat(pair).isEmpty();
  }

  @Test
  public void inputSchema_recursiveTypes_areEmptyUnlikeNestedGenerics() {
    // Act
    Optional<Schema> tree = inputSchema(TreeNode.class);
    Optional<Schema> box = inputSchema(new TypeReference<Box<Box<Integer>>>() {});

    // Assert: the Box<Integer> inside a Box<Box<Integer>> is another type, not a cycle.
    Schema intBox = object(ImmutableMap.of("item", INTEGER), "item");
    assertThat(tree).isEmpty();
    assertThat(box).hasValue(object(ImmutableMap.of("item", intBox), "item"));
  }

  @Test
  public void of_contentPorts_inferContentsSchemaForBothPorts() {
    // Act
    FunctionNode<Content, Content> node =
        FunctionNodes.of("n", Content.class, Content.class, (scope, content) -> content);

    // Assert: a returned Content is the output in its JSON form.
    Schema schema = node.inputSchema().orElseThrow();
    assertThat(schema.type()).hasValue(new Type(Type.Known.OBJECT));
    assertThat(schema.properties().orElseThrow()).containsEntry("role", nullable(STRING));
    assertThat(schema.properties().orElseThrow().keySet()).containsExactly("parts", "role");
    assertThat(node.outputSchema()).hasValue(schema);
    assertThat(node.inputSchemaInferred()).isTrue();
    assertThat(node.outputSchemaInferred()).isTrue();
  }

  @Test
  public void of_eventAndObjectPorts_inferASchemaOnlyForAnEventInput() {
    // Act
    FunctionNode<Event, Event> node =
        FunctionNodes.of("n", Event.class, Event.class, (scope, event) -> event);
    Optional<Schema> object = inputSchema(Object.class);

    // Assert: a returned Event is sent as it is, and an Object takes any JSON value.
    assertThat(node.inputSchema().orElseThrow().properties().orElseThrow())
        .containsEntry("author", STRING);
    assertThat(node.inputSchemaInferred()).isTrue();
    assertThat(node.outputSchema()).isEmpty();
    assertThat(node.outputSchemaInferred()).isFalse();
    assertThat(object).isEmpty();
  }

  @Test
  public void build_recordIntoAClosedRecordThatLacksOneOfItsKeys_failsNamingBothSchemas() {
    // Arrange: the inferred BasicOrder schema takes no note.
    FunctionNode<Content, FullOrder> full = fullOrder();
    FunctionNode<BasicOrder, String> basic =
        FunctionNodes.of("basic", BasicOrder.class, String.class, (scope, order) -> order.id());

    // Act
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(full, basic));

    // Assert
    assertThat(error)
        .hasMessageThat()
        .isEqualTo(
            "Schema mismatch on edge full -> basic. Output schema "
                + full.outputSchema().get().toJson()
                + " does not fit input schema "
                + basic.inputSchema().get().toJson()
                + ".");
  }

  @Test
  public void build_recordIntoAHandWrittenSchema_admitsKeysItDoesNotList() {
    // Arrange: a schema set on the builder is open, unlike an inferred one.
    FunctionNode<BasicOrder, String> basic =
        FunctionNodes.builder(BasicOrder.class, String.class)
            .name("basic")
            .inputSchema(object(ImmutableMap.of("id", STRING), "id"))
            .body((scope, order) -> order.id())
            .build();

    // Act
    RunResult result = run(chain(fullOrder(), basic));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/basic@1")).isEqualTo("a1");
  }

  @Test
  public void build_integerIntoANullableIntegerSchema_buildsAndRuns() {
    // Arrange
    FunctionNode<Content, Integer> three =
        FunctionNodes.of("three", Content.class, Integer.class, (scope, message) -> 3);
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.builder(Integer.class, Integer.class)
            .name("increment")
            .inputSchema(nullable(INTEGER))
            .body((scope, n) -> n + 1)
            .build();

    // Act
    RunResult result = run(chain(three, increment));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/increment@1")).isEqualTo(4L);
  }

  @Test
  public void build_contentIntoAStringPort_fails() {
    // Arrange: a returned Content is the output in its JSON form, an object.
    FunctionNode<String, String> shout =
        FunctionNodes.of("shout", String.class, String.class, (scope, text) -> text);

    // Act
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(reply(), shout));

    // Assert
    assertThat(error).hasMessageThat().startsWith("Schema mismatch on edge reply -> shout.");
  }

  @Test
  public void build_recordIntoAnEventPort_fails() {
    // Arrange: an Event port's inferred schema requires the keys every event has.
    FunctionNode<Event, String> author =
        FunctionNodes.of("author", Event.class, String.class, (scope, event) -> event.author());

    // Act
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(fullOrder(), author));

    // Assert
    assertThat(error).hasMessageThat().startsWith("Schema mismatch on edge full -> author.");
  }

  @Test
  public void build_contentIntoContentOrObjectPorts_buildsAndRuns() {
    // Arrange
    FunctionNode<Content, String> read =
        FunctionNodes.of("read", Content.class, String.class, (scope, content) -> content.text());
    FunctionNode<Object, String> any =
        FunctionNodes.of("any", Object.class, String.class, (scope, value) -> "ok");

    // Act
    RunResult intoContent = run(chain(reply(), read));
    RunResult intoObject = run(chain(reply(), any));

    // Assert
    intoContent.assertCompleted();
    intoObject.assertCompleted();
    assertThat(intoContent.outputAt("wf@1/read@1")).isEqualTo("hi");
    assertThat(intoObject.outputAt("wf@1/any@1")).isEqualTo("ok");
  }

  @Test
  public void build_contentPortWithAnExplicitSchema_checksTheEdgeAgainstItsContentSchema() {
    // Arrange: an explicit schema on a Content port describes the message's text.
    FunctionNode<Content, String> text =
        FunctionNodes.builder(Content.class, String.class)
            .name("text")
            .inputSchema(STRING)
            .body((scope, content) -> content.text())
            .build();

    // Act
    RunResult result = run(chain(reply(), text));
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(fullOrder(), text));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/text@1")).isEqualTo("hi");
    assertThat(error).hasMessageThat().startsWith("Schema mismatch on edge full -> text.");
  }

  @Test
  public void build_edgeIntoANodeThatIsNotAFunctionNode_checksItsOpenSchema() {
    // Arrange: such a node infers nothing, so its schema takes keys it does not list.
    FunctionNode<Content, String> text =
        FunctionNodes.of("text", Content.class, String.class, (scope, message) -> message.text());
    JoinNode wantsInteger = JoinNode.builder().name("join").inputSchema(INTEGER).build();
    JoinNode wantsId =
        JoinNode.builder()
            .name("join")
            .inputSchema(object(ImmutableMap.of("id", STRING), "id"))
            .build();

    // Act
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(text, wantsInteger));
    Workflow workflow = chain(fullOrder(), wantsId);

    // Assert
    assertThat(error).hasMessageThat().startsWith("Schema mismatch on edge text -> join.");
    assertThat(workflow.graph()).isPresent();
  }

  @Test
  public void scope_usedAfterItsBodyReturned_throws() {
    // Arrange
    AtomicReference<NodeScope> kept = new AtomicReference<>();
    FunctionNode<Content, Void> keep =
        FunctionNodes.of(
            "keep",
            Content.class,
            Void.class,
            (scope, message) -> {
              kept.set(scope);
              return null;
            });
    run(chain(keep)).assertCompleted();

    // Act
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> kept.get().context());

    // Assert
    assertThat(error).hasMessageThat().contains("only while its body runs");
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
    // Arrange: Python's list conversion into models.
    AtomicReference<List<Section>> received = new AtomicReference<>();
    FunctionNode<Content, Object> upstream =
        FunctionNodes.of(
            "upstream",
            Content.class,
            Object.class,
            (scope, message) ->
                ImmutableList.of(
                    ImmutableMap.of("section_name", "s1", "content", "c1"),
                    ImmutableMap.of("section_name", "s2", "content", "c2")));
    FunctionNode<List<Section>, String> aggregate =
        FunctionNodes.of(
            "aggregate",
            new TypeReference<List<Section>>() {},
            String.class,
            (scope, sections) -> {
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
    // Arrange: Python's dict conversion into models.
    AtomicReference<Map<String, Section>> received = new AtomicReference<>();
    FunctionNode<Content, Object> upstream =
        FunctionNodes.of(
            "upstream",
            Content.class,
            Object.class,
            (scope, message) ->
                ImmutableMap.of(
                    "one", ImmutableMap.of("section_name", "s1", "content", "c1"),
                    "two", ImmutableMap.of("section_name", "s2", "content", "c2")));
    FunctionNode<Map<String, Section>, String> aggregate =
        FunctionNodes.builder(new TypeReference<Map<String, Section>>() {}, String.class)
            .name("aggregate")
            .body(
                (scope, sections) -> {
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
    // Arrange: Python's list port fed a non-list, by an Object output the build does not check.
    FunctionNode<Content, Object> number =
        FunctionNodes.of("number", Content.class, Object.class, (scope, message) -> 123);
    FunctionNode<List<Section>, String> first =
        FunctionNodes.of(
            "first",
            new TypeReference<List<Section>>() {},
            String.class,
            (scope, sections) -> sections.get(0).sectionName());

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
            () -> FunctionNodes.of("n", Shape.class, String.class, (scope, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().contains("is polymorphic (@JsonTypeInfo)");
  }

  @Test
  public void of_numberPort_failsAtBuildSuggestingAConcreteNumber() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Number.class, String.class, (scope, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().contains("Declare the port as Long, Double or BigDecimal");
  }

  @Test
  public void of_charSequencePort_failsAtBuildSuggestingString() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", CharSequence.class, String.class, (scope, in) -> ""));

    // Assert
    assertThat(error).hasMessageThat().contains("Declare the port as String");
  }

  @Test
  public void of_outputTypeWithoutProperties_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Content.class, NoProperties.class, (scope, in) -> null));

    // Assert
    assertThat(error).hasMessageThat().contains("cannot write as JSON");
  }

  @Test
  public void of_inputTypeCreatableOnlyFromAString_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", StringOnly.class, String.class, (scope, in) -> ""));

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
        FunctionNodes.of("pick", Content.class, Range.class, (scope, m) -> new SimpleRange(2, 5));
    FunctionNode<Range, Point> place =
        FunctionNodes.of(
            "place", Range.class, Point.class, (scope, r) -> Point.create(r.high() - r.low()));
    FunctionNode<Point, Integer> readX =
        FunctionNodes.of("read_x", Point.class, Integer.class, (scope, point) -> point.x());

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
        FunctionNodes.of("make_id", Content.class, OrderId.class, (scope, m) -> new OrderId("A-1"));
    FunctionNode<OrderId, String> readId =
        FunctionNodes.of("read_id", OrderId.class, String.class, (scope, id) -> "id " + id.value());

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
            (scope, m) -> ImmutableList.of(new Section("s1", "c1")));

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
            () -> FunctionNodes.of("n", NoCreator.class, String.class, (scope, in) -> in.label));

    // Assert
    assertThat(error).hasMessageThat().contains("cannot create from JSON");
  }

  @Test
  public void of_inputTypeJacksonCannotWrite_failsAtBuild() {
    // Act
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Token.class, String.class, (scope, in) -> ""));

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
                    "n", new TypeReference<List<NoCreator>>() {}, String.class, (scope, in) -> ""));

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
                    "n",
                    new TypeReference<Optional<String>>() {},
                    String.class,
                    (scope, in) -> ""));

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
                    (scope, in) -> ""));

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
            (scope, m) -> new Bag(ImmutableMap.of("name", "x")));
    FunctionNode<Bag, String> readBag =
        FunctionNodes.of(
            "read_bag", Bag.class, String.class, (scope, bag) -> "bag " + bag.getName());

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
            () -> FunctionNodes.of("n", String.class, Decision.class, (scope, in) -> null));

    // Assert
    assertThat(error)
        .hasMessageThat()
        .contains("interface or abstract class without a Jackson creator");
  }

  @Test
  public void of_untypedAndAdkPorts_needNoJacksonShape() {
    // Arrange
    FunctionNode<Content, Object> echo =
        FunctionNodes.of("echo", Content.class, Object.class, (scope, message) -> message.text());
    FunctionNode<Object, Void> sink =
        FunctionNodes.of("sink", Object.class, Void.class, (scope, in) -> null);

    // Act
    RunResult result = run(chain(echo, sink));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/echo@1")).isEqualTo("go");
  }

  private static Optional<Schema> inputSchema(Class<?> type) {
    return FunctionNodes.of("n", type, Void.class, (scope, input) -> null).inputSchema();
  }

  private static Optional<Schema> inputSchema(TypeReference<?> type) {
    return FunctionNodes.of("n", type, Void.class, (scope, input) -> null).inputSchema();
  }

  private static Optional<Schema> outputSchema(Class<?> type) {
    return FunctionNodes.of("n", Void.class, type, (scope, input) -> null).outputSchema();
  }

  private static Schema typed(Type.Known type) {
    return Schema.builder().type(type).build();
  }

  private static Schema nullable(Schema schema) {
    return schema.toBuilder().nullable(true).build();
  }

  private static Schema object(ImmutableMap<String, Schema> properties, String... required) {
    Schema.Builder object = Schema.builder().type(Type.Known.OBJECT).properties(properties);
    return (required.length == 0 ? object : object.required(required)).build();
  }

  /** Returns a node named {@code reply} that returns a Content saying "hi". */
  private static FunctionNode<Content, Content> reply() {
    return FunctionNodes.of(
        "reply",
        Content.class,
        Content.class,
        (scope, m) -> Content.fromParts(Part.fromText("hi")));
  }

  /** Returns a node named {@code full} that returns a {@link FullOrder} with a note. */
  private static FunctionNode<Content, FullOrder> fullOrder() {
    return FunctionNodes.of(
        "full", Content.class, FullOrder.class, (scope, m) -> new FullOrder("a1", 9.5, "rush"));
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
