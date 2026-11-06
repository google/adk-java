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
import static com.google.adk.workflow.WorkflowResumeTesting.answer;
import static com.google.adk.workflow.WorkflowResumeTesting.captureInput;
import static com.google.adk.workflow.WorkflowResumeTesting.go;
import static com.google.adk.workflow.WorkflowResumeTesting.longRunningAsk;
import static com.google.adk.workflow.WorkflowResumeTesting.replyInput;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonIdentityReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.databind.util.StdConverter;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.google.genai.types.VideoMetadata;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

  record Price(Number amount) {}

  record Reply(String id, Content content) {}

  record Ticket(String id, Optional<String> tag, @Nullable String note) {}

  record Badge(String id, @JsonIgnore int rank) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Parcel(String id, String note) {}

  record Coupon(String code, @JsonProperty(isRequired = OptBoolean.FALSE) String owner) {}

  record Label(String text, @DeclaredNullness.Nullable String hint) {}

  /** A class that reads a secret it never writes, since it has no getter for it. */
  static final class Stamp {
    private final String code;
    private final String secret;

    @JsonCreator
    Stamp(@JsonProperty("code") String code, @JsonProperty("secret") String secret) {
      this.code = code;
      this.secret = secret;
    }

    public String getCode() {
      return code;
    }
  }

  /** Holds a declaration annotation named like the {@code @Nullable} of other packages. */
  static final class DeclaredNullness {
    @Retention(RetentionPolicy.RUNTIME)
    @interface Nullable {}

    private DeclaredNullness() {}
  }

  record UserId(String id) {}

  record Tag(@JsonValue String name) {}

  record Section(@JsonProperty("section_name") String sectionName, String content) {}

  record Box<T>(T item) {}

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

  /** A record whose JSON form may omit all but two keys. */
  record Sparse(
      @JsonInclude(JsonInclude.Include.NON_EMPTY) String text,
      @JsonInclude(JsonInclude.Include.NON_DEFAULT) int count,
      @JsonInclude(JsonInclude.Include.NON_ABSENT) Optional<String> model,
      @JsonInclude(JsonInclude.Include.NON_NULL) Optional<String> label,
      @JsonSerialize(converter = NumberToText.class) int code,
      String plain) {}

  /** A record whose class-level inclusion omits only the keys that can be null. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Counted(int count, String label, @Nullable String note, Optional<String> tag) {}

  record Aliased(@JsonAlias("qty") int quantity) {}

  /** A record whose inclusions may omit keys, though none of its values can be null or empty. */
  record Lenient(
      @JsonInclude(JsonInclude.Include.NON_ABSENT) String id,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) int count,
      @JsonInclude(JsonInclude.Include.NON_EMPTY) Order order,
      @JsonInclude(JsonInclude.Include.NON_DEFAULT) Status status) {}

  /** A record that reads a password it never writes, and writes a role it never reads. */
  record Login(
      String user,
      @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
      @JsonProperty(access = JsonProperty.Access.READ_ONLY) String role) {}

  /** A class whose creator parameter has no getter or field, so the mapper never writes it. */
  static final class Greeting {
    public final String text;

    @JsonCreator
    Greeting(@JsonProperty("name") String name) {
      this.text = "hi " + name;
    }
  }

  record Strict(@JsonProperty(required = true) @Nullable String id) {}

  /** A field marked required, which Jackson does not enforce outside a creator. */
  static final class MarkedField {
    @JsonProperty(required = true)
    public @Nullable String id;
  }

  /** A setter marked required, which Jackson does not enforce outside a creator. */
  static final class MarkedSetter {
    private @Nullable String id;

    public @Nullable String getId() {
      return id;
    }

    @JsonProperty(required = true)
    public void setId(@Nullable String id) {
      this.id = id;
    }
  }

  record AliasedMarked(
      @JsonProperty(value = "quantity", required = true) @JsonAlias("qty") int q) {}

  static final class NumberToText extends StdConverter<Integer, String> {
    @Override
    public String convert(Integer value) {
      return value.toString();
    }
  }

  /** A class whose field alone is annotated nullable. */
  static final class Memo {
    private @Nullable String note;

    public String getNote() {
      return note;
    }
  }

  /** A class whose creator parameter alone is annotated nullable. */
  static final class Signed {
    public final String name;

    @JsonCreator
    Signed(@JsonProperty("name") @Nullable String name) {
      this.name = name;
    }
  }

  /** A record whose explicit accessor drops its component's annotation. */
  record Remark(@Nullable String note) {
    @Override
    public String note() {
      return note;
    }
  }

  record TreeNode(String label, List<TreeNode> children) {}

  /** Holds a declaration annotation named like the {@code @Nullable} of other packages. */
  static final class OtherNullness {
    @Retention(RetentionPolicy.RUNTIME)
    @interface Nullable {}

    private OtherNullness() {}
  }

  @Test
  public void of_recordPortFedAMap_readsTheRecord() {
    // A map output feeds a record port.
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

    RunResult result = run(chain(makeOrder, describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("a1 x3");
    assertThat(result.outputAt("wf@1/make_order@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3));
  }

  @Test
  public void of_recordPort_readsTheSameRecordFromTheStoredJsonForm() {
    // A session read back from JSON hands a successor its own number types.
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
    Node replay = emitter("make_order", stored);

    RunResult fromStored = run(chain(replay, describe));

    fromStored.assertCompleted();
    assertThat(fromStored.outputAt("wf@1/describe@1")).isEqualTo(live.outputAt("wf@1/describe@1"));
  }

  @Test
  public void of_recordOutput_equalsItsStoredJsonForm() {
    // An untyped successor must see the same number types live and after a resume.
    FunctionNode<Content, Order> makeOrder =
        FunctionNodes.of(
            "make_order", Content.class, Order.class, (ctx, message) -> new Order("a1", 3));

    Event liveOutput = run(chain(makeOrder)).outputEventAt("wf@1/make_order@1");

    assertThat(liveOutput.output()).hasValue(Event.fromJson(liveOutput.toJson()).output().get());
  }

  @Test
  public void builder_bodyDelegatingItsOutput_outputsTheChildsOutputWrittenAsO() {
    AtomicReference<Object> received = new AtomicReference<>();
    FunctionNode<Object, Void> read =
        FunctionNodes.of(
            "read",
            Object.class,
            Void.class,
            (ctx, input) -> {
              received.set(input);
              return null;
            });
    Map<String, @Nullable Object> asTicket = new HashMap<>();
    asTicket.put("id", "a1");
    asTicket.put("tag", null);
    asTicket.put("note", null);

    RunResult result = run(chain(delegating(Ticket.class, ImmutableMap.of("id", "a1")), read));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/router@1/child@1")).isEqualTo(ImmutableMap.of("id", "a1"));
    assertThat(result.outputAt("wf@1/router@1")).isEqualTo(asTicket);
    assertThat(result.storedOutputAt("wf@1/router@1")).isEqualTo(asTicket);
    assertThat(received.get()).isEqualTo(asTicket);
  }

  @Test
  public void builder_bodyRerunOnResume_writesTheReplayedDelegatedOutputAsO() {
    // The body asks after delegating, so the answer reruns it and the child replays its output.
    FunctionNode<Content, Ticket> router =
        FunctionNodes.builder(Content.class, Ticket.class)
            .name("router")
            .rerunOnResume(true)
            .body(
                (ctx, message) -> {
                  Object unusedOutput =
                      ctx.runNode(
                              emitter("child", ImmutableMap.of("id", "a1")),
                              null,
                              RunNodeConfig.builder().useAsOutput(true).build())
                          .blockingGet();
                  Object unusedAnswer =
                      ctx.runNode(
                              longRunningAsk("ask", "ask_1"),
                              null,
                              RunNodeConfig.builder().runId("a").build())
                          .blockingGet();
                  return null;
                })
            .build();
    WorkflowSession session = new WorkflowSession(chain(router, captureInput("reply")));
    session.advance(go());
    Map<String, @Nullable Object> asTicket = new HashMap<>();
    asTicket.put("id", "a1");
    asTicket.put("tag", null);
    asTicket.put("note", null);

    ImmutableList<Event> events = session.turn(answer("ask_1", ImmutableMap.of("ok", true)));

    assertThat(events.stream().filter(e -> e.errorCode().isPresent())).isEmpty();
    Event routerOutput = WorkflowResumeTesting.outputEventAt(events, "wf@1/router@1");
    assertThat(routerOutput.output()).hasValue(asTicket);
    assertThat(Event.fromJson(routerOutput.toJson()).output()).hasValue(asTicket);
    assertThat(replyInput(events)).isEqualTo(asTicket);
  }

  @Test
  public void builder_eventBodyDelegatingARecord_outputsItsJsonForm() {
    RunResult result = run(chain(delegating(Event.class, new Order("a1", 3))));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/router@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3));
    assertThat(result.storedOutputAt("wf@1/router@1")).isEqualTo(result.outputAt("wf@1/router@1"));
  }

  @Test
  public void builder_bodyDelegatingAnOutputAlreadyWrittenAsO_sendsOnlyTheChildsEvent() {
    ImmutableMap<String, Object> order = ImmutableMap.of("id", "a1", "quantity", 3);

    RunResult result = run(chain(delegating(Order.class, order)));

    result.assertCompleted();
    assertThat(result.eventsAt("wf@1/router@1").stream().filter(e -> e.output().isPresent()))
        .isEmpty();
    assertThat(result.outputAt("wf@1/router@1/child@1")).isEqualTo(order);
  }

  @Test
  public void builder_bodyDelegatingAnOutputThatIsNotAnO_fails() {
    RunResult result = run(chain(delegating(Order.class, "x")));

    assertThat(result.errorMessage("IllegalArgumentException"))
        .contains("output of node 'router' does not match");
  }

  @Test
  public void builder_voidBodyDelegatingItsOutput_fails() {
    RunResult result = run(chain(delegating(Void.class, "x")));

    assertThat(result.errorMessage("IllegalArgumentException"))
        .contains("node 'router' declares no output (Void)");
  }

  @Test
  public void of_primitivePort_readsTheBoxedValue() {
    FunctionNode<Content, Integer> three =
        FunctionNodes.of("three", Content.class, Integer.class, (ctx, message) -> 3);
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.of("increment", int.class, Integer.class, (ctx, n) -> n + 1);

    RunResult result = run(chain(three, increment));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/increment@1")).isEqualTo(4);
  }

  @Test
  public void of_bodyReturningAContent_outputsItsJsonForm() {
    FunctionNode<Content, Content> reply =
        FunctionNodes.of(
            "reply",
            Content.class,
            Content.class,
            (ctx, message) -> Content.fromParts(Part.fromText("hi")));

    RunResult result = run(chain(reply));

    // A returned Content is the output, not a message.
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
    FunctionNode<Content, Event> order =
        FunctionNodes.of(
            "order",
            Content.class,
            Event.class,
            (ctx, message) -> Event.builder().output(new Order("a1", 3)).build());

    RunResult result = run(chain(order));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/order@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3));
  }

  @Test
  public void of_bodyThatThrows_failsTheNodeWithItsException() {
    FunctionNode<Content, String> broken =
        FunctionNodes.of(
            "broken",
            Content.class,
            String.class,
            (ctx, message) -> {
              throw new IOException("down");
            });

    RunResult result = run(chain(broken));

    assertThat(result.errorMessage("IOException")).isEqualTo("down");
  }

  @Test
  public void of_blockingSiblingsWaitingForEachOther_bothFinish() {
    // Each body blocks until the other has started, which needs two threads.
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
              leftSawRight.set(rightStarted.await(5, SECONDS));
              return null;
            });
    FunctionNode<Content, Void> right =
        FunctionNodes.of(
            "right",
            Content.class,
            Void.class,
            (ctx, message) -> {
              rightStarted.countDown();
              rightSawLeft.set(leftStarted.await(5, SECONDS));
              return null;
            });
    Workflow workflow =
        Workflow.builder()
            .name("wf")
            .edges(new Edge(Start.INSTANCE, left), new Edge(Start.INSTANCE, right))
            .build();

    RunResult result = run(workflow);

    result.assertCompleted();
    assertThat(leftSawRight.get()).isTrue();
    assertThat(rightSawLeft.get()).isTrue();
  }

  @Test
  @SuppressWarnings("rawtypes") // A Class port for a map is raw.
  public void of_mapPortFedAMap_getsTheJsonObject() {
    // A map output feeds a map port.
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

    RunResult result = run(chain(makeParams, describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("p1=value1, p2=100");
  }

  @Test
  public void of_recordOutput_isItsJsonForm() {
    FunctionNode<Content, Order> makeOrder =
        FunctionNodes.of(
            "make_order", Content.class, Order.class, (ctx, message) -> new Order("a1", 3));

    RunResult result = run(chain(makeOrder));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/make_order@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3));
  }

  @Test
  public void of_bodiesReturningAValueOrNothing_outputWhatTheyReturn() {
    FunctionNode<Content, String> hello =
        FunctionNodes.of("hello", Content.class, String.class, (ctx, message) -> "Hello");
    FunctionNode<String, Void> silent =
        FunctionNodes.of("silent", String.class, Void.class, (ctx, text) -> null);

    RunResult result = run(chain(hello, silent));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/hello@1")).isEqualTo("Hello");
    assertThat(result.eventsAt("wf@1/silent@1")).isEmpty();
  }

  @Test
  @SuppressWarnings("rawtypes") // A Class port for a list is raw.
  public void of_listPortWithoutItemType_getsTheJsonList() {
    FunctionNode<Content, List> makeList =
        FunctionNodes.of(
            "make_list", Content.class, List.class, (ctx, message) -> ImmutableList.of(1, 2));
    FunctionNode<List, String> describe =
        FunctionNodes.of("describe", List.class, String.class, (ctx, list) -> "list=" + list);

    RunResult result = run(chain(makeList, describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("list=[1, 2]");
  }

  @Test
  public void of_contentPort_getsTheUserMessage() {
    FunctionNode<Content, String> echo =
        FunctionNodes.of("echo", Content.class, String.class, (ctx, message) -> message.text());

    RunResult result = run(chain(echo));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/echo@1")).isEqualTo("go");
  }

  @Test
  public void of_bodyReturningARouteEvent_sendsItWithoutAnOutput() {
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

    RunResult result = run(chain(route));

    result.assertCompleted();
    Event event = result.eventsAt("wf@1/route@1").stream().collect(onlyElement());
    assertThat(event.output()).isEmpty();
    assertThat(event.actions().route()).hasValue(ImmutableList.of(new Route.Tag("some_route")));
  }

  @Test
  public void of_bodyReturningAContentEvent_sendsTheMessageWithoutAnOutput() {
    FunctionNode<Content, Event> say =
        FunctionNodes.of(
            "say",
            Content.class,
            Event.class,
            (ctx, message) ->
                Event.builder().content(Content.fromParts(Part.fromText("some content"))).build());

    RunResult result = run(chain(say));

    result.assertCompleted();
    Event event = result.eventsAt("wf@1/say@1").stream().collect(onlyElement());
    assertThat(event.output()).isEmpty();
    assertThat(event.content().get().text()).isEqualTo("some content");
  }

  @Test
  public void of_bodySettingStateAndReturningNothing_sendsTheStateDelta() {
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

    RunResult result = run(chain(write, read));

    result.assertCompleted();
    Event written = result.eventsAt("wf@1/write@1").stream().collect(onlyElement());
    assertThat(written.actions().stateDelta()).containsExactly("my_key", "my_value");
    assertThat(written.output()).isEmpty();
    assertThat(result.outputAt("wf@1/read@1")).isEqualTo("my_key=my_value");
  }

  @Test
  public void of_bodySettingStateAndReturningAnEvent_mergesBothStateDeltas() {
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

    RunResult result = run(chain(write));

    result.assertCompleted();
    Event written = result.eventsAt("wf@1/write@1").stream().collect(onlyElement());
    assertThat(written.output()).hasValue("result");
    assertThat(written.actions().stateDelta())
        .containsExactly("from_event", "event_value", "from_ctx", "ctx_value");
  }

  @Test
  public void of_inputThatDoesNotFitThePort_failsTheNodeWithoutQuotingTheValue() {
    FunctionNode<Content, String> secret =
        FunctionNodes.of("secret", Content.class, String.class, (ctx, message) -> "s3cr3t");
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());

    RunResult result = run(chain(secret, describe));

    String message = result.errorMessage("NodeInputValidationException");
    assertThat(message).contains("input of node 'describe'");
    assertThat(message).doesNotContain("s3cr3t");
  }

  @Test
  public void of_valueThatIsNotAContent_failsAContentPortWithoutQuotingTheValue() {
    FunctionNode<Content, String> secret =
        FunctionNodes.of("secret", Content.class, String.class, (ctx, message) -> "s3cr3t");
    FunctionNode<Content, String> read =
        FunctionNodes.of("read", Content.class, String.class, (ctx, content) -> content.text());

    RunResult result = run(chain(secret, read));

    String message = result.errorMessage("NodeInputValidationException");
    assertThat(message).contains("input of node 'read'");
    assertThat(message).doesNotContain("s3cr3t");
  }

  @Test
  public void of_body_runsOnTheRunnersScheduler() {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "app-scheduler"));
    FunctionNode<Content, String> where =
        FunctionNodes.of(
            "where",
            Content.class,
            String.class,
            (ctx, message) -> Thread.currentThread().getName());

    RunResult result;
    try {
      result = run(chain(where), Schedulers.from(executor));
    } finally {
      executor.shutdownNow();
    }

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/where@1")).isEqualTo("app-scheduler");
  }

  @Test
  public void toBuilder_copy_keepsThePortsAndTheBody() {
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());
    FunctionNode<Content, Object> makeOrder =
        FunctionNodes.of(
            "make_order",
            Content.class,
            Object.class,
            (ctx, message) -> ImmutableMap.of("id", "a1", "quantity", 3));

    RunResult result = run(chain(makeOrder, describe.toBuilder().name("copy").build()));

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
            .body((ctx, order) -> order)
            .build();

    // Act
    FunctionNode<Order, Order> copy = node.toBuilder().name("copy").build();

    // Assert: the explicit schema wins over the inferred one in the node and in its copy.
    assertThat(node.inputSchema()).hasValue(explicit);
    assertThat(copy.inputSchema()).hasValue(explicit);
    assertThat(copy.inputSchemaInferred()).isFalse();
    assertThat(copy.outputSchema()).hasValue(ORDER);
  }

  @Test
  public void of_inputWithoutAKeyTheInferredSchemaRequires_failsWithAValidationError() {
    // Arrange: the inferred Order schema requires a quantity, which the input lacks.
    Node makeOrder = emitter("make_order", ImmutableMap.of("id", "a1", "note", "rush"));
    FunctionNode<Order, String> describe =
        FunctionNodes.of(
            "describe",
            Order.class,
            String.class,
            (ctx, order) -> order.id() + " x" + order.quantity());

    // Act
    RunResult result = run(chain(makeOrder, describe));

    // Assert: the port enforces the key the inferred schema requires.
    assertThat(describe.inputSchemaInferred()).isTrue();
    assertThat(describe.inputSchema().orElseThrow().required())
        .hasValue(ImmutableList.of("id", "quantity"));
    assertThat(result.errorMessage("NodeInputValidationException"))
        .isEqualTo(
            "validation error: input of node 'describe' does not match "
                + Order.class.getName()
                + ".");
  }

  @Test
  public void of_recordWithOrWithoutAnOptionalKey_readsTheValueOrEmpty() {
    // Arrange: the inferred Note schema does not require subtitle, so it may arrive without one.
    FunctionNode<Note, String> read =
        FunctionNodes.of(
            "read", Note.class, String.class, (ctx, note) -> String.valueOf(note.subtitle()));
    Node without = emitter("make", ImmutableMap.of("title", "t", "pages", 1));
    Node with = emitter("make", ImmutableMap.of("title", "t", "pages", 1, "subtitle", "s"));

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
  public void inputSchema_jacksonAnnotations_renameIgnoreAliasOrUnrequireProperties() {
    // Act
    Optional<Schema> schema = inputSchema(Draft.class);
    Optional<Schema> aliased = inputSchema(Aliased.class);
    Optional<Schema> flat = inputSchema(Flat.class);

    // Assert: a primitive marked not required is optional too, as a missing one reads as zero.
    assertThat(schema)
        .hasValue(
            object(
                ImmutableMap.of("section_name", STRING, "content", STRING, "priority", INTEGER),
                "section_name"));
    assertThat(aliased).hasValue(object(ImmutableMap.of("quantity", INTEGER, "qty", INTEGER)));
    assertThat(flat).isEmpty();
  }

  @Test
  public void outputSchema_keysTheMapperAlwaysWrites_areRequiredEvenIfNullOrMarkedNotRequired() {
    // Act
    Optional<Schema> note = outputSchema(Note.class);
    Optional<Schema> draft = outputSchema(Draft.class);

    // Assert
    assertThat(note.orElseThrow().required())
        .hasValue(ImmutableList.of("title", "pages", "subtitle", "body", "footer"));
    assertThat(draft.orElseThrow().required())
        .hasValue(ImmutableList.of("section_name", "content", "priority"));
  }

  @Test
  public void outputSchema_keysTheMapperMayOmit_areNotRequiredAndAConverterTakesAnyValue() {
    // Act
    Optional<Schema> schema = outputSchema(Sparse.class);
    Optional<Schema> counted = outputSchema(Counted.class);
    Optional<Schema> sparseIn = inputSchema(Sparse.class);

    // Assert: an omitted key is never null, but NON_NULL still writes an empty Optional as null.
    // An input port does not need a key the mapper may omit.
    ImmutableMap<String, Schema> properties =
        ImmutableMap.of(
            "text", STRING,
            "count", INTEGER,
            "model", STRING,
            "label", nullable(STRING),
            "code", ANY,
            "plain", STRING);
    assertThat(schema).hasValue(object(properties, "code", "plain"));
    assertThat(sparseIn.orElseThrow().required()).hasValue(ImmutableList.of("code", "plain"));
    assertThat(counted.orElseThrow().required()).hasValue(ImmutableList.of("count", "label"));
    assertThat(counted.orElseThrow().properties().orElseThrow())
        .containsAtLeast("note", STRING, "tag", nullable(STRING));
  }

  @Test
  public void inputSchema_keysTheMapperMayOmitOrNeverWrites_areNotRequired() {
    // Arrange: an inclusion may omit each key of Counted and Lenient, though none can be null or
    // empty, the mapper never writes Login's password or Greeting's name, and required = true
    // binds only an unaliased creator parameter.
    ImmutableMap<Class<?>, ImmutableList<String>> expected =
        ImmutableMap.of(
            Counted.class, ImmutableList.of(),
            Lenient.class, ImmutableList.of(),
            Login.class, ImmutableList.of("user"),
            Greeting.class, ImmutableList.of(),
            MarkedField.class, ImmutableList.of(),
            MarkedSetter.class, ImmutableList.of(),
            AliasedMarked.class, ImmutableList.of());

    // Act
    ImmutableMap<Class<?>, ImmutableList<String>> actual =
        expected.keySet().stream()
            .collect(toImmutableMap(type -> type, FunctionNodeTest::requiredInputKeys));

    // Assert: an input needs only the keys the mapper always writes, which the port enforces.
    assertThat(actual).isEqualTo(expected);
    assertThat(inputSchema(Greeting.class).orElseThrow().properties().orElseThrow())
        .containsKey("name");
    assertThat(outputSchema(Lenient.class).orElseThrow().required())
        .hasValue(ImmutableList.of("id", "count", "order", "status"));
  }

  @Test
  public void of_inputMissingAKey_failsOnlyIfTheInferredSchemaRequiresIt() {
    // Arrange: of these keys, the inferred schemas require only Login's user and Strict's id.
    FunctionNode<Lenient, String> lenient =
        FunctionNodes.of(
            "lenient", Lenient.class, String.class, (ctx, in) -> in.count() + " " + in.order());
    FunctionNode<Login, String> login =
        FunctionNodes.of(
            "login", Login.class, String.class, (ctx, in) -> in.user() + " " + in.password());
    FunctionNode<Greeting, String> greeting =
        FunctionNodes.of("greeting", Greeting.class, String.class, (ctx, in) -> in.text);
    FunctionNode<Strict, String> strict =
        FunctionNodes.of("strict", Strict.class, String.class, (ctx, in) -> in.id());
    FunctionNode<MarkedField, String> markedField =
        FunctionNodes.of("marked_field", MarkedField.class, String.class, (ctx, in) -> in.id);
    FunctionNode<AliasedMarked, Integer> aliased =
        FunctionNodes.of("aliased", AliasedMarked.class, Integer.class, (ctx, in) -> in.q());

    // Act
    RunResult lenientRun = run(chain(emitter("make", ImmutableMap.of()), lenient));
    RunResult loginRun = run(chain(emitter("make", ImmutableMap.of("user", "u")), login));
    RunResult greetingRun = run(chain(emitter("make", ImmutableMap.of()), greeting));
    RunResult strictRun = run(chain(emitter("make", ImmutableMap.of()), strict));
    RunResult markedFieldRun = run(chain(emitter("make", ImmutableMap.of()), markedField));
    RunResult aliasedRun = run(chain(emitter("make", ImmutableMap.of("qty", 7)), aliased));

    // Assert: a key marked required is required even if nullable.
    lenientRun.assertCompleted();
    assertThat(lenientRun.outputAt("wf@1/lenient@1")).isEqualTo("0 null");
    loginRun.assertCompleted();
    assertThat(loginRun.outputAt("wf@1/login@1")).isEqualTo("u null");
    greetingRun.assertCompleted();
    assertThat(greetingRun.outputAt("wf@1/greeting@1")).isEqualTo("hi null");
    markedFieldRun.assertCompleted();
    aliasedRun.assertCompleted();
    assertThat(aliasedRun.outputAt("wf@1/aliased@1")).isEqualTo(7);
    assertThat(strict.inputSchema())
        .hasValue(object(ImmutableMap.of("id", nullable(STRING)), "id"));
    assertThat(strictRun.errorMessage("NodeInputValidationException"))
        .isEqualTo(
            "validation error: input of node 'strict' does not match "
                + Strict.class.getName()
                + ".");
  }

  @Test
  public void schemas_nullableOnAFieldCreatorParameterOrRecordComponent_makeThePropertyNullable() {
    // Act
    Optional<Schema> memo = outputSchema(Memo.class);
    Optional<Schema> signed = inputSchema(Signed.class);
    Optional<Schema> remark = outputSchema(Remark.class);

    // Assert: the getter or accessor the mapper calls is not annotated.
    assertThat(memo).hasValue(object(ImmutableMap.of("note", nullable(STRING)), "note"));
    assertThat(signed).hasValue(object(ImmutableMap.of("name", nullable(STRING))));
    assertThat(remark).hasValue(object(ImmutableMap.of("note", nullable(STRING)), "note"));
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
    ImmutableMap<String, Schema> properties =
        ImmutableMap.<String, Schema>builder()
            .put("id", ANY)
            .put("codes", ANY)
            .put("count", ANY)
            .put("typed", ANY)
            .put("typedOrders", typed(Type.Known.ARRAY))
            .put("byId", ANY)
            .put("tagged", ANY)
            .put("catchall", ANY)
            .put("twoCatchalls", ANY)
            .put("flat", ANY)
            .put("pair", ANY)
            .put("ref", ANY)
            .put("pet", ANY)
            .put("level", ANY)
            .put("at", ANY)
            .put("raw", ANY)
            .put("pets", typed(Type.Known.ARRAY))
            .buildOrThrow();
    assertThat(irregular).hasValue(object(properties, properties.keySet().toArray(String[]::new)));
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
        FunctionNodes.of("n", Content.class, Content.class, (ctx, content) -> content);

    // Assert: a returned Content is the output in its JSON form, which omits an unset key.
    for (Schema schema :
        ImmutableList.of(node.inputSchema().orElseThrow(), node.outputSchema().orElseThrow())) {
      assertThat(schema.type()).hasValue(new Type(Type.Known.OBJECT));
      assertThat(schema.properties().orElseThrow()).containsEntry("role", STRING);
      assertThat(schema.properties().orElseThrow().keySet()).containsExactly("parts", "role");
      assertThat(schema.required()).isEmpty();
    }
    assertThat(node.inputSchemaInferred()).isTrue();
  }

  @Test
  public void of_eventAndObjectPorts_inferASchemaOnlyForAnEventInput() {
    // Act
    FunctionNode<Event, Event> node =
        FunctionNodes.of("n", Event.class, Event.class, (ctx, event) -> event);
    Optional<Schema> object = inputSchema(Object.class);

    // Assert: an Event reads every key as optional, a returned Event is sent as it is, and an
    // Object takes any JSON value.
    Schema input = node.inputSchema().orElseThrow();
    assertThat(input.properties().orElseThrow()).containsEntry("author", STRING);
    assertThat(input.required()).isEmpty();
    assertThat(node.inputSchemaInferred()).isTrue();
    assertThat(node.outputSchema()).isEmpty();
    assertThat(object).isEmpty();
  }

  @Test
  public void builder_withoutABody_failsAtBuild() {
    FunctionNode.Builder<String, String> builder =
        FunctionNodes.builder(String.class, String.class).name("empty");

    IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);

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

    RunResult result = run(chain(upstream, aggregate));

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

    RunResult result = run(chain(upstream, aggregate));

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

    RunResult result = run(chain(number, first));

    assertThat(result.errorMessage("NodeInputValidationException"))
        .isEqualTo(
            "validation error: input of node 'first' does not match java.util.List<"
                + Section.class.getName()
                + ">.");
  }

  @Test
  public void of_polymorphicPort_failsAtBuild() {
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Shape.class, String.class, (ctx, in) -> ""));

    assertThat(error).hasMessageThat().contains("is polymorphic (@JsonTypeInfo)");
  }

  @Test
  public void of_numberPort_failsAtBuildSuggestingAConcreteNumber() {
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Number.class, String.class, (ctx, in) -> ""));

    assertThat(error).hasMessageThat().contains("Declare the port as Long, Double or BigDecimal");
  }

  @Test
  public void of_charSequencePort_failsAtBuildSuggestingString() {
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", CharSequence.class, String.class, (ctx, in) -> ""));

    assertThat(error).hasMessageThat().contains("Declare the port as String");
  }

  @Test
  public void of_outputTypeWithoutProperties_failsAtBuild() {
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Content.class, NoProperties.class, (ctx, in) -> null));

    assertThat(error).hasMessageThat().contains("cannot write as JSON");
  }

  @Test
  public void of_inputTypeCreatableOnlyFromAString_failsAtBuild() {
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", StringOnly.class, String.class, (ctx, in) -> ""));

    assertThat(error).hasMessageThat().contains("only from a string or number");
  }

  @Test
  public void builder_mapPortWithAnUnreadableKey_failsAtBuild() {
    TypeReference<Map<Order, String>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class, () -> FunctionNodes.builder(port, String.class));

    assertThat(error).hasMessageThat().contains("cannot read as a map key");
  }

  @Test
  public void of_abstractTypesWithACreator_roundTrip() {
    FunctionNode<Content, Range> pick =
        FunctionNodes.of("pick", Content.class, Range.class, (ctx, m) -> new SimpleRange(2, 5));
    FunctionNode<Range, Point> place =
        FunctionNodes.of(
            "place", Range.class, Point.class, (ctx, r) -> Point.create(r.high() - r.low()));
    FunctionNode<Point, Integer> readX =
        FunctionNodes.of("read_x", Point.class, Integer.class, (ctx, point) -> point.x());

    RunResult result = run(chain(pick, place, readX));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/pick@1")).isEqualTo(ImmutableMap.of("low", 2, "high", 5));
    assertThat(result.outputAt("wf@1/read_x@1")).isEqualTo(3);
  }

  @Test
  public void of_valueTypeWrittenAsAString_roundTrips() {
    FunctionNode<Content, OrderId> makeId =
        FunctionNodes.of("make_id", Content.class, OrderId.class, (ctx, m) -> new OrderId("A-1"));
    FunctionNode<OrderId, String> readId =
        FunctionNodes.of("read_id", OrderId.class, String.class, (ctx, id) -> "id " + id.value());

    RunResult result = run(chain(makeId, readId));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/make_id@1")).isEqualTo("A-1");
    assertThat(result.outputAt("wf@1/read_id@1")).isEqualTo("id A-1");
  }

  @Test
  public void of_genericOutputPort_writesEachElementInJsonForm() {
    FunctionNode<Content, List<Section>> sections =
        FunctionNodes.of(
            "sections",
            Content.class,
            new TypeReference<List<Section>>() {},
            (ctx, m) -> ImmutableList.of(new Section("s1", "c1")));

    RunResult result = run(chain(sections));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/sections@1"))
        .isEqualTo(ImmutableList.of(ImmutableMap.of("section_name", "s1", "content", "c1")));
  }

  @Test
  public void of_inputTypeJacksonCannotCreate_failsAtBuild() {
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", NoCreator.class, String.class, (ctx, in) -> in.label));

    assertThat(error).hasMessageThat().contains("cannot create from JSON");
  }

  @Test
  public void of_inputTypeJacksonCannotWrite_failsAtBuild() {
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Token.class, String.class, (ctx, in) -> ""));

    assertThat(error).hasMessageThat().contains("The input of node 'n'");
    assertThat(error).hasMessageThat().contains("cannot write as JSON");
  }

  @Test
  public void of_listOfAnUncreatableType_failsAtBuildNamingTheElement() {
    TypeReference<List<NoCreator>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", port, String.class, (ctx, in) -> ""));

    assertThat(error).hasMessageThat().startsWith("An element of the input of node 'n'");
  }

  @Test
  public void of_optionalPort_failsAtBuild() {
    TypeReference<Optional<String>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", port, String.class, (ctx, in) -> ""));

    assertThat(error).hasMessageThat().contains("Declare it without Optional");
  }

  @Test
  public void of_listOfOptionals_failsAtBuildNamingTheElement() {
    TypeReference<List<Optional<Order>>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", port, String.class, (ctx, in) -> ""));

    assertThat(error).hasMessageThat().startsWith("An element of the input of node 'n'");
    assertThat(error).hasMessageThat().contains("Declare it without Optional");
  }

  @Test
  public void of_optionalOutputPort_failsAtBuild() {
    TypeReference<Optional<Order>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Content.class, port, (ctx, in) -> Optional.empty()));

    assertThat(error).hasMessageThat().startsWith("The output of node 'n'");
    assertThat(error).hasMessageThat().contains("Declare it without Optional");
  }

  @Test
  public void of_listOfOptionalsOutputPort_failsAtBuildNamingTheElement() {
    TypeReference<List<Optional<Order>>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", Content.class, port, (ctx, in) -> ImmutableList.of()));

    assertThat(error).hasMessageThat().startsWith("An element of the output of node 'n'");
    assertThat(error).hasMessageThat().contains("Declare it without Optional");
  }

  @Test
  public void builder_portTypeWithATypeVariable_failsAtBuild() {
    // Arrange: a TypeReference made in a generic method cannot capture its type argument.
    TypeReference<List<Order>> port = listPort();

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class, () -> FunctionNodes.builder(port, String.class));

    assertThat(error).hasMessageThat().contains("holds T, which a TypeReference cannot capture");
  }

  @Test
  public void builder_portTypeWithAWildcard_failsAtBuild() {
    TypeReference<List<? extends Order>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class, () -> FunctionNodes.builder(port, String.class));

    assertThat(error).hasMessageThat().contains("holds the wildcard ? extends");
  }

  private static <T> TypeReference<List<T>> listPort() {
    return new TypeReference<>() {};
  }

  @Test
  public void of_genericRecordPortFedAValueWithMapFields_readsTheFieldTypes() {
    // Arrange: an upstream node hands over a Box whose item is still a map.
    Node upstream =
        emitter("upstream", new Box<Object>(ImmutableMap.of("id", "a1", "quantity", 3)));
    FunctionNode<Box<Order>, String> open =
        FunctionNodes.of(
            "open",
            new TypeReference<Box<Order>>() {},
            String.class,
            (ctx, box) -> box.item().id());

    RunResult result = run(chain(upstream, open));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/open@1")).isEqualTo("a1");
  }

  @Test
  public void of_genericRecordPortFedAMismatchedField_failsTheNodeInputValidation() {
    Node upstream = emitter("upstream", new Box<String>("bad"));
    FunctionNode<Box<Order>, String> open =
        FunctionNodes.of(
            "open",
            new TypeReference<Box<Order>>() {},
            String.class,
            (ctx, box) -> box.item().id());

    RunResult result = run(chain(upstream, open));

    assertThat(result.errorMessage("NodeInputValidationException"))
        .isEqualTo(
            "validation error: input of node 'open' does not match "
                + Box.class.getName()
                + "<"
                + Order.class.getName()
                + ">.");
  }

  @Test
  @SuppressWarnings("unchecked") // The cast smuggles strings into a list of orders.
  public void of_genericOutputPortGivenElementsOfAnotherType_failsTheNode() {
    FunctionNode<Content, List<Order>> orders =
        FunctionNodes.of(
            "orders",
            Content.class,
            new TypeReference<List<Order>>() {},
            (ctx, m) -> (List<Order>) (List<?>) ImmutableList.of("not an order"));

    RunResult result = run(chain(orders));

    String message = result.errorMessage("IllegalArgumentException");
    assertThat(message).contains("output of node 'orders' does not match");
    assertThat(message).doesNotContain("not an order");
  }

  @Test
  @SuppressWarnings("unchecked") // The cast passes maps as orders.
  public void of_genericOutputPortGivenElementsInJsonForm_writesThemAsThePortType() {
    FunctionNode<Content, List<Order>> orders =
        FunctionNodes.of(
            "orders",
            Content.class,
            new TypeReference<List<Order>>() {},
            (ctx, m) ->
                (List<Order>)
                    (List<?>) ImmutableList.of(ImmutableMap.of("id", "a1", "quantity", 2.0)));

    RunResult result = run(chain(orders));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/orders@1"))
        .isEqualTo(ImmutableList.of(ImmutableMap.of("id", "a1", "quantity", 2)));
  }

  @Test
  public void of_genericOutputOfATypeJacksonCannotRead_isWritten() {
    // Arrange: an output port need not be readable.
    FunctionNode<Content, Box<NoCreator>> boxed =
        FunctionNodes.of(
            "boxed",
            Content.class,
            new TypeReference<Box<NoCreator>>() {},
            (ctx, m) -> new Box<>(new NoCreator("x", 1)));

    RunResult result = run(chain(boxed));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/boxed@1"))
        .isEqualTo(ImmutableMap.of("item", ImmutableMap.of("label", "x")));
  }

  @Test
  public void of_typeReadThroughADelegatingMapCreator_roundTrips() {
    FunctionNode<Content, Bag> makeBag =
        FunctionNodes.of(
            "make_bag",
            Content.class,
            Bag.class,
            (ctx, m) -> new Bag(ImmutableMap.of("name", "x")));
    FunctionNode<Bag, String> readBag =
        FunctionNodes.of("read_bag", Bag.class, String.class, (ctx, bag) -> "bag " + bag.getName());

    RunResult result = run(chain(makeBag, readBag));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/make_bag@1")).isEqualTo(ImmutableMap.of("name", "x"));
    assertThat(result.outputAt("wf@1/read_bag@1")).isEqualTo("bag x");
  }

  @Test
  public void of_interfaceOutputWithoutACreator_failsAtBuild() {
    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class,
            () -> FunctionNodes.of("n", String.class, Decision.class, (ctx, in) -> null));

    assertThat(error)
        .hasMessageThat()
        .contains("interface or abstract class without a Jackson creator");
  }

  @Test
  public void of_untypedAndAdkPorts_needNoJacksonShape() {
    FunctionNode<Content, Object> echo =
        FunctionNodes.of("echo", Content.class, Object.class, (ctx, message) -> message.text());
    FunctionNode<Object, Void> sink =
        FunctionNodes.of("sink", Object.class, Void.class, (ctx, in) -> null);

    RunResult result = run(chain(echo, sink));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/echo@1")).isEqualTo("go");
  }

  @Test
  public void builder_withoutAName_failsAtBuild() {
    FunctionNode.Builder<String, String> builder =
        FunctionNodes.builder(String.class, String.class).body((ctx, text) -> text);

    IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);

    assertThat(error).hasMessageThat().contains("requires name");
  }

  @Test
  public void builder_optionalProperties_areTheNodesProperties() {
    NodeConfig config = NodeConfig.builder().timeout(Duration.ofSeconds(5)).build();
    Schema schema = Schema.builder().type("STRING").build();

    FunctionNode<String, String> node =
        FunctionNodes.builder(String.class, String.class)
            .name("node")
            .description("Echoes its input.")
            .rerunOnResume(true)
            .waitForOutput(true)
            .config(config)
            .inputSchema(schema)
            .outputSchema(schema)
            .stateSchema(schema)
            .body((ctx, text) -> text)
            .build();

    assertThat(node.description()).isEqualTo("Echoes its input.");
    assertThat(node.rerunOnResume()).isTrue();
    assertThat(node.waitForOutput()).isTrue();
    assertThat(node.config()).isEqualTo(config);
    assertThat(node.inputSchema()).hasValue(schema);
    assertThat(node.outputSchema()).hasValue(schema);
    assertThat(node.stateSchema()).hasValue(schema);
  }

  @Test
  public void toBuilder_copy_keepsEveryProperty() {
    Schema schema = Schema.builder().type("STRING").build();
    FunctionNode<String, String> node =
        FunctionNodes.builder(String.class, String.class)
            .name("node")
            .description("Echoes its input.")
            .rerunOnResume(true)
            .waitForOutput(true)
            .config(NodeConfig.builder().timeout(Duration.ofSeconds(5)).build())
            .inputSchema(schema)
            .outputSchema(schema)
            .stateSchema(schema)
            .body((ctx, text) -> text)
            .build();

    FunctionNode<String, String> copy = node.toBuilder().build();

    assertThat(copy.name()).isEqualTo(node.name());
    assertThat(copy.description()).isEqualTo(node.description());
    assertThat(copy.rerunOnResume()).isTrue();
    assertThat(copy.waitForOutput()).isTrue();
    assertThat(copy.config()).isEqualTo(node.config());
    assertThat(copy.inputSchema()).isEqualTo(node.inputSchema());
    assertThat(copy.outputSchema()).isEqualTo(node.outputSchema());
    assertThat(copy.stateSchema()).isEqualTo(node.stateSchema());
  }

  @Test
  public void of_reusableBodyOfWiderTypes_bindsWithoutAnAdapter() {
    NodeBody<Object, String> done = (ctx, input) -> "done";
    FunctionNode<Content, Object> node =
        FunctionNodes.of("done", Content.class, Object.class, done);

    RunResult result = run(chain(node));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/done@1")).isEqualTo("done");
  }

  @Test
  public void of_bigIntegerOutput_equalsItsStoredJsonForm() {
    BigInteger big = new BigInteger("123456789012345678901234567890");
    FunctionNode<Content, BigInteger> node =
        FunctionNodes.of("big", Content.class, BigInteger.class, (ctx, message) -> big);

    RunResult result = run(chain(node));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/big@1")).isEqualTo(big);
    assertThat(result.outputAt("wf@1/big@1")).isEqualTo(result.storedOutputAt("wf@1/big@1"));
  }

  @Test
  public void of_bigDecimalOutput_isTheDoubleItsStoredJsonFormHolds() {
    FunctionNode<Content, BigDecimal> node =
        FunctionNodes.of(
            "decimal", Content.class, BigDecimal.class, (ctx, message) -> new BigDecimal("0.1"));

    RunResult result = run(chain(node));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/decimal@1")).isEqualTo(0.1);
    assertThat(result.outputAt("wf@1/decimal@1"))
        .isEqualTo(result.storedOutputAt("wf@1/decimal@1"));
  }

  @Test
  public void of_floatOutput_equalsItsStoredJsonForm() {
    FunctionNode<Content, Float> node =
        FunctionNodes.of("float", Content.class, Float.class, (ctx, message) -> 0.1f);

    RunResult result = run(chain(node));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/float@1")).isEqualTo(0.1);
    assertThat(result.outputAt("wf@1/float@1")).isEqualTo(result.storedOutputAt("wf@1/float@1"));
  }

  @Test
  public void of_nonFiniteOutput_failsTheNode() {
    FunctionNode<Content, Double> node =
        FunctionNodes.of("nan", Content.class, Double.class, (ctx, message) -> Double.NaN);

    RunResult result = run(chain(node));

    assertThat(result.errorMessage("IllegalArgumentException"))
        .isEqualTo("output of node 'nan' holds a NaN or infinite number, which JSON lacks.");
  }

  @Test
  public void of_outputThatWritesNull_failsTheNodeNamingThePort() {
    FunctionNode<Content, Object> node =
        FunctionNodes.of("empty", Content.class, Object.class, (ctx, message) -> Optional.empty());

    RunResult result = run(chain(node));

    assertThat(result.errorMessage("IllegalArgumentException"))
        .isEqualTo("output of node 'empty' writes null as java.lang.Object.");
  }

  @Test
  public void of_genaiOutputWithoutAJsonForm_failsTheNodeWithoutQuotingTheValue() {
    FunctionNode<Content, Schema> node =
        FunctionNodes.of(
            "schema",
            Content.class,
            Schema.class,
            (ctx, message) -> Schema.builder().example(new Object()).build());

    RunResult result = run(chain(node));

    assertThat(result.errorMessage("IllegalArgumentException"))
        .isEqualTo("output of node 'schema' has no JSON form as com.google.genai.types.Schema.");
  }

  @Test
  public void of_partPort_readsThePartLiveAndFromTheStoredJsonForm() {
    FunctionNode<Content, Part> makePart =
        FunctionNodes.of("make_part", Content.class, Part.class, (ctx, m) -> Part.fromText("hi"));
    FunctionNode<Part, String> read =
        FunctionNodes.of("read", Part.class, String.class, (ctx, part) -> part.text().get());
    RunResult live = run(chain(makePart, read));
    Node replay = emitter("make_part", live.storedOutputAt("wf@1/make_part@1"));

    RunResult fromStored = run(chain(replay, read));

    live.assertCompleted();
    fromStored.assertCompleted();
    assertThat(live.outputAt("wf@1/read@1")).isEqualTo("hi");
    assertThat(fromStored.outputAt("wf@1/read@1")).isEqualTo("hi");
  }

  @Test
  public void of_videoMetadataPort_readsItsDurationLiveAndFromTheStoredJsonForm() {
    FunctionNode<Content, VideoMetadata> makeMetadata =
        FunctionNodes.of(
            "make_metadata",
            Content.class,
            VideoMetadata.class,
            (ctx, m) -> VideoMetadata.builder().startOffset(Duration.ofSeconds(3)).build());
    FunctionNode<VideoMetadata, String> read =
        FunctionNodes.of(
            "read",
            VideoMetadata.class,
            String.class,
            (ctx, metadata) -> metadata.startOffset().get().toString());
    RunResult live = run(chain(makeMetadata, read));
    Node replay = emitter("make_metadata", live.storedOutputAt("wf@1/make_metadata@1"));

    RunResult fromStored = run(chain(replay, read));

    live.assertCompleted();
    fromStored.assertCompleted();
    // genai writes a duration as "3s".
    assertThat(live.outputAt("wf@1/make_metadata@1"))
        .isEqualTo(ImmutableMap.of("startOffset", "3s"));
    assertThat(live.outputAt("wf@1/read@1")).isEqualTo("PT3S");
    assertThat(fromStored.outputAt("wf@1/read@1")).isEqualTo("PT3S");
  }

  @Test
  public void of_contentArrayPort_readsTheContentsLiveAndFromTheStoredJsonForm() {
    FunctionNode<Content, Content[]> makeContents =
        FunctionNodes.of(
            "make_contents",
            Content.class,
            Content[].class,
            (ctx, m) -> new Content[] {Content.fromParts(Part.fromText("a")), m});
    FunctionNode<Content[], String> read =
        FunctionNodes.of(
            "read",
            Content[].class,
            String.class,
            (ctx, contents) -> contents[0].text() + contents[1].text());
    RunResult live = run(chain(makeContents, read));
    Node replay = emitter("make_contents", live.storedOutputAt("wf@1/make_contents@1"));

    RunResult fromStored = run(chain(replay, read));

    live.assertCompleted();
    fromStored.assertCompleted();
    assertThat(live.outputAt("wf@1/read@1")).isEqualTo("ago");
    assertThat(fromStored.outputAt("wf@1/read@1")).isEqualTo("ago");
  }

  @Test
  public void of_recordWithAContentField_readsTheRecordLiveAndFromTheStoredJsonForm() {
    FunctionNode<Content, Reply> makeReply =
        FunctionNodes.of(
            "make_reply", Content.class, Reply.class, (ctx, message) -> new Reply("r1", message));
    FunctionNode<Reply, String> read =
        FunctionNodes.of(
            "read", Reply.class, String.class, (ctx, reply) -> reply.id() + reply.content().text());
    RunResult live = run(chain(makeReply, read));
    Node replay = emitter("make_reply", live.storedOutputAt("wf@1/make_reply@1"));

    RunResult fromStored = run(chain(replay, read));

    live.assertCompleted();
    fromStored.assertCompleted();
    assertThat(live.outputAt("wf@1/read@1")).isEqualTo("r1go");
    assertThat(fromStored.outputAt("wf@1/read@1")).isEqualTo("r1go");
  }

  @Test
  public void of_primitivePortAfterANodeWithoutOutput_failsWithAValidationError() {
    FunctionNode<Content, Void> silent =
        FunctionNodes.of("silent", Content.class, Void.class, (ctx, message) -> null);
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.of("increment", int.class, Integer.class, (ctx, n) -> n + 1);

    RunResult result = run(chain(silent, increment));

    assertThat(result.errorMessage("NodeInputValidationException"))
        .isEqualTo(
            "validation error: input of node 'increment' does not accept null (expected int).");
  }

  @Test
  public void of_intPortFedAFraction_failsWithAValidationError() {
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.of("increment", int.class, Integer.class, (ctx, n) -> n + 1);

    RunResult result = run(chain(emitter("make", 2.5), increment));

    assertThat(result.errorMessage("NodeInputValidationException"))
        .contains("input of node 'increment'");
  }

  @Test
  public void of_intPortFedAWholeFloat_readsTheWholeNumber() {
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.of("increment", int.class, Integer.class, (ctx, n) -> n + 1);

    RunResult result = run(chain(emitter("make", 2.0), increment));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/increment@1")).isEqualTo(3);
  }

  @Test
  public void of_recordPortFedAFractionalQuantity_failsWithAValidationError() {
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());

    RunResult result =
        run(chain(emitter("make", ImmutableMap.of("id", "a1", "quantity", 2.5)), describe));

    assertThat(result.errorMessage("NodeInputValidationException"))
        .contains("input of node 'describe'");
  }

  @Test
  public void of_recordPortFedAWholeFloatQuantity_readsTheWholeNumber() {
    FunctionNode<Order, String> describe =
        FunctionNodes.of(
            "describe",
            Order.class,
            String.class,
            (ctx, order) -> order.id() + " x" + order.quantity());

    RunResult result =
        run(chain(emitter("make", ImmutableMap.of("id", "a1", "quantity", 2.0)), describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("a1 x2");
  }

  @Test
  public void of_recordWithANumberFieldFedAFraction_keepsTheFraction() {
    FunctionNode<Price, String> describe =
        FunctionNodes.of(
            "describe", Price.class, String.class, (ctx, price) -> "amount " + price.amount());

    RunResult result = run(chain(emitter("make", ImmutableMap.of("amount", 2.5)), describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("amount 2.5");
  }

  @Test
  public void of_recordPortFedAMapWithoutARequiredKey_failsWithAValidationError() {
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());

    RunResult withoutId = run(chain(emitter("make", ImmutableMap.of("quantity", 3)), describe));
    RunResult withoutQuantity = run(chain(emitter("make", ImmutableMap.of("id", "a1")), describe));

    String expected =
        "validation error: input of node 'describe' does not match " + Order.class.getName() + ".";
    assertThat(withoutId.errorMessage("NodeInputValidationException")).isEqualTo(expected);
    assertThat(withoutQuantity.errorMessage("NodeInputValidationException")).isEqualTo(expected);
  }

  @Test
  public void of_recordPortFedANullForAPrimitive_failsWithAValidationError() {
    Map<String, @Nullable Object> nullQuantity = new HashMap<>();
    nullQuantity.put("id", "a1");
    nullQuantity.put("quantity", null);
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());

    RunResult result = run(chain(emitter("make", nullQuantity), describe));

    assertThat(result.errorMessage("NodeInputValidationException"))
        .isEqualTo(
            "validation error: input of node 'describe' does not match "
                + Order.class.getName()
                + ".");
  }

  @Test
  public void of_recordPortFedAMapWithoutOptionalAndNullableKeys_readsEmptyAndNull() {
    FunctionNode<Ticket, String> describe =
        FunctionNodes.of(
            "describe",
            Ticket.class,
            String.class,
            (ctx, ticket) -> ticket.id() + " " + ticket.tag() + " " + ticket.note());

    RunResult result = run(chain(emitter("make", ImmutableMap.of("id", "t1")), describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("t1 Optional.empty null");
  }

  @Test
  public void of_recordPortFedAMapWithoutAnIgnoredKey_readsItsDefault() {
    FunctionNode<Badge, String> describe =
        FunctionNodes.of(
            "describe", Badge.class, String.class, (ctx, badge) -> badge.id() + " " + badge.rank());

    RunResult result = run(chain(emitter("make", ImmutableMap.of("id", "b1")), describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("b1 0");
  }

  @Test
  public void of_recordOutputOmittingANullKey_readsBackInTheSameType() {
    FunctionNode<Content, Parcel> make =
        FunctionNodes.of("make", Content.class, Parcel.class, (ctx, m) -> new Parcel("p1", null));
    FunctionNode<Parcel, String> describe =
        FunctionNodes.of(
            "describe", Parcel.class, String.class, (ctx, p) -> p.id() + " " + p.note());

    RunResult result = run(chain(make, describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/make@1")).isEqualTo(ImmutableMap.of("id", "p1"));
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("p1 null");
  }

  @Test
  public void of_classPortFedAMapWithoutAKeyItNeverWrites_readsNull() {
    FunctionNode<Stamp, String> describe =
        FunctionNodes.of(
            "describe", Stamp.class, String.class, (ctx, st) -> st.getCode() + " " + st.secret);

    RunResult result = run(chain(emitter("make", ImmutableMap.of("code", "s1")), describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("s1 null");
  }

  @Test
  public void of_recordPortFedAMapWithoutAKeyMarkedNotRequired_readsNull() {
    FunctionNode<Coupon, String> describe =
        FunctionNodes.of(
            "describe", Coupon.class, String.class, (ctx, c) -> c.code() + " " + c.owner());

    RunResult result = run(chain(emitter("make", ImmutableMap.of("code", "c1")), describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("c1 null");
  }

  @Test
  public void of_recordPortFedAMapWithoutADeclarationNullableKey_readsNull() {
    FunctionNode<Label, String> describe =
        FunctionNodes.of(
            "describe", Label.class, String.class, (ctx, l) -> l.text() + " " + l.hint());

    RunResult result = run(chain(emitter("make", ImmutableMap.of("text", "l1")), describe));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/describe@1")).isEqualTo("l1 null");
  }

  @Test
  public void of_localeAndUriWrittenAsEmptyStrings_readBackInTheSameType() {
    FunctionNode<Content, Locale> makeLocale =
        FunctionNodes.of("make", Content.class, Locale.class, (ctx, m) -> Locale.ROOT);
    FunctionNode<Locale, Boolean> isRoot =
        FunctionNodes.of("check", Locale.class, Boolean.class, (ctx, l) -> l.equals(Locale.ROOT));
    FunctionNode<Content, URI> makeUri =
        FunctionNodes.of("make", Content.class, URI.class, (ctx, m) -> URI.create(""));
    FunctionNode<URI, Boolean> isEmpty =
        FunctionNodes.of("check", URI.class, Boolean.class, (ctx, u) -> u.toString().isEmpty());

    RunResult locale = run(chain(makeLocale, isRoot));
    RunResult uri = run(chain(makeUri, isEmpty));

    locale.assertCompleted();
    uri.assertCompleted();
    assertThat(locale.outputAt("wf@1/make@1")).isEqualTo("");
    assertThat(locale.outputAt("wf@1/check@1")).isEqualTo(true);
    assertThat(uri.outputAt("wf@1/make@1")).isEqualTo("");
    assertThat(uri.outputAt("wf@1/check@1")).isEqualTo(true);
  }

  @Test
  public void of_intPortFedAnEmptyString_failsWithAValidationError() {
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.of("increment", int.class, Integer.class, (ctx, n) -> n + 1);

    RunResult result = run(chain(emitter("make", ""), increment));

    assertThat(result.errorMessage("NodeInputValidationException"))
        .isEqualTo("validation error: input of node 'increment' does not match java.lang.Integer.");
  }

  @Test
  public void of_intPortFedAValueThatConvertsToNull_failsWithAValidationError() {
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.of("increment", int.class, Integer.class, (ctx, n) -> n + 1);

    RunResult result = run(chain(emitter("make", Optional.empty()), increment));

    assertThat(result.errorMessage("NodeInputValidationException"))
        .isEqualTo(
            "validation error: input of node 'increment' does not accept null (expected int).");
  }

  @Test
  public void of_textFedANumberOrBoolean_failsWithAValidationError() {
    FunctionNode<String, String> echo =
        FunctionNodes.of("echo", String.class, String.class, (ctx, text) -> text);
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());

    RunResult number = run(chain(emitter("make", 42), echo));
    RunResult bool =
        run(chain(emitter("make", ImmutableMap.of("id", true, "quantity", 3)), describe));

    assertThat(number.errorMessage("NodeInputValidationException"))
        .isEqualTo("validation error: input of node 'echo' does not match java.lang.String.");
    assertThat(bool.errorMessage("NodeInputValidationException"))
        .isEqualTo(
            "validation error: input of node 'describe' does not match "
                + Order.class.getName()
                + ".");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"}) // A Class port for a map is raw.
  public void of_bodyMutatingItsMapInput_failsAndLeavesThePredecessorsOutput() {
    FunctionNode<Content, Object> makeParams =
        FunctionNodes.of(
            "make_params", Content.class, Object.class, (ctx, m) -> ImmutableMap.of("a", 1));
    FunctionNode<Map, String> mutate =
        FunctionNodes.of(
            "mutate",
            Map.class,
            String.class,
            (ctx, params) -> {
              params.put("b", 2);
              return "mutated";
            });

    RunResult result = run(chain(makeParams, mutate));

    assertThat(result.errorMessage("UnsupportedOperationException")).isEmpty();
    assertThat(result.outputAt("wf@1/make_params@1")).isEqualTo(ImmutableMap.of("a", 1));
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"}) // A Class port for a map is raw.
  public void of_bodyMutatingAListInItsInput_failsAndLeavesThePredecessorsOutput() {
    FunctionNode<Content, Object> makeParams =
        FunctionNodes.of(
            "make_params",
            Content.class,
            Object.class,
            (ctx, m) -> ImmutableMap.of("items", ImmutableList.of(1)));
    FunctionNode<Map, String> mutate =
        FunctionNodes.of(
            "mutate",
            Map.class,
            String.class,
            (ctx, params) -> {
              ((List) params.get("items")).add(2);
              return "mutated";
            });

    RunResult result = run(chain(makeParams, mutate));

    assertThat(result.errorMessage("UnsupportedOperationException")).isEmpty();
    assertThat(result.outputAt("wf@1/make_params@1"))
        .isEqualTo(ImmutableMap.of("items", ImmutableList.of(1)));
  }

  @Test
  public void of_bodyReturningARequestInput_failsTheNode() {
    FunctionNode<Content, Object> ask =
        FunctionNodes.of(
            "ask",
            Content.class,
            Object.class,
            (ctx, message) -> RequestInput.builder().interruptId("ask_1").build());

    RunResult result = run(chain(ask));

    assertThat(result.errorMessage("IllegalArgumentException"))
        .isEqualTo(
            "node 'ask' returned a RequestInput, which a NodeBody cannot send; return the output"
                + " instead.");
  }

  @Test
  public void of_recordPortFedTheUserMessage_failsNamingTheMessageType() {
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());

    RunResult result = run(chain(describe));

    assertThat(result.errorMessage("NodeInputValidationException"))
        .contains("input of node 'describe' expected");
    assertThat(result.errorMessage("NodeInputValidationException"))
        .containsMatch("but got \\w*Content");
  }

  @Test
  public void of_eventPortFedAMap_readsTheEvent() {
    FunctionNode<Event, String> read =
        FunctionNodes.of("read", Event.class, String.class, (ctx, event) -> event.author());

    RunResult result = run(chain(emitter("make", ImmutableMap.of("author", "someone")), read));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/read@1")).isEqualTo("someone");
  }

  @Test
  public void of_arrayPortFedItsOwnArray_getsTheSameArray() {
    String[] words = {"a", "b"};
    AtomicReference<String[]> received = new AtomicReference<>();
    FunctionNode<String[], Void> read =
        FunctionNodes.of(
            "read",
            String[].class,
            Void.class,
            (ctx, input) -> {
              received.set(input);
              return null;
            });

    RunResult result = run(chain(emitter("make", words), read));

    result.assertCompleted();
    assertThat(received.get()).isSameInstanceAs(words);
  }

  @Test
  public void of_intArrayPortFedAnIntArray_getsTheSameArray() {
    int[] numbers = {1, 2};
    AtomicReference<int[]> received = new AtomicReference<>();
    FunctionNode<int[], Void> read =
        FunctionNodes.of(
            "read",
            int[].class,
            Void.class,
            (ctx, input) -> {
              received.set(input);
              return null;
            });

    RunResult result = run(chain(emitter("make", numbers), read));

    result.assertCompleted();
    assertThat(received.get()).isSameInstanceAs(numbers);
  }

  @Test
  public void of_listOfContentPort_readsTheContentsLiveAndFromTheStoredJsonForm() {
    TypeReference<List<Content>> contents = new TypeReference<>() {};
    FunctionNode<Content, List<Content>> makeContents =
        FunctionNodes.of(
            "make_contents",
            Content.class,
            contents,
            (ctx, m) -> ImmutableList.of(Content.fromParts(Part.fromText("a")), m));
    FunctionNode<List<Content>, String> read =
        FunctionNodes.of(
            "read", contents, String.class, (ctx, list) -> list.get(0).text() + list.get(1).text());
    RunResult live = run(chain(makeContents, read));
    Node replay = emitter("make_contents", live.storedOutputAt("wf@1/make_contents@1"));

    RunResult fromStored = run(chain(replay, read));

    live.assertCompleted();
    fromStored.assertCompleted();
    assertThat(live.outputAt("wf@1/read@1")).isEqualTo("ago");
    assertThat(fromStored.outputAt("wf@1/read@1")).isEqualTo("ago");
  }

  @Test
  public void of_mapOfContentPort_readsTheContentsLiveAndFromTheStoredJsonForm() {
    TypeReference<Map<String, Content>> contents = new TypeReference<>() {};
    FunctionNode<Content, Map<String, Content>> makeContents =
        FunctionNodes.of(
            "make_contents", Content.class, contents, (ctx, m) -> ImmutableMap.of("first", m));
    FunctionNode<Map<String, Content>, String> read =
        FunctionNodes.of("read", contents, String.class, (ctx, map) -> map.get("first").text());
    RunResult live = run(chain(makeContents, read));
    Node replay = emitter("make_contents", live.storedOutputAt("wf@1/make_contents@1"));

    RunResult fromStored = run(chain(replay, read));

    live.assertCompleted();
    fromStored.assertCompleted();
    assertThat(live.outputAt("wf@1/read@1")).isEqualTo("go");
    assertThat(fromStored.outputAt("wf@1/read@1")).isEqualTo("go");
  }

  @Test
  public void of_listOfEventPort_readsTheEventsLiveAndFromTheStoredJsonForm() {
    TypeReference<List<Event>> events = new TypeReference<>() {};
    FunctionNode<Content, List<Event>> makeEvents =
        FunctionNodes.of(
            "make_events",
            Content.class,
            events,
            (ctx, m) -> ImmutableList.of(Event.builder().author("someone").content(m).build()));
    FunctionNode<List<Event>, String> read =
        FunctionNodes.of(
            "read",
            events,
            String.class,
            (ctx, list) -> list.get(0).author() + ":" + list.get(0).content().get().text());
    RunResult live = run(chain(makeEvents, read));
    Node replay = emitter("make_events", live.storedOutputAt("wf@1/make_events@1"));

    RunResult fromStored = run(chain(replay, read));

    live.assertCompleted();
    fromStored.assertCompleted();
    assertThat(live.outputAt("wf@1/read@1")).isEqualTo("someone:go");
    assertThat(fromStored.outputAt("wf@1/read@1")).isEqualTo("someone:go");
  }

  @Test
  public void of_zoneIdPorts_sendAndReadTheZone() {
    FunctionNode<Content, ZoneId> zone =
        FunctionNodes.of(
            "zone", Content.class, ZoneId.class, (ctx, m) -> ZoneId.of("Europe/Paris"));
    FunctionNode<ZoneId, String> read =
        FunctionNodes.of("read", ZoneId.class, String.class, (ctx, id) -> id.getId());

    RunResult result = run(chain(zone, read));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/zone@1")).isEqualTo("Europe/Paris");
    assertThat(result.outputAt("wf@1/read@1")).isEqualTo("Europe/Paris");
  }

  @Test
  public void of_jsonNodePorts_sendAndReadTheTree() {
    FunctionNode<Content, JsonNode> tree =
        FunctionNodes.of(
            "tree",
            Content.class,
            JsonNode.class,
            (ctx, m) -> JsonNodeFactory.instance.objectNode().put("a", 1));
    FunctionNode<JsonNode, Integer> read =
        FunctionNodes.of(
            "read", JsonNode.class, Integer.class, (ctx, node) -> node.get("a").asInt());

    RunResult result = run(chain(tree, read));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/tree@1")).isEqualTo(ImmutableMap.of("a", 1));
    assertThat(result.outputAt("wf@1/read@1")).isEqualTo(1);
  }

  @Test
  public void builder_mapInputWithAToStringKey_failsAtBuild() {
    TypeReference<Map<UserId, String>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class, () -> FunctionNodes.builder(port, String.class));

    assertThat(error).hasMessageThat().contains("writes as a map key with toString()");
  }

  @Test
  public void builder_mapOutputWithAToStringKey_failsAtBuild() {
    TypeReference<Map<UserId, String>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class, () -> FunctionNodes.builder(String.class, port));

    assertThat(error).hasMessageThat().contains("writes as a map key with toString()");
  }

  @Test
  public void builder_mapInputWithAStringCreatorKey_failsAtBuild() {
    // Token writes no object, but its toString() key would come back as a different Token.
    TypeReference<Map<Token, String>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class, () -> FunctionNodes.builder(port, String.class));

    assertThat(error).hasMessageThat().contains("FunctionNodeTest$Token");
    assertThat(error).hasMessageThat().contains("writes as a map key with toString()");
  }

  @Test
  public void builder_mapOutputWithAStringCreatorKey_failsAtBuild() {
    TypeReference<Map<Token, String>> port = new TypeReference<>() {};

    WorkflowConfigurationException error =
        assertThrows(
            WorkflowConfigurationException.class, () -> FunctionNodes.builder(String.class, port));

    assertThat(error).hasMessageThat().contains("FunctionNodeTest$Token");
    assertThat(error).hasMessageThat().contains("writes as a map key with toString()");
  }

  @Test
  public void builder_mapPortsWithKeysJacksonParses_build() {
    TypeReference<Map<BigDecimal, String>> byAmount = new TypeReference<>() {};
    TypeReference<Map<ZoneId, String>> byZone = new TypeReference<>() {};

    assertThat(FunctionNodes.builder(byAmount, byZone)).isNotNull();
    assertThat(FunctionNodes.builder(byZone, byAmount)).isNotNull();
  }

  @Test
  public void of_mapPortsWithANumberKey_readTheKeyBack() {
    TypeReference<Map<Integer, String>> byId = new TypeReference<>() {};
    FunctionNode<Content, Map<Integer, String>> makeMap =
        FunctionNodes.of("make_map", Content.class, byId, (ctx, m) -> ImmutableMap.of(7, "x"));
    FunctionNode<Map<Integer, String>, Integer> read =
        FunctionNodes.of(
            "read", byId, Integer.class, (ctx, map) -> map.keySet().iterator().next() + 1);

    RunResult result = run(chain(makeMap, read));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/read@1")).isEqualTo(8);
  }

  @Test
  public void of_mapPortsWithAJsonValueKey_readTheKeyBack() {
    TypeReference<Map<Tag, String>> tags = new TypeReference<>() {};
    FunctionNode<Content, Map<Tag, String>> makeTags =
        FunctionNodes.of(
            "make_tags", Content.class, tags, (ctx, m) -> ImmutableMap.of(new Tag("t1"), "x"));
    FunctionNode<Map<Tag, String>, String> read =
        FunctionNodes.of(
            "read", tags, String.class, (ctx, map) -> map.keySet().iterator().next().name());

    RunResult result = run(chain(makeTags, read));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/read@1")).isEqualTo("t1");
  }

  private static Optional<Schema> inputSchema(Class<?> type) {
    return FunctionNodes.of("n", type, Void.class, (ctx, input) -> null).inputSchema();
  }

  private static Optional<Schema> inputSchema(TypeReference<?> type) {
    return FunctionNodes.of("n", type, Void.class, (ctx, input) -> null).inputSchema();
  }

  private static ImmutableList<String> requiredInputKeys(Class<?> type) {
    return ImmutableList.copyOf(
        inputSchema(type).orElseThrow().required().orElse(ImmutableList.of()));
  }

  private static Optional<Schema> outputSchema(Class<?> type) {
    return FunctionNodes.of("n", Void.class, type, (ctx, input) -> null).outputSchema();
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

  /**
   * Returns a node {@code router} whose body gives its output to a child {@code child} that outputs
   * {@code childOutput}, and returns nothing itself.
   */
  private static <O> FunctionNode<Content, O> delegating(Class<O> outputType, Object childOutput) {
    return FunctionNodes.builder(Content.class, outputType)
        .name("router")
        .rerunOnResume(true)
        .body(
            (ctx, message) -> {
              Object unused =
                  ctx.runNode(
                          emitter("child", childOutput),
                          null,
                          RunNodeConfig.builder().useAsOutput(true).build())
                      .blockingGet();
              return null;
            })
        .build();
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
        .timeout(30, SECONDS)
        .blockingSubscribe(events::add, error::set);
    return new RunResult(ImmutableList.copyOf(events), error.get());
  }

  /** The events a run streamed and the error it failed with, if any. */
  private record RunResult(ImmutableList<Event> events, @Nullable Throwable error) {

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

    /** Returns the output at {@code nodePath} as a session read back from JSON holds it. */
    @Nullable Object storedOutputAt(String nodePath) {
      return Event.fromJson(outputEventAt(nodePath).toJson()).output().orElse(null);
    }

    /**
     * Asserts that the run has one error event, with {@code errorCode}, and returns its message.
     */
    String errorMessage(String errorCode) {
      Event error = events.stream().filter(e -> e.errorCode().isPresent()).collect(onlyElement());
      assertThat(error.errorCode()).hasValue(new FinishReason(errorCode));
      return error.errorMessage().get();
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
