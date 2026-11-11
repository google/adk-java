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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
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
import com.google.adk.agents.Context;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestUtils;
import com.google.adk.tools.ToolContext;
import com.google.adk.workflow.WorkflowResumeTesting.WorkflowSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.google.genai.types.VideoMetadata;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
  private static final Duration SLOW_APPEND = Duration.ofMillis(100);
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

  record FullOrder(String id, double total, String note) {}

  record BasicOrder(String id, double total) {}

  record OrderWithOptionalNote(String id, double total, @Nullable String note) {}

  /** A record that writes a total it derives, and so does not read. */
  record Invoice(String id, int quantity) {
    public int getTotal() {
      return quantity * 10;
    }
  }

  record Creds(String user, String password) {}

  /** A class with a property it only reads, by a setter. */
  static final class Notepad {
    public String id;

    public void setNote(String note) {}
  }

  record FlatOut(String label, String id, int quantity) {}

  record Qty(int qty) {}

  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  record Count(int count) {}

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  record Tags(String text, List<String> tags) {}

  /** A record whose nested record NON_DEFAULT still writes. */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  record Packed(int count, Order order) {}

  /** A record whose property-level inclusion overrides its class's. */
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  record Overridden(@JsonInclude(JsonInclude.Include.ALWAYS) String text, String note) {}

  /** A class whose NON_DEFAULT omits any value equal to its field's initializer. */
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  static final class Defaults {
    public Status status = Status.OPEN;
    public Order order = new Order("a1", 1);
  }

  record View(Status status, Order order) {}

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  record Labeled(@JsonProperty(required = true) String label, int n) {}

  /** A class that writes its order unwrapped and has two setters for it. */
  static final class OverFlat {
    public String label;

    @JsonUnwrapped
    public Order getOrder() {
      return new Order("a1", 1);
    }

    public void setOrder(Order order) {}

    public void setOrder(Map<String, Object> order) {}
  }

  /** A class that reads its unwrapped order through a setter. */
  static final class SetterFlat {
    public String label;

    @JsonUnwrapped
    public void setOrder(Order order) {}
  }

  record Authored(String author) {}

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
  public void builder_bodyReturningAfterDelegating_dropsTheValueAndOutputsTheChildsAsO() {
    // Arrange: the child's output lacks Ticket's null fields, which writing it as O adds.
    FunctionNode<Content, Ticket> parent =
        FunctionNodes.builder(Content.class, Ticket.class)
            .name("parent")
            .rerunOnResume(true)
            .body((ctx, message) -> {
              Object unused =
                  ctx.runNode(
                          emitter("child", ImmutableMap.of("id", "a1")),
                          null,
                          RunNodeConfig.builder().useAsOutput(true).build())
                      .blockingGet();
              return new Ticket("returned", Optional.empty(), null);
            })
            .build();
    Map<String, @Nullable Object> asTicket = new HashMap<>();
    asTicket.put("id", "a1");
    asTicket.put("tag", null);
    asTicket.put("note", null);

    RunResult result = run(chain(parent, captureInput("reply")));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/parent@1")).isEqualTo(asTicket);
    assertThat(result.storedOutputAt("wf@1/parent@1")).isEqualTo(asTicket);
    assertThat(replyInput(result.events())).isEqualTo(asTicket);
    assertThat(
            result.events().stream()
                .flatMap(e -> e.output().stream())
                .anyMatch(output -> output.toString().contains("returned")))
        .isFalse();
  }

  @Test
  public void builder_bodyBlockingOnAChildThatAsks_resumesWithTheAnswer() {
    // Arrange: a plain body may block on its worker thread until a child finishes.
    FunctionNode<Object, Object> asker =
        FunctionNodes.ofFlowable(
            "asker",
            Object.class,
            Object.class,
            (ctx, input) ->
                Flowable.just(
                    RequestInput.builder().interruptId("name_1").message("name?").build()));
    FunctionNode<Content, Object> parent =
        FunctionNodes.builder(Content.class, Object.class)
            .name("parent")
            .rerunOnResume(true)
            .body((ctx, message) ->
                ctx.runNode(asker, null, RunNodeConfig.builder().runId("b").build()).blockingGet())
            .build();
    WorkflowSession session = new WorkflowSession(chain(parent, captureInput("reply")));
    session.advance(go());
    ImmutableMap<String, Object> details = ImmutableMap.of("name", "John");

    ImmutableList<Event> events =
        session.turn(answer("name_1", details, RequestInput.FUNCTION_CALL_NAME));

    assertThat(replyInput(events)).isEqualTo(details);
  }

  @Test
  public void builder_twoChildrenAndAnEmittedMessageAcrossAPause_replaysTheFinishedChild() {
    // Arrange: a plain body sends a message, runs the first child once, and the second asks.
    AtomicInteger firstRuns = new AtomicInteger();
    FunctionNode<Object, String> first =
        FunctionNodes.of(
            "first",
            Object.class,
            String.class,
            (ctx, input) -> {
              firstRuns.incrementAndGet();
              return "a_out";
            });
    FunctionNode<Object, Object> asker =
        FunctionNodes.ofFlowable(
            "asker",
            Object.class,
            Object.class,
            (ctx, input) ->
                Flowable.just(
                    RequestInput.builder().interruptId("name_1").message("name?").build()));
    FunctionNode<Content, Object> parent =
        FunctionNodes.builder(Content.class, Object.class)
            .name("parent")
            .rerunOnResume(true)
            .body((ctx, message) -> {
              ctx.emit(text("working"));
              Object a =
                  ctx.runNode(first, null, RunNodeConfig.builder().runId("a").build())
                      .blockingGet();
              Object b =
                  ctx.runNode(asker, a, RunNodeConfig.builder().runId("b").build()).blockingGet();
              return ImmutableList.of(a, b);
            })
            .build();
    WorkflowSession session = new WorkflowSession(chain(parent, captureInput("reply")));
    ImmutableList<Event> paused = session.turn(go());
    ImmutableMap<String, Object> details = ImmutableMap.of("name", "John");

    ImmutableList<Event> events =
        session.turn(answer("name_1", details, RequestInput.FUNCTION_CALL_NAME));

    // Assert: the message went out in both turns, as the body ran again.
    assertThat(paused.stream().flatMap(e -> e.content().stream()).map(Content::text))
        .contains("working");
    assertThat(events.stream().flatMap(e -> e.content().stream()).map(Content::text))
        .contains("working");
    assertThat(firstRuns.get()).isEqualTo(1);
    assertThat(replyInput(events)).isEqualTo(ImmutableList.of("a_out", details));
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
    // Arrange: an Object output has no schema, so the build leaves this edge to run time.
    FunctionNode<Content, Object> secret =
        FunctionNodes.of("secret", Content.class, Object.class, (ctx, message) -> "s3cr3t");
    FunctionNode<Order, String> describe =
        FunctionNodes.of("describe", Order.class, String.class, (ctx, order) -> order.id());

    RunResult result = run(chain(secret, describe));

    String message = result.errorMessage("NodeInputValidationException");
    assertThat(message).contains("input of node 'describe'");
    assertThat(message).doesNotContain("s3cr3t");
  }

  @Test
  public void of_valueThatIsNotAContent_failsAContentPortWithoutQuotingTheValue() {
    // Arrange: an Object output has no schema, so the build leaves this edge to run time.
    FunctionNode<Content, Object> secret =
        FunctionNodes.of("secret", Content.class, Object.class, (ctx, message) -> "s3cr3t");
    FunctionNode<Content, String> read =
        FunctionNodes.of("read", Content.class, String.class, (ctx, content) -> content.text());

    RunResult result = run(chain(secret, read));

    String message = result.errorMessage("NodeInputValidationException");
    assertThat(message).contains("input of node 'read'");
    assertThat(message).doesNotContain("s3cr3t");
  }

  @Test
  public void of_body_runsOnIoRatherThanTheRunnersScheduler() {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "app-scheduler"));
    ExecutorService io = Executors.newCachedThreadPool(task -> new Thread(task, "io-stand-in"));
    var previousIoHandler = RxJavaPlugins.getIoSchedulerHandler();
    FunctionNode<Content, String> where =
        FunctionNodes.of(
            "where",
            Content.class,
            String.class,
            (ctx, message) -> Thread.currentThread().getName());

    RunResult result;
    try {
      RxJavaPlugins.setIoSchedulerHandler(unused -> Schedulers.from(io));
      result = run(chain(where), Schedulers.from(executor));
    } finally {
      RxJavaPlugins.setIoSchedulerHandler(previousIoHandler);
      executor.shutdownNow();
      io.shutdownNow();
    }

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/where@1")).isEqualTo("io-stand-in");
  }

  @Test
  public void builderScheduler_body_runsOnThatScheduler() {
    ExecutorService nodeExecutor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "node-scheduler"));
    ExecutorService runnerExecutor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "app-scheduler"));
    FunctionNode<Content, String> where =
        FunctionNodes.builder(Content.class, String.class)
            .name("where")
            .scheduler(Schedulers.from(nodeExecutor))
            .body((ctx, message) -> Thread.currentThread().getName())
            .build();

    RunResult result;
    try {
      result = run(chain(where), Schedulers.from(runnerExecutor));
    } finally {
      nodeExecutor.shutdownNow();
      runnerExecutor.shutdownNow();
    }

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/where@1")).isEqualTo("node-scheduler");
  }

  @Test
  public void runDisposed_sleepingBody_isInterrupted() throws InterruptedException {
    // Arrange: an executor scheduler, which does not interrupt on its own when disposed.
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    ExecutorService executor = Executors.newCachedThreadPool();
    FunctionNode<Content, String> sleeper =
        FunctionNodes.builder(Content.class, String.class)
            .name("sleeper")
            .scheduler(Schedulers.from(executor))
            .body((ctx, message) -> {
              started.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interrupted.countDown();
              }
              return "late";
            })
            .build();
    try {
      Runner runner = runner(chain(sleeper), Schedulers.from(executor));
      Session session = runner.sessionService().createSession(APP, USER).blockingGet();
      Disposable run =
          runner.runAsync(USER, session.id(), Content.fromParts(Part.fromText("go"))).subscribe();
      assertThat(started.await(10, SECONDS)).isTrue();

      run.dispose();

      assertThat(interrupted.await(10, SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void runDisposed_bodyThatKeepsTheInterrupt_leavesTheThreadUninterruptedForTheNextTask()
      throws Exception {
    // Arrange: one thread runs every task and keeps an interrupt between them, unlike a pool.
    BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    AtomicBoolean stopped = new AtomicBoolean();
    Thread worker =
        new Thread(() -> {
          while (!stopped.get()) {
            Uninterruptibles.takeUninterruptibly(tasks).run();
          }
        });
    worker.setDaemon(true);
    worker.start();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    AtomicBoolean sawInterrupt = new AtomicBoolean();
    FunctionNode<Content, String> spinner =
        FunctionNodes.builder(Content.class, String.class)
            .name("spinner")
            .scheduler(Schedulers.from(tasks::add))
            .body((ctx, message) -> {
              started.countDown();
              long deadline = System.nanoTime() + SECONDS.toNanos(10);
              while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
              }
              sawInterrupt.set(Thread.currentThread().isInterrupted());
              finished.countDown();
              return "late";
            })
            .build();
    try {
      Runner runner = runner(chain(spinner), Schedulers.from(tasks::add));
      Session session = runner.sessionService().createSession(APP, USER).blockingGet();
      Disposable run =
          runner.runAsync(USER, session.id(), Content.fromParts(Part.fromText("go"))).subscribe();
      assertThat(started.await(10, SECONDS)).isTrue();

      run.dispose();
      assertThat(finished.await(10, SECONDS)).isTrue();
      CompletableFuture<Boolean> nextTaskInterrupted = new CompletableFuture<>();
      tasks.add(() -> nextTaskInterrupted.complete(Thread.currentThread().isInterrupted()));

      assertThat(sawInterrupt.get()).isTrue();
      assertThat(nextTaskInterrupted.get(10, SECONDS)).isFalse();
    } finally {
      stopped.set(true);
      tasks.add(() -> {});
    }
  }

  @Test
  public void inlineScheduler_runDisposedFromAnotherThread_interruptsTheBodyAndClearsTheInterrupt()
      throws Exception {
    // Arrange: a trampoline scheduler runs the body on the caller's thread, which subscribes.
    CountDownLatch started = new CountDownLatch(1);
    AtomicBoolean sawInterrupt = new AtomicBoolean();
    FunctionNode<Content, String> sleeper =
        FunctionNodes.builder(Content.class, String.class)
            .name("sleeper")
            .scheduler(Schedulers.trampoline())
            .body((ctx, message) -> {
              started.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                sawInterrupt.set(true);
                Thread.currentThread().interrupt();
              }
              return "late";
            })
            .build();
    Runner runner = runner(chain(sleeper), Schedulers.trampoline());
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    TestSubscriber<Event> subscriber = new TestSubscriber<>();
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> callerInterrupted =
          caller.submit(() -> {
            runner
                .runAsync(USER, session.id(), Content.fromParts(Part.fromText("go")))
                .subscribe(subscriber);
            return Thread.interrupted();
          });
      assertThat(started.await(10, SECONDS)).isTrue();

      subscriber.cancel();

      assertThat(callerInterrupted.get(10, SECONDS)).isFalse();
      assertThat(sawInterrupt.get()).isTrue();
    } finally {
      caller.shutdownNow();
    }
  }

  @Test
  public void inlineScheduler_callerAlreadyInterrupted_keepsTheInterruptAfterTheBodyReturns()
      throws Exception {
    // Arrange: a trampoline scheduler runs the body on the caller's thread, interrupted beforehand.
    AtomicBoolean bodySawInterrupt = new AtomicBoolean();
    FunctionNode<Content, String> quick =
        FunctionNodes.builder(Content.class, String.class)
            .name("quick")
            .scheduler(Schedulers.trampoline())
            .body((ctx, message) -> {
              bodySawInterrupt.set(Thread.currentThread().isInterrupted());
              return "done";
            })
            .build();
    Runner runner = runner(chain(quick), Schedulers.trampoline());
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    TestSubscriber<Event> subscriber = new TestSubscriber<>();
    ExecutorService caller = Executors.newSingleThreadExecutor();
    Future<Boolean> callerInterrupted;
    try {
      callerInterrupted =
          caller.submit(() -> {
            Thread.currentThread().interrupt();
            runner
                .runAsync(USER, session.id(), Content.fromParts(Part.fromText("go")))
                .subscribe(subscriber);
            return Thread.interrupted();
          });

      assertThat(callerInterrupted.get(10, SECONDS)).isTrue();
    } finally {
      caller.shutdownNow();
    }

    subscriber.assertComplete().assertNoErrors();
    assertThat(bodySawInterrupt.get()).isTrue();
  }

  @Test
  public void inlineScheduler_callerInterruptedThenRunDisposed_keepsTheCallersInterrupt()
      throws Exception {
    // Arrange: the caller's interrupt and the cancel's both reach the body on the caller's thread.
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch canceled = new CountDownLatch(1);
    AtomicBoolean sawCancel = new AtomicBoolean();
    AtomicBoolean cancelInterruptedTheBody = new AtomicBoolean();
    FunctionNode<Content, String> waiter =
        FunctionNodes.builder(Content.class, String.class)
            .name("waiter")
            .scheduler(Schedulers.trampoline())
            .body((ctx, message) -> {
              boolean callerInterrupt = Thread.interrupted();
              started.countDown();
              sawCancel.set(Uninterruptibles.awaitUninterruptibly(canceled, 10, SECONDS));
              cancelInterruptedTheBody.set(Thread.currentThread().isInterrupted());
              if (callerInterrupt) {
                Thread.currentThread().interrupt();
              }
              return "late";
            })
            .build();
    Runner runner = runner(chain(waiter), Schedulers.trampoline());
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    TestSubscriber<Event> subscriber = new TestSubscriber<>();
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try {
      Future<Boolean> callerInterrupted =
          caller.submit(() -> {
            Thread.currentThread().interrupt();
            runner
                .runAsync(USER, session.id(), Content.fromParts(Part.fromText("go")))
                .subscribe(subscriber);
            return Thread.interrupted();
          });
      assertThat(started.await(10, SECONDS)).isTrue();

      subscriber.cancel();
      canceled.countDown();

      assertThat(callerInterrupted.get(10, SECONDS)).isTrue();
      assertThat(sawCancel.get()).isTrue();
      assertThat(cancelInterruptedTheBody.get()).isTrue();
    } finally {
      caller.shutdownNow();
    }
  }

  @Test
  public void inlineScheduler_bodyInterruptsItself_callerSeesTheInterrupt() throws Exception {
    // Arrange: nothing cancels the node, so only the body interrupts the caller's thread.
    FunctionNode<Content, String> restorer =
        FunctionNodes.builder(Content.class, String.class)
            .name("restorer")
            .scheduler(Schedulers.trampoline())
            .body((ctx, message) -> {
              Thread.currentThread().interrupt();
              return "done";
            })
            .build();
    Runner runner = runner(chain(restorer), Schedulers.trampoline());
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    TestSubscriber<Event> subscriber = new TestSubscriber<>();
    ExecutorService caller = Executors.newSingleThreadExecutor();
    Future<Boolean> callerInterrupted;
    try {
      callerInterrupted =
          caller.submit(() -> {
            runner
                .runAsync(USER, session.id(), Content.fromParts(Part.fromText("go")))
                .subscribe(subscriber);
            return Thread.interrupted();
          });

      assertThat(callerInterrupted.get(10, SECONDS)).isTrue();
    } finally {
      caller.shutdownNow();
    }

    subscriber.assertComplete().assertNoErrors();
  }

  @Test
  public void inlineScheduler_bodyReturns_leavesTheCallerUninterrupted() throws Exception {
    // Arrange: RxJava calls the node's cancellable after the body's result is sent.
    FunctionNode<Content, String> quick =
        FunctionNodes.builder(Content.class, String.class)
            .name("quick")
            .scheduler(Schedulers.trampoline())
            .body((ctx, message) -> "done")
            .build();
    Runner runner = runner(chain(quick), Schedulers.trampoline());
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    TestSubscriber<Event> subscriber = new TestSubscriber<>();
    ExecutorService caller = Executors.newSingleThreadExecutor();
    Future<Boolean> callerInterrupted;
    try {
      callerInterrupted =
          caller.submit(() -> {
            runner
                .runAsync(USER, session.id(), Content.fromParts(Part.fromText("go")))
                .subscribe(subscriber);
            return Thread.interrupted();
          });

      assertThat(callerInterrupted.get(10, SECONDS)).isFalse();
    } finally {
      caller.shutdownNow();
    }

    subscriber.assertComplete().assertNoErrors();
  }

  @Test
  public void nodeTimeout_sleepingBody_isInterruptedAndFailsWithNodeTimeoutException()
      throws InterruptedException {
    CountDownLatch interrupted = new CountDownLatch(1);
    FunctionNode<Content, String> sleeper =
        FunctionNodes.builder(Content.class, String.class)
            .name("sleeper")
            .config(NodeConfig.builder().timeout(Duration.ofSeconds(1)).build())
            .body((ctx, message) -> {
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interrupted.countDown();
              }
              return "late";
            })
            .build();

    RunResult result = run(chain(sleeper));

    assertThat(interrupted.await(10, SECONDS)).isTrue();
    assertThat(result.error()).isInstanceOf(NodeTimeoutException.class);
    assertThat(result.errorMessage("NodeTimeoutException")).contains("'sleeper'");
  }

  @Test
  public void nodeTimeout_sleepingBodyOnBuilderScheduler_isInterrupted()
      throws InterruptedException {
    // An executor scheduler does not interrupt its task on its own when disposed.
    ExecutorService executor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "node-scheduler"));
    AtomicReference<String> interruptedOn = new AtomicReference<>();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    FunctionNode<Content, String> sleeper =
        FunctionNodes.builder(Content.class, String.class)
            .name("sleeper")
            .config(NodeConfig.builder().timeout(Duration.ofSeconds(1)).build())
            .scheduler(Schedulers.from(executor))
            .body((ctx, message) -> {
              started.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interruptedOn.set(Thread.currentThread().getName());
                interrupted.countDown();
              }
              return "late";
            })
            .build();

    RunResult result;
    try {
      result = run(chain(sleeper));
      assertThat(started.await(10, SECONDS)).isTrue();
      assertThat(interrupted.await(10, SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }

    assertThat(result.error()).isInstanceOf(NodeTimeoutException.class);
    assertThat(interruptedOn.get()).isEqualTo("node-scheduler");
  }

  @Test
  public void nodeTimeout_afterTheBodyReturned_doesNotInterruptTheHandlingOfItsOutput() {
    // An interruptible worker interrupts its task when disposed, as io() does.
    ExecutorService executor = Executors.newCachedThreadPool();
    FunctionNode<Content, String> quick =
        FunctionNodes.builder(Content.class, String.class)
            .name("quick")
            .config(NodeConfig.builder().timeout(Duration.ofSeconds(1)).build())
            .scheduler(Schedulers.from(executor, /* interruptibleWorker= */ true))
            .body((ctx, message) -> "out")
            .build();
    Runner runner = runner(chain(quick), /* scheduler= */ null);
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    AtomicBoolean handled = new AtomicBoolean();
    AtomicBoolean interrupted = new AtomicBoolean();
    AtomicReference<Throwable> error = new AtomicReference<>();

    try {
      runner
          .runAsync(USER, session.id(), Content.fromParts(Part.fromText("go")))
          .doOnNext(event -> {
            if (RunResult.pathOf(event).equals("wf@1/quick@1") && event.output().isPresent()) {
              try {
                // Outlasts the timeout on the thread that delivers the node's output.
                Thread.sleep(2000);
                handled.set(true);
              } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
              }
            }
          })
          .timeout(30, SECONDS)
          .blockingSubscribe(event -> {}, error::set);
    } finally {
      executor.shutdownNow();
    }

    assertThat(error.get()).isInstanceOf(NodeTimeoutException.class);
    assertThat(handled.get()).isTrue();
    assertThat(interrupted.get()).isFalse();
  }

  @Test
  public void runDisposed_bodyThatThrowsAfterTheInterrupt_reportsNoUndeliverableError()
      throws InterruptedException {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(1);
    List<Throwable> undeliverable = new CopyOnWriteArrayList<>();
    var previousErrorHandler = RxJavaPlugins.getErrorHandler();
    ExecutorService executor = Executors.newCachedThreadPool();
    FunctionNode<Content, String> sleeper =
        FunctionNodes.builder(Content.class, String.class)
            .name("sleeper")
            .scheduler(Schedulers.from(executor))
            .body((ctx, message) -> {
              started.countDown();
              try {
                Thread.sleep(60_000);
                return "late";
              } finally {
                finished.countDown();
              }
            })
            .build();
    RxJavaPlugins.setErrorHandler(undeliverable::add);
    try {
      Runner runner = runner(chain(sleeper), Schedulers.from(executor));
      Session session = runner.sessionService().createSession(APP, USER).blockingGet();
      Disposable run =
          runner.runAsync(USER, session.id(), Content.fromParts(Part.fromText("go"))).subscribe();
      assertThat(started.await(10, SECONDS)).isTrue();

      run.dispose();
      assertThat(finished.await(10, SECONDS)).isTrue();
      executor.shutdown();
      assertThat(executor.awaitTermination(10, SECONDS)).isTrue();

      assertThat(undeliverable).isEmpty();
    } finally {
      RxJavaPlugins.setErrorHandler(previousErrorHandler);
      executor.shutdownNow();
    }
  }

  @Test
  public void siblingFails_sleepingBody_isInterrupted() throws InterruptedException {
    // Arrange: the workflow cancels the nodes still running when one fails.
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
    ExecutorService executor = Executors.newCachedThreadPool();
    FunctionNode<Content, Void> sleeper =
        FunctionNodes.builder(Content.class, Void.class)
            .name("sleeper")
            .scheduler(Schedulers.from(executor))
            .body((ctx, message) -> {
              started.countDown();
              try {
                Thread.sleep(60_000);
              } catch (InterruptedException e) {
                interrupted.countDown();
              }
              return null;
            })
            .build();
    FunctionNode<Content, Void> broken =
        FunctionNodes.of(
            "broken",
            Content.class,
            Void.class,
            (ctx, message) -> {
              if (!started.await(10, SECONDS)) {
                throw new IllegalStateException("sleeper never started");
              }
              throw new IOException("down");
            });
    Workflow workflow =
        Workflow.builder()
            .name("wf")
            .edges(new Edge(Start.INSTANCE, sleeper), new Edge(Start.INSTANCE, broken))
            .build();

    RunResult result;
    try {
      result = run(workflow, Schedulers.from(executor));
      assertThat(interrupted.await(10, SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }

    assertThat(result.errorMessage("IOException")).isEqualTo("down");
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
  public void toBuilder_copy_keepsTheScheduler() {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "node-scheduler"));
    FunctionNode<Content, String> where =
        FunctionNodes.builder(Content.class, String.class)
            .name("where")
            .scheduler(Schedulers.from(executor))
            .body((ctx, message) -> Thread.currentThread().getName())
            .build();

    RunResult result;
    try {
      result = run(chain(where.toBuilder().name("copy").build()));
    } finally {
      executor.shutdownNow();
    }

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/copy@1")).isEqualTo("node-scheduler");
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
  public void build_recordIntoAClosedRecordThatLacksOneOfItsKeys_failsNamingBothSchemasAndTheFix() {
    // Arrange: the inferred BasicOrder schema takes no note.
    FunctionNode<Content, FullOrder> full = fullOrder();
    FunctionNode<BasicOrder, String> basic =
        FunctionNodes.of("basic", BasicOrder.class, String.class, (ctx, order) -> order.id());

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
                + ". It was inferred from the port's type and takes no other keys; set inputSchema"
                + " on the builder or read the objects as Maps to accept them.");
  }

  @Test
  public void build_recordIntoAClosedRecordWithAnOptionalKeyItLacks_buildsAndRuns() {
    // Arrange: the inferred OrderWithOptionalNote schema lists a note it does not require.
    FunctionNode<Content, BasicOrder> basic =
        FunctionNodes.of(
            "basic", Content.class, BasicOrder.class, (ctx, m) -> new BasicOrder("a1", 9.5));
    FunctionNode<OrderWithOptionalNote, String> noted =
        FunctionNodes.of(
            "noted",
            OrderWithOptionalNote.class,
            String.class,
            (ctx, order) -> order.id() + ":" + order.note());

    // Act
    RunResult result = run(chain(basic, noted));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/noted@1")).isEqualTo("a1:null");
  }

  @Test
  public void build_listOfRecordsIntoAListOfClosedRecords_hintsAtMapsThatThenBuild() {
    // Arrange: the inferred BasicOrder items take no note; map items take any key.
    FunctionNode<Content, List<FullOrder>> fulls = fullOrders();
    FunctionNode<List<BasicOrder>, Integer> basics =
        FunctionNodes.of(
            "basics",
            new TypeReference<List<BasicOrder>>() {},
            Integer.class,
            (ctx, l) -> l.size());
    FunctionNode<List<Map<String, Object>>, Object> maps =
        FunctionNodes.of(
            "maps",
            new TypeReference<List<Map<String, Object>>>() {},
            Object.class,
            (ctx, l) -> l.get(0).get("note"));

    // Act
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(fulls, basics));
    RunResult result = run(chain(fulls, maps));

    // Assert
    assertThat(error)
        .hasMessageThat()
        .endsWith("set inputSchema on the builder or read the objects as Maps to accept them.");
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/maps@1")).isEqualTo("rush");
  }

  @Test
  public void build_recordIntoAHandWrittenSchema_admitsKeysItDoesNotList() {
    // Arrange: a schema set on the builder is open, unlike an inferred one.
    FunctionNode<BasicOrder, String> basic =
        FunctionNodes.builder(BasicOrder.class, String.class)
            .name("basic")
            .inputSchema(object(ImmutableMap.of("id", STRING), "id"))
            .body((ctx, order) -> order.id())
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
        FunctionNodes.of("three", Content.class, Integer.class, (ctx, message) -> 3);
    FunctionNode<Integer, Integer> increment =
        FunctionNodes.builder(Integer.class, Integer.class)
            .name("increment")
            .inputSchema(nullable(INTEGER))
            .body((ctx, n) -> n + 1)
            .build();

    // Act
    RunResult result = run(chain(three, increment));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/increment@1")).isEqualTo(4);
  }

  @Test
  public void build_recordIntoAContentPort_failsWithoutSuggestingABuilderSchema() {
    // Arrange: a builder schema on a Content port describes the text, so it cannot help here.
    FunctionNode<Content, FullOrder> full = fullOrder();
    FunctionNode<Content, String> read =
        FunctionNodes.of("read", Content.class, String.class, (ctx, content) -> content.text());

    // Act
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(full, read));

    // Assert
    assertThat(error).hasMessageThat().startsWith("Schema mismatch on edge full -> read.");
    assertThat(error).hasMessageThat().doesNotContain("inputSchema on the builder");
  }

  @Test
  public void build_contentIntoAStringPort_fails() {
    // Arrange: a returned Content is the output in its JSON form, an object.
    FunctionNode<Content, Content> reply = reply();
    FunctionNode<String, String> shout =
        FunctionNodes.of("shout", String.class, String.class, (ctx, text) -> text);

    // Act
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(reply, shout));

    // Assert: the keys are not the cause, so the message suggests no way to accept them.
    assertThat(error).hasMessageThat().startsWith("Schema mismatch on edge reply -> shout.");
    assertThat(error).hasMessageThat().doesNotContain("takes no other keys");
  }

  @Test
  public void build_recordIntoAnEventPort_fails() {
    // Arrange: an Event port takes only an event's keys, and a FullOrder has others.
    FunctionNode<Content, FullOrder> full = fullOrder();
    FunctionNode<Event, String> author =
        FunctionNodes.of("author", Event.class, String.class, (ctx, event) -> event.author());

    // Act
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(full, author));

    // Assert
    assertThat(error).hasMessageThat().startsWith("Schema mismatch on edge full -> author.");
  }

  @Test
  public void build_contentIntoContentOrObjectPorts_buildsAndRuns() {
    // Arrange
    FunctionNode<Content, String> read =
        FunctionNodes.of("read", Content.class, String.class, (ctx, content) -> content.text());
    FunctionNode<Object, String> any =
        FunctionNodes.of("any", Object.class, String.class, (ctx, value) -> "ok");

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
            .body((ctx, content) -> content.text())
            .build();
    FunctionNode<Content, FullOrder> full = fullOrder();

    // Act
    RunResult result = run(chain(reply(), text));
    WorkflowConfigurationException error =
        assertThrows(WorkflowConfigurationException.class, () -> chain(full, text));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/text@1")).isEqualTo("hi");
    assertThat(error).hasMessageThat().startsWith("Schema mismatch on edge full -> text.");
  }

  @Test
  public void build_edgeIntoANodeThatIsNotAFunctionNode_checksItsOpenSchema() {
    // Arrange: such a node infers nothing, so its schema takes keys it does not list.
    FunctionNode<Content, String> text =
        FunctionNodes.of("text", Content.class, String.class, (ctx, message) -> message.text());
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
  public void build_recordsIntoPortsThatReadOtherKeysThanTheyWrite_buildAndRun() {
    // Arrange: Invoice writes a total it does not read; Login reads a password it does not write.
    FunctionNode<Content, Order> order =
        FunctionNodes.of("order", Content.class, Order.class, (ctx, m) -> new Order("a1", 2));
    FunctionNode<Invoice, String> invoice =
        FunctionNodes.of(
            "invoice", Invoice.class, String.class, (ctx, i) -> i.id() + "=" + i.getTotal());
    FunctionNode<Content, Creds> creds =
        FunctionNodes.of("creds", Content.class, Creds.class, (ctx, m) -> new Creds("ann", "pw"));
    FunctionNode<Login, String> login =
        FunctionNodes.of(
            "login", Login.class, String.class, (ctx, l) -> l.user() + ":" + l.password().length());

    // Act
    RunResult invoiced = run(chain(order, invoice));
    RunResult loggedIn = run(chain(creds, login));

    // Assert
    invoiced.assertCompleted();
    loggedIn.assertCompleted();
    assertThat(invoiced.outputAt("wf@1/invoice@1")).isEqualTo("a1=20");
    assertThat(loggedIn.outputAt("wf@1/login@1")).isEqualTo("ann:2");
  }

  @Test
  public void build_contentIntoASchemaWithAnOptionalRole_buildsAndRuns() {
    // Arrange: a Content omits an unset role rather than writing null.
    FunctionNode<Content, Content> model =
        FunctionNodes.of(
            "model",
            Content.class,
            Content.class,
            (ctx, m) -> Content.builder().role("model").parts(Part.fromText("hi")).build());
    FunctionNode<Map<String, Object>, Object> role =
        FunctionNodes.builder(new TypeReference<Map<String, Object>>() {}, Object.class)
            .name("role")
            .inputSchema(object(ImmutableMap.of("role", STRING)))
            .body((ctx, message) -> message.get("role"))
            .build();

    // Act
    RunResult result = run(chain(model, role));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/role@1")).isEqualTo("model");
  }

  @Test
  public void build_recordWithAnEventKeyIntoAnEventPort_buildsAndRuns() {
    // Arrange: an Event port reads each key as optional, as Event.fromJson does.
    FunctionNode<Content, Authored> authored =
        FunctionNodes.of(
            "authored", Content.class, Authored.class, (ctx, m) -> new Authored("ann"));
    FunctionNode<Event, String> author =
        FunctionNodes.of("author", Event.class, String.class, (ctx, event) -> event.author());

    // Act
    RunResult result = run(chain(authored, author));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/author@1")).isEqualTo("ann");
  }

  @Test
  public void schemas_propertiesOnlyReadOrOnlyWritten_appearOnlyInThatPortsSchema() {
    // Act
    Optional<Schema> notepadIn = inputSchema(Notepad.class);
    Optional<Schema> notepadOut = outputSchema(Notepad.class);
    Optional<Schema> invoiceIn = inputSchema(Invoice.class);
    Optional<Schema> invoiceOut = outputSchema(Invoice.class);
    Optional<Schema> loginIn = inputSchema(Login.class);
    Optional<Schema> loginOut = outputSchema(Login.class);

    // Assert: Jackson leaves a field or setter property it does not read at its default.
    assertThat(notepadIn).hasValue(object(ImmutableMap.of("id", STRING, "note", STRING)));
    assertThat(notepadOut).hasValue(object(ImmutableMap.of("id", STRING), "id"));
    assertThat(invoiceIn).hasValue(ORDER);
    assertThat(invoiceOut.orElseThrow().required())
        .hasValue(ImmutableList.of("id", "quantity", "total"));
    assertThat(loginIn)
        .hasValue(object(ImmutableMap.of("user", STRING, "password", STRING), "user"));
    assertThat(loginOut)
        .hasValue(object(ImmutableMap.of("user", STRING, "role", STRING), "user", "role"));
  }

  @Test
  public void build_sameTypeUnderClassInclusionOrFlatKeysIntoUnwrapped_builds() {
    // Arrange: NON_NULL omits only nullable keys, and an unwrapped port infers no schema.
    FunctionNode<Content, Counted> counts =
        FunctionNodes.of(
            "counts",
            Content.class,
            Counted.class,
            (ctx, m) -> new Counted(1, "a", null, Optional.empty()));
    FunctionNode<Counted, Integer> count =
        FunctionNodes.of("count", Counted.class, Integer.class, (ctx, c) -> c.count());
    FunctionNode<Content, FlatOut> flatOut =
        FunctionNodes.of(
            "flat_out", Content.class, FlatOut.class, (ctx, m) -> new FlatOut("l", "a1", 2));
    FunctionNode<Flat, String> flat =
        FunctionNodes.of("flat", Flat.class, String.class, (ctx, f) -> f.label());
    FunctionNode<SetterFlat, String> setterFlat =
        FunctionNodes.of("setter_flat", SetterFlat.class, String.class, (ctx, f) -> f.label);

    // Act
    Workflow counted = chain(counts, count);
    Workflow flattened = chain(flatOut, flat);
    Workflow setterFlattened = chain(flatOut, setterFlat);

    // Assert
    assertThat(counted.graph()).isPresent();
    assertThat(flattened.graph()).isPresent();
    assertThat(setterFlattened.graph()).isPresent();
  }

  @Test
  public void build_sameTypeUnderClassNonDefaultOrNonEmpty_builds() {
    // Arrange: such a key may be omitted on write, and an absent one reads as its default.
    FunctionNode<Content, Count> makeCount =
        FunctionNodes.of("make_count", Content.class, Count.class, (ctx, m) -> new Count(0));
    FunctionNode<Count, Integer> readCount =
        FunctionNodes.of("read_count", Count.class, Integer.class, (ctx, c) -> c.count());
    FunctionNode<Content, Tags> makeTags =
        FunctionNodes.of(
            "make_tags", Content.class, Tags.class, (ctx, m) -> new Tags("", ImmutableList.of()));
    FunctionNode<Tags, String> readTags =
        FunctionNodes.of("read_tags", Tags.class, String.class, (ctx, t) -> t.text());

    // Act
    Workflow counts = chain(makeCount, readCount);
    Workflow tags = chain(makeTags, readTags);

    // Assert
    assertThat(counts.graph()).isPresent();
    assertThat(tags.graph()).isPresent();
  }

  @Test
  public void build_producerThatMayOmitAKeyTheTargetNeeds_fails() {
    // Arrange: Defaults omits a value equal to its initializer, and Labeled an empty label.
    FunctionNode<Content, Defaults> defaults =
        FunctionNodes.of("defaults", Content.class, Defaults.class, (ctx, m) -> new Defaults());
    FunctionNode<View, Status> view =
        FunctionNodes.of("view", View.class, Status.class, (ctx, v) -> v.status());
    FunctionNode<Content, Labeled> makeLabeled =
        FunctionNodes.of(
            "make_labeled", Content.class, Labeled.class, (ctx, m) -> new Labeled("", 1));
    FunctionNode<Labeled, String> readLabeled =
        FunctionNodes.of("read_labeled", Labeled.class, String.class, (ctx, l) -> l.label());

    // Act
    WorkflowConfigurationException intoView =
        assertThrows(WorkflowConfigurationException.class, () -> chain(defaults, view));
    WorkflowConfigurationException intoLabeled =
        assertThrows(WorkflowConfigurationException.class, () -> chain(makeLabeled, readLabeled));

    // Assert
    assertThat(intoView).hasMessageThat().startsWith("Schema mismatch on edge defaults -> view.");
    assertThat(intoLabeled)
        .hasMessageThat()
        .startsWith("Schema mismatch on edge make_labeled -> read_labeled.");
  }

  @Test
  public void outputSchema_unwrappedGetterBesideOverloadedSetters_inferNone() {
    // Act
    Optional<Schema> schema = outputSchema(OverFlat.class);

    // Assert: Jackson cannot pick a setter, but the getter still writes the order's keys.
    assertThat(schema).isEmpty();
  }

  @Test
  public void outputSchema_nonDefaultNestedRecordOrPropertyLevelAlways_staysRequired() {
    // Act
    Optional<Schema> packed = outputSchema(Packed.class);
    Optional<Schema> overridden = outputSchema(Overridden.class);

    // Assert: NON_DEFAULT always writes a non-null record.
    assertThat(packed).hasValue(object(ImmutableMap.of("count", INTEGER, "order", ORDER), "order"));
    assertThat(overridden)
        .hasValue(object(ImmutableMap.of("text", STRING, "note", STRING), "text"));
  }

  @Test
  public void build_recordIntoAnAliasOfItsKey_buildsAndRuns() {
    // Arrange: Aliased reads its quantity from a qty key too.
    FunctionNode<Content, Qty> qty =
        FunctionNodes.of("qty", Content.class, Qty.class, (ctx, m) -> new Qty(3));
    FunctionNode<Aliased, String> aliased =
        FunctionNodes.of("aliased", Aliased.class, String.class, (ctx, a) -> "x" + a.quantity());

    // Act
    RunResult result = run(chain(qty, aliased));

    // Assert
    result.assertCompleted();
    assertThat(result.outputAt("wf@1/aliased@1")).isEqualTo("x3");
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
    // Arrange: a number feeds a list port, by an Object output the build does not check.
    FunctionNode<Content, Object> number =
        FunctionNodes.of("number", Content.class, Object.class, (ctx, message) -> 123);
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
  public void ofFlowable_twoMessagesThenAValue_sendsThemInOrderBeforeTheOutput() {
    FunctionNode<Content, String> chatty =
        FunctionNodes.ofFlowable(
            "chatty",
            Content.class,
            String.class,
            (ctx, message) ->
                Flowable.just(
                    Event.builder()
                        .author("some_agent")
                        .content(Content.fromParts(Part.fromText("event 1")))
                        .build(),
                    Event.builder().content(Content.fromParts(Part.fromText("event 2"))).build(),
                    "done"));

    RunResult result = run(chain(chatty));

    result.assertCompleted();
    ImmutableList<Event> events = result.eventsAt("wf@1/chatty@1");
    assertThat(events).hasSize(3);
    assertThat(events.get(0).content().get().text()).isEqualTo("event 1");
    assertThat(events.get(1).content().get().text()).isEqualTo("event 2");
    assertThat(events.get(2).output()).hasValue("done");
  }

  @Test
  public void ofFlowable_genericPortTypes_readAndWriteThePortTypes() {
    FunctionNode<Content, List<Order>> orders =
        FunctionNodes.ofFlowable(
            "orders",
            Content.class,
            new TypeReference<List<Order>>() {},
            (ctx, message) -> Flowable.just(ImmutableList.of(new Order("a1", 3))));
    FunctionNode<List<Order>, List<String>> ids =
        FunctionNodes.ofFlowable(
            "ids",
            new TypeReference<List<Order>>() {},
            new TypeReference<List<String>>() {},
            (ctx, list) -> Flowable.just(ImmutableList.of(list.get(0).id())));
    FunctionNode<List<String>, Integer> count =
        FunctionNodes.ofFlowable(
            "count",
            new TypeReference<List<String>>() {},
            Integer.class,
            (ctx, list) -> Flowable.just(list.size()));

    RunResult result = run(chain(orders, ids, count));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/orders@1"))
        .isEqualTo(ImmutableList.of(ImmutableMap.of("id", "a1", "quantity", 3)));
    assertThat(result.outputAt("wf@1/ids@1")).isEqualTo(ImmutableList.of("a1"));
    assertThat(result.outputAt("wf@1/count@1")).isEqualTo(1);
    assertThat(orders.outputSchema()).isPresent();
    assertThat(ids.inputSchema()).isPresent();
    assertThat(ids.outputSchema()).isPresent();
    assertThat(count.inputSchema()).isPresent();
  }

  @Test
  public void ofFlowable_emptyMaybe_sendsNoOutput() {
    FunctionNode<Content, String> quiet =
        FunctionNodes.ofFlowable(
            "quiet", Content.class, String.class, (ctx, message) -> Maybe.empty().toFlowable());

    RunResult result = run(chain(quiet));

    result.assertCompleted();
    assertThat(result.events().stream().anyMatch(e -> e.output().isPresent())).isFalse();
  }

  @Test
  public void ofFlowable_eventWithARecordOutputOnAnEventNode_sendsItsJsonForm() {
    FunctionNode<Content, Event> lookUp =
        FunctionNodes.ofFlowable(
            "look_up",
            Content.class,
            Event.class,
            (ctx, message) -> Flowable.just(Event.builder().output(new Order("a1", 3)).build()));

    RunResult result = run(chain(lookUp));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/look_up@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3));
  }

  @Test
  public void ofFlowable_itemOfAnotherType_failsTheNode() {
    FunctionNode<Content, String> wrong =
        FunctionNodes.ofFlowable(
            "wrong", Content.class, String.class, (ctx, message) -> Flowable.just(42));

    RunResult result = run(chain(wrong));

    assertThat(result.errorMessage("IllegalArgumentException"))
        .contains("output of node 'wrong' expected");
  }

  @Test
  public void ofFlowable_bodyReturningAnErrorOrThrowing_failsTheNode() {
    FunctionNode<Content, String> erroring =
        FunctionNodes.ofFlowable(
            "broken",
            Content.class,
            String.class,
            (ctx, m) -> Flowable.error(new IOException("down")));
    FunctionNode<Content, String> throwing =
        FunctionNodes.ofFlowable(
            "broken",
            Content.class,
            String.class,
            (ctx, m) -> {
              throw new IOException("down");
            });

    RunResult errored = run(chain(erroring));
    RunResult thrown = run(chain(throwing));

    assertThat(errored.errorMessage("IOException")).isEqualTo("down");
    assertThat(thrown.errorMessage("IOException")).isEqualTo("down");
  }

  @Test
  public void ofFlowable_twoValueItems_sendsTheFirstAndFailsTheNodeOnTheSecond() {
    FunctionNode<Content, String> twice =
        FunctionNodes.ofFlowable(
            "twice", Content.class, String.class, (ctx, message) -> Flowable.just("a", "b"));

    RunResult result = run(chain(twice));

    assertThat(result.outputAt("wf@1/twice@1")).isEqualTo("a");
    assertThat(result.errorMessage("IllegalStateException"))
        .isEqualTo("Node 'twice' produced a second output; a node produces at most one output.");
  }

  @Test
  public void ofFlowable_inputThatDoesNotFitThePort_failsTheNode() {
    FunctionNode<Content, Object> secret =
        FunctionNodes.of("secret", Content.class, Object.class, (ctx, message) -> "s3cr3t");
    FunctionNode<Order, String> describe =
        FunctionNodes.ofFlowable(
            "describe", Order.class, String.class, (ctx, order) -> Flowable.just(order.id()));

    RunResult result = run(chain(secret, describe));

    assertThat(result.errorMessage("NodeInputValidationException")).contains("describe");
  }

  @Test
  public void ofFlowable_requestInputAnsweredWithAMapOnARecordOutput_outputsTheRecordJsonForm() {
    // Arrange: Order drops the extra key and writes the Long quantity as its int.
    FunctionNode<Content, Order> asker =
        FunctionNodes.ofFlowable(
            "asker",
            Content.class,
            Order.class,
            (ctx, message) ->
                Flowable.just(RequestInput.builder().interruptId("o_1").message("order?").build()));
    WorkflowSession session = new WorkflowSession(chain(asker, captureInput("reply")));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(
            answer(
                "o_1",
                ImmutableMap.of(
                    "result", ImmutableMap.of("id", "a1", "quantity", 3L, "gift", true)),
                RequestInput.FUNCTION_CALL_NAME));

    ImmutableMap<String, Object> asOrder = ImmutableMap.of("id", "a1", "quantity", 3);
    assertThat(WorkflowResumeTesting.outputEventAt(events, "wf@1/asker@1").output())
        .hasValue(asOrder);
    assertThat(replyInput(events)).isEqualTo(asOrder);
  }

  @Test
  public void
      ofFlowable_requestInputAnsweredWithAnotherType_failsNamingTheAskerBeforeTheSuccessor() {
    FunctionNode<Content, Integer> asker =
        FunctionNodes.ofFlowable(
            "asker",
            Content.class,
            Integer.class,
            (ctx, message) ->
                Flowable.just(RequestInput.builder().interruptId("n_1").message("n?").build()));
    FunctionNode<Integer, Integer> twice =
        FunctionNodes.of("twice", Integer.class, Integer.class, (ctx, n) -> n * 2);
    WorkflowSession session = new WorkflowSession(chain(asker, twice));
    session.advance(go());

    WorkflowResumeTesting.FailedTurn turn =
        session.failingTurn(
            answer("n_1", ImmutableMap.of("result", "abc"), RequestInput.FUNCTION_CALL_NAME));

    assertThat(turn.error()).isInstanceOf(WorkflowDataException.class);
    assertThat(turn.error())
        .hasMessageThat()
        .isEqualTo(
            "validation error: answer to node 'asker' (interrupt 'n_1') does not match"
                + " java.lang.Integer.");
    assertThat(WorkflowResumeTesting.pathsOf(turn.events())).doesNotContain("wf@1/twice@1");
  }

  @Test
  public void ofFlowable_requestInputAnsweredWithTextOnARecordOutput_failsNamingTheNode() {
    FunctionNode<Content, Order> asker =
        FunctionNodes.ofFlowable(
            "asker",
            Content.class,
            Order.class,
            (ctx, message) ->
                Flowable.just(RequestInput.builder().interruptId("o_1").message("order?").build()));
    WorkflowSession session = new WorkflowSession(chain(asker));
    session.advance(go());

    WorkflowResumeTesting.FailedTurn turn =
        session.failingTurn(
            answer("o_1", ImmutableMap.of("result", "secret"), RequestInput.FUNCTION_CALL_NAME));

    assertThat(turn.error())
        .hasMessageThat()
        .isEqualTo(
            "validation error: answer to node 'asker' (interrupt 'o_1') does not match"
                + " com.google.adk.workflow.FunctionNodeTest$Order.");
  }

  @Test
  public void ofFlowable_requestInputBadAnswerThenACorrectedOne_recoversWithTheTypedOutput() {
    FunctionNode<Content, Order> asker =
        FunctionNodes.ofFlowable(
            "asker",
            Content.class,
            Order.class,
            (ctx, message) ->
                Flowable.just(RequestInput.builder().interruptId("o_1").message("order?").build()));
    WorkflowSession session = new WorkflowSession(chain(asker, captureInput("reply")));
    session.advance(go());

    WorkflowResumeTesting.FailedTurn failed =
        session.failingTurn(
            answer("o_1", ImmutableMap.of("result", "secret"), RequestInput.FUNCTION_CALL_NAME));
    ImmutableList<Event> events =
        session.turn(
            answer(
                "o_1",
                ImmutableMap.of("result", ImmutableMap.of("id", "a1", "quantity", 3L)),
                RequestInput.FUNCTION_CALL_NAME));

    assertThat(failed.error()).hasMessageThat().contains("node 'asker' (interrupt 'o_1')");
    assertThat(replyInput(events)).isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3));
  }

  @Test
  public void ofFlowable_requestInputAnsweredWithNumericTextOnAStringOutput_outputsTheText() {
    FunctionNode<Content, String> asker =
        FunctionNodes.ofFlowable(
            "asker",
            Content.class,
            String.class,
            (ctx, message) ->
                Flowable.just(
                    RequestInput.builder().interruptId("t_1").message("ticket?").build()));
    WorkflowSession session = new WorkflowSession(chain(asker, captureInput("reply")));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(
            answer("t_1", ImmutableMap.of("result", "12345"), RequestInput.FUNCTION_CALL_NAME));

    assertThat(replyInput(events)).isEqualTo("12345");
  }

  @Test
  public void ofFlowable_requestInputAnsweredOnAVoidOutput_completesWithoutAnOutput() {
    FunctionNode<Content, Void> asker =
        FunctionNodes.ofFlowable(
            "asker",
            Content.class,
            Void.class,
            (ctx, message) ->
                Flowable.just(RequestInput.builder().interruptId("p_1").message("go on?").build()));
    WorkflowSession session = new WorkflowSession(chain(asker, captureInput("reply")));
    session.advance(go());

    ImmutableList<Event> events =
        session.turn(
            answer("p_1", ImmutableMap.of("result", "yes"), RequestInput.FUNCTION_CALL_NAME));

    assertThat(
            events.stream()
                .anyMatch(e ->
                    WorkflowResumeTesting.pathOf(e).equals("wf@1/asker@1")
                        && e.output().isPresent()))
        .isFalse();
    assertThat(replyInput(events)).isNull();
  }

  @Test
  public void ofFlowable_messageAsOutputEvent_failsTheNode() {
    Content message = Content.fromParts(Part.fromText("hello"));
    Event marked =
        Event.builder()
            .content(message)
            .nodeInfo(NodeInfo.builder().messageAsOutput(true).build())
            .build();

    RunResult onContent = run(chain(emitting("greeter", Content.class, marked)));
    RunResult withAnOutput =
        run(chain(emitting("greeter", Content.class, marked.toBuilder().output(message).build())));
    RunResult partialOnEvent =
        run(chain(emitting("greeter", Event.class, marked.toBuilder().partial(true).build())));

    String rejection =
        "node 'greeter' emitted an Event marked messageAsOutput; send the message as a plain"
            + " event and return the Content.";
    assertThat(onContent.errorMessage("IllegalArgumentException")).isEqualTo(rejection);
    assertThat(withAnOutput.errorMessage("IllegalArgumentException")).isEqualTo(rejection);
    assertThat(partialOnEvent.errorMessage("IllegalArgumentException")).isEqualTo(rejection);
    assertThat(onContent.eventsAt("wf@1/greeter@1").stream().collect(onlyElement()).errorCode())
        .isPresent();
  }

  @Test
  public void ofFlowable_messageEventThenTheSameContent_successorGetsItLiveAndOnResume() {
    // Arrange: the successor pauses on its first run and runs again on the resume.
    Content message = Content.fromParts(Part.fromText("hello"));
    FunctionNode<Content, Content> greeter =
        FunctionNodes.ofFlowable(
            "greeter",
            Content.class,
            Content.class,
            (ctx, input) -> Flowable.just(Event.builder().content(message).build(), message));
    List<@Nullable Object> inputs = new ArrayList<>();
    FunctionNode<Content, String> asker =
        FunctionNodes.builder(Content.class, String.class)
            .name("asker")
            .rerunOnResume(true)
            .flowableBody((ctx, input) -> {
              inputs.add(input);
              return inputs.size() == 1
                  ? Flowable.just(RequestInput.builder().interruptId("ok_1").message("ok?").build())
                  : Flowable.just("done");
            })
            .build();
    WorkflowSession session = new WorkflowSession(chain(greeter, asker));

    ImmutableList<Event> first = session.turn(go());
    ImmutableList<Event> second =
        session.turn(answer("ok_1", ImmutableMap.of("ok", true), RequestInput.FUNCTION_CALL_NAME));

    // Assert: the user sees the message once, and both runs of the successor get the same Content.
    assertThat(
            first.stream()
                .filter(e -> WorkflowResumeTesting.pathOf(e).equals("wf@1/greeter@1"))
                .flatMap(e -> e.content().stream()))
        .containsExactly(message);
    assertThat(second.stream().filter(e -> e.errorCode().isPresent())).isEmpty();
    assertThat(second.stream().filter(e -> e.content().equals(Optional.of(message)))).isEmpty();
    assertThat(inputs).containsExactly(message, message);
  }

  @Test
  public void ofFlowable_eventOutputOnAnIntegerNode_failsTheNodeUnlessItIsAnInteger() {
    RunResult fits =
        run(chain(emitting("count", Integer.class, Event.builder().output(42).build())));
    RunResult misfits =
        run(chain(emitting("count", Integer.class, Event.builder().output("abc").build())));

    fits.assertCompleted();
    assertThat(fits.outputAt("wf@1/count@1")).isEqualTo(42);
    assertThat(misfits.errorMessage("IllegalArgumentException"))
        .isEqualTo("output of node 'count' expected java.lang.Integer, but got String.");
    assertThat(misfits.eventsAt("wf@1/count@1").stream().collect(onlyElement()).errorCode())
        .isPresent();
  }

  @Test
  public void ofFlowable_eventWithARecordOutputOnARecordNode_sendsItsJsonForm() {
    Event event = Event.builder().output(new Order("a1", 3)).build();

    RunResult result = run(chain(emitting("order", Order.class, event)));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/order@1"))
        .isEqualTo(ImmutableMap.of("id", "a1", "quantity", 3));
  }

  @Test
  public void ofFlowable_bodyDelegatingItsOutput_outputsTheChildsOutputWrittenAsO() {
    FunctionNode<Content, Ticket> router =
        FunctionNodes.builder(Content.class, Ticket.class)
            .name("router")
            .rerunOnResume(true)
            .flowableBody((ctx, message) ->
                ctx.runNode(
                        emitter("child", ImmutableMap.of("id", "a1")),
                        null,
                        RunNodeConfig.builder().useAsOutput(true).build())
                    .ignoreElement()
                    .toFlowable())
            .build();
    Map<String, @Nullable Object> asTicket = new HashMap<>();
    asTicket.put("id", "a1");
    asTicket.put("tag", null);
    asTicket.put("note", null);

    RunResult result = run(chain(router));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/router@1/child@1")).isEqualTo(ImmutableMap.of("id", "a1"));
    assertThat(result.outputAt("wf@1/router@1")).isEqualTo(asTicket);
  }

  @Test
  public void ofFlowable_twoChildrenAndAMessageAcrossAPause_replaysTheFinishedChild() {
    // Arrange: the first child runs once; the second asks, and the answer is its output.
    AtomicInteger firstRuns = new AtomicInteger();
    FunctionNode<Object, String> first =
        FunctionNodes.of(
            "first",
            Object.class,
            String.class,
            (ctx, input) -> {
              firstRuns.incrementAndGet();
              return "a_out";
            });
    FunctionNode<Object, Object> asker =
        FunctionNodes.ofFlowable(
            "asker",
            Object.class,
            Object.class,
            (ctx, input) ->
                Flowable.just(
                    RequestInput.builder().interruptId("name_1").message("name?").build()));
    FunctionNode<Content, Object> parent =
        FunctionNodes.builder(Content.class, Object.class)
            .name("parent")
            .rerunOnResume(true)
            .flowableBody((ctx, message) ->
                Flowable.concat(
                    Flowable.just(
                        Event.builder()
                            .content(Content.fromParts(Part.fromText("working")))
                            .build()),
                    ctx.runNode(first, null, RunNodeConfig.builder().runId("a").build())
                        .flatMap(a ->
                            ctx.runNode(asker, a, RunNodeConfig.builder().runId("b").build())
                                .map(b -> ImmutableList.of(a, b)))
                        .toFlowable()))
            .build();
    WorkflowSession session = new WorkflowSession(chain(parent, captureInput("reply")));
    session.advance(go());
    ImmutableMap<String, Object> details = ImmutableMap.of("name", "John");

    ImmutableList<Event> events =
        session.turn(answer("name_1", details, RequestInput.FUNCTION_CALL_NAME));

    // Assert: the body ran again and sent its message again.
    assertThat(events.stream().flatMap(e -> e.content().stream()).map(Content::text))
        .contains("working");
    assertThat(firstRuns.get()).isEqualTo(1);
    assertThat(replyInput(events)).isEqualTo(ImmutableList.of("a_out", details));
  }

  @Test
  public void ofFlowable_body_isSubscribedWithoutMovingToTheRunnersScheduler() throws Exception {
    // Arrange: the Runner's scheduler runs on the "app-scheduler" thread.
    ExecutorService executor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "app-scheduler"));
    ExecutorService caller =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "runner-caller"));
    FunctionNode<Content, String> where =
        FunctionNodes.ofFlowable(
            "where",
            Content.class,
            String.class,
            (ctx, message) -> Flowable.just(Thread.currentThread().getName()));

    RunResult result;
    try {
      result = caller.submit(() -> run(chain(where), Schedulers.from(executor))).get(30, SECONDS);
    } finally {
      executor.shutdownNow();
      caller.shutdownNow();
    }

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/where@1")).isEqualTo("runner-caller");
  }

  @Test
  public void builderScheduler_flowableBody_isSubscribedOnTheCallingThread() throws Exception {
    ExecutorService nodeExecutor =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "node-scheduler"));
    ExecutorService caller =
        Executors.newSingleThreadExecutor(task -> new Thread(task, "runner-caller"));
    FunctionNode<Content, String> where =
        FunctionNodes.builder(Content.class, String.class)
            .name("where")
            .scheduler(Schedulers.from(nodeExecutor))
            .flowableBody((ctx, message) -> Flowable.just(Thread.currentThread().getName()))
            .build();

    RunResult result;
    try {
      result = caller.submit(() -> run(chain(where))).get(30, SECONDS);
    } finally {
      nodeExecutor.shutdownNow();
      caller.shutdownNow();
    }

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/where@1")).isEqualTo("runner-caller");
  }

  @Test
  public void ofFlowable_runDisposed_disposesTheBodysFlowable() throws InterruptedException {
    CountDownLatch subscribed = new CountDownLatch(1);
    CountDownLatch canceled = new CountDownLatch(1);
    FunctionNode<Content, String> waiting =
        FunctionNodes.ofFlowable(
            "waiting",
            Content.class,
            String.class,
            (ctx, message) ->
                Flowable.<String>never()
                    .doOnSubscribe(unused -> subscribed.countDown())
                    .doOnCancel(canceled::countDown));
    Runner runner = runner(chain(waiting), /* scheduler= */ null);
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    Disposable run =
        runner.runAsync(USER, session.id(), Content.fromParts(Part.fromText("go"))).subscribe();
    assertThat(subscribed.await(10, SECONDS)).isTrue();

    run.dispose();

    assertThat(canceled.await(10, SECONDS)).isTrue();
  }

  @Test
  public void emit_stateWrittenAroundEvents_ridesTheNextEventAndReadsBackAfterTheStore()
      throws Exception {
    onEveryScheduler(scheduler -> {
      SlowStore store = new SlowStore(scheduler, SLOW_APPEND);
      AtomicReference<String> readBack = new AtomicReference<>();
      FunctionNode<Content, Object> counter =
          node(
              "counter",
              (ctx, message) -> {
                ctx.state().put("n", 1);
                ctx.emit(text("first"));
                ctx.state().put("n", 2);
                // "first" is stored by now, so its n=1 can no longer land over n=2.
                readBack.set(ctx.state().get("n") + " " + store.storedTexts);
                ctx.emit(text("second"));
                ctx.state().put("after_second", 3);
                return ctx.state().get("n");
              });

      RunResult result = run(chain(counter), scheduler, store);

      assertThat(readBack.get()).isEqualTo("2 [first]");
      result.assertCompleted();
      assertThat(
              result.eventsAt("wf@1/counter@1").stream()
                  .map(e -> describe(e) + e.actions().stateDelta()))
          .containsExactly("first{n=1}", "second{n=2}", "2{after_second=3}")
          .inOrder();
    });
  }

  @Test
  public void emit_fromTheBodyOrAHelperStillWaitingAtReturn_returnsOnceStoredAndLaterCallsThrow()
      throws Exception {
    onEveryScheduler(scheduler -> {
      // Arrange: the body returns no output while its helper still waits in emit.
      SlowStore store = new SlowStore(scheduler, SLOW_APPEND);
      AtomicReference<ImmutableList<String>> storedWhenHelperReturned = new AtomicReference<>();
      ExecutorService helper = Executors.newSingleThreadExecutor();
      AtomicReference<Future<?>> helperEmit = new AtomicReference<>();
      AtomicReference<Context> bodyContext = new AtomicReference<>();
      FunctionNode<Content, Object> reporter =
          node(
              "reporter",
              (ctx, message) -> {
                bodyContext.set(ctx);
                ctx.emit(text("from the body"));
                helperEmit.set(
                    helper.submit(() -> {
                      ctx.emit(text("from a helper"));
                      storedWhenHelperReturned.set(ImmutableList.copyOf(store.storedTexts));
                    }));
                assertThat(store.appendsStarted.tryAcquire(2, 10, SECONDS)).isTrue();
                return null;
              });

      ExecutionException lateError;
      try {
        run(chain(reporter), scheduler, store).assertCompleted();
        helperEmit.get().get(10, SECONDS);
        Future<?> lateEmit = helper.submit(() -> bodyContext.get().emit(text("late")));
        lateError = assertThrows(ExecutionException.class, () -> lateEmit.get(10, SECONDS));
      } finally {
        helper.shutdownNow();
      }

      assertThat(storedWhenHelperReturned.get())
          .containsExactly("from the body", "from a helper")
          .inOrder();
      assertThat(lateError).hasCauseThat().isInstanceOf(IllegalStateException.class);
      assertThat(lateError).hasCauseThat().hasMessageThat().contains("a tool or callback returns");
    });
  }

  @Test
  public void emit_duplicateIdThenRunDisposedWhileWaiting_throwIllegalArgumentThenCancellation()
      throws Exception {
    onEveryScheduler(scheduler -> {
      // Arrange: the store never finishes a node event, so emit waits until the cancel.
      SlowStore store = new SlowStore(scheduler, Duration.ofHours(1));
      ExecutorService helper = Executors.newSingleThreadExecutor();
      CompletableFuture<Future<?>> duplicate = new CompletableFuture<>();
      CompletableFuture<Exception> emitFailure = new CompletableFuture<>();
      FunctionNode<Content, Object> stuck =
          node(
              "stuck",
              (ctx, message) -> {
                duplicate.complete(
                    helper.submit(() -> {
                      assertThat(store.appendsStarted.tryAcquire(10, SECONDS)).isTrue();
                      ctx.emit(Event.builder().id("x").content(text("again")).build());
                      return null;
                    }));
                try {
                  ctx.emit(Event.builder().id("x").content(text("stuck")).build());
                } catch (CancellationException e) {
                  // The waiting call reported the cancel, so this one sends nothing and returns.
                  ctx.emit(text("after the cancel"));
                  emitFailure.complete(e);
                }
                return "dropped";
              });
      Runner runner = runner(chain(stuck), scheduler, store);
      Session session = runner.sessionService().createSession(APP, USER).blockingGet();
      try {
        Disposable run = runner.runAsync(USER, session.id(), text("go")).subscribe();
        ExecutionException duplicateError =
            assertThrows(
                ExecutionException.class, () -> duplicate.get(10, SECONDS).get(10, SECONDS));

        run.dispose();

        assertThat(duplicateError).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
        assertThat(emitFailure.get(10, SECONDS)).isInstanceOf(CancellationException.class);
      } finally {
        helper.shutdownNow();
      }
    });
  }

  @Test
  public void emit_runDisposedWhileTheBodyWorks_nextCallThrowsAndTheOneAfterReturns()
      throws Exception {
    // Arrange: the body works between calls and learns of the cancel only from its interrupt.
    CountDownLatch working = new CountDownLatch(1);
    CompletableFuture<Exception> nextEmitFailure = new CompletableFuture<>();
    FunctionNode<Content, Object> importer =
        node(
            "importer",
            (ctx, message) -> {
              working.countDown();
              try {
                new CountDownLatch(1).await(10, SECONDS);
              } catch (InterruptedException e) {
                // The cancel reaches the body before the interrupt does.
              }
              try {
                ctx.emit(text("batch 1"));
              } catch (CancellationException e) {
                ctx.emit(text("batch 2"));
                nextEmitFailure.complete(e);
              }
              return "dropped";
            });
    Runner runner = runner(chain(importer), null);
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    Disposable run = runner.runAsync(USER, session.id(), text("go")).subscribe();
    assertThat(working.await(10, SECONDS)).isTrue();

    run.dispose();

    assertThat(nextEmitFailure.get(10, SECONDS)).isInstanceOf(CancellationException.class);
  }

  @Test
  public void emit_partialOrOutputEvent_partialIsNotStoredNorReleasesALaterEventAndOutputFails()
      throws Exception {
    // Arrange: a helper's event keeps the run busy, so the partial event waits in line.
    SlowStore store = new SlowStore(Schedulers.io(), SLOW_APPEND);
    ExecutorService helper = Executors.newSingleThreadExecutor();
    AtomicReference<ImmutableList<String>> storedWhenEmitReturned = new AtomicReference<>();
    AtomicReference<IllegalArgumentException> outputError = new AtomicReference<>();
    FunctionNode<Content, Object> typist =
        node(
            "typist",
            (ctx, message) -> {
              Future<?> first = helper.submit(() -> ctx.emit(text("first")));
              assertThat(store.appendsStarted.tryAcquire(10, SECONDS)).isTrue();
              ctx.state().put("k", 1);
              outputError.set(
                  assertThrows(
                      IllegalArgumentException.class,
                      () -> ctx.emit(Event.builder().output("too soon").build())));
              ctx.emit(Event.builder().id("x").partial(true).content(text("typing")).build());
              ctx.emit(Event.builder().id("x").content(text("typed")).build());
              storedWhenEmitReturned.set(ImmutableList.copyOf(store.storedTexts));
              first.get(10, SECONDS);
              return "done";
            });

    RunResult result;
    try {
      result = run(chain(typist), Schedulers.io(), store);
    } finally {
      helper.shutdownNow();
    }

    result.assertCompleted();
    ImmutableList<Event> events = result.eventsAt("wf@1/typist@1");
    assertThat(events.stream().map(e -> describe(e) + e.actions().stateDelta()))
        .containsExactly("first{}", "typing{}", "typed{k=1}", "done{}")
        .inOrder();
    assertThat(storedWhenEmitReturned.get()).containsExactly("first", "typed").inOrder();
    assertThat(outputError.get()).hasMessageThat().contains("return the value from the body");
  }

  @Test
  public void emit_fromAFlowableBodyOrOutsideANode_throwsNamingTheAlternative() {
    FlowableNodeBody<Content> rx = (ctx, message) -> Flowable.fromAction(() -> ctx.emit(text("x")));
    ToolContext tool =
        ToolContext.builder(TestUtils.createInvocationContext(TestUtils.createRootAgent())).build();

    RunResult result = run(chain(FunctionNodes.ofFlowable("rx", Content.class, String.class, rx)));
    var toolError = assertThrows(IllegalStateException.class, () -> tool.emit(text("x")));

    assertThat(result.errorMessage("IllegalStateException")).contains("sends events as items");
    assertThat(toolError).hasMessageThat().contains("a tool or callback returns its result");
  }

  @Test
  public void toBuilder_bodyOfTheOtherKind_replacesTheBodyInLaterCopies() {
    FunctionNode<Object, String> plain =
        FunctionNodes.of("plain", Object.class, String.class, (ctx, input) -> "plain");
    FunctionNode<Object, String> rx =
        plain.toBuilder().name("rx").flowableBody((ctx, input) -> Flowable.just("rx")).build();
    FunctionNode<Object, String> back =
        rx.toBuilder().name("back").body((ctx, input) -> "back").build();

    RunResult result = run(chain(rx.toBuilder().name("recopy").build(), back));

    result.assertCompleted();
    assertThat(result.outputAt("wf@1/recopy@1")).isEqualTo("rx");
    assertThat(result.outputAt("wf@1/back@1")).isEqualTo("back");
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
            "node 'ask' returned a RequestInput, which a NodeBody cannot send; emit it from a"
                + " FlowableNodeBody instead.");
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

  /** Returns a node named {@code reply} that returns a Content saying "hi". */
  private static FunctionNode<Content, Content> reply() {
    return FunctionNodes.of(
        "reply", Content.class, Content.class, (ctx, m) -> Content.fromParts(Part.fromText("hi")));
  }

  /** Returns a node named {@code full} that returns a {@link FullOrder} with a note. */
  private static FunctionNode<Content, FullOrder> fullOrder() {
    return FunctionNodes.of(
        "full", Content.class, FullOrder.class, (ctx, m) -> new FullOrder("a1", 9.5, "rush"));
  }

  private static FunctionNode<Content, List<FullOrder>> fullOrders() {
    return FunctionNodes.of(
        "fulls",
        Content.class,
        new TypeReference<List<FullOrder>>() {},
        (ctx, m) -> ImmutableList.of(new FullOrder("a1", 9.5, "rush")));
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

  /** Returns a flowable node that sends {@code event}. */
  private static <O> FunctionNode<Content, O> emitting(String name, Class<O> type, Event event) {
    return FunctionNodes.ofFlowable(
        name, Content.class, type, (ctx, message) -> Flowable.just(event));
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

  private static RunResult run(Node root, @Nullable Scheduler scheduler) {
    return run(root, scheduler, new InMemorySessionService());
  }

  /** Runs {@code root} as an app's root node through a {@link Runner} on a "go" message. */
  private static RunResult run(
      Node root, @Nullable Scheduler scheduler, BaseSessionService sessionService) {
    Runner runner = runner(root, scheduler, sessionService);
    Session session = runner.sessionService().createSession(APP, USER).blockingGet();
    List<Event> events = new ArrayList<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    runner
        .runAsync(USER, session.id(), Content.fromParts(Part.fromText("go")))
        .timeout(30, SECONDS)
        .blockingSubscribe(events::add, error::set);
    return new RunResult(ImmutableList.copyOf(events), error.get());
  }

  private static Runner runner(Node root, @Nullable Scheduler scheduler) {
    return runner(root, scheduler, new InMemorySessionService());
  }

  private static Runner runner(
      Node root, @Nullable Scheduler scheduler, BaseSessionService sessionService) {
    return Runner.builder()
        .app(App.builder().name(APP).rootNode(root).build())
        .sessionService(sessionService)
        .scheduler(scheduler)
        .build();
  }

  /** Runs {@code check} on its own thread, for a bounded time, with each Runner scheduler. */
  private static void onEveryScheduler(SchedulerCheck check) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    ExecutorService checks = Executors.newCachedThreadPool();
    try {
      for (Scheduler scheduler :
          ImmutableList.of(
              Schedulers.io(),
              Schedulers.trampoline(),
              Schedulers.single(),
              Schedulers.from(pool),
              Schedulers.from(Runnable::run))) {
        Future<?> done =
            checks.submit(() -> {
              check.run(scheduler);
              return null;
            });
        try {
          done.get(20, SECONDS);
        } catch (ExecutionException | TimeoutException e) {
          throw new AssertionError("on " + scheduler + ": " + e.getMessage(), e);
        }
      }
    } finally {
      checks.shutdownNow();
      pool.shutdownNow();
    }
  }

  private interface SchedulerCheck {
    void run(Scheduler scheduler) throws Exception;
  }

  /** Returns a plain node named {@code name} that runs {@code body} on the user's message. */
  private static FunctionNode<Content, Object> node(String name, NodeBody<Content, Object> body) {
    return FunctionNodes.of(name, Content.class, Object.class, body);
  }

  private static Content text(String text) {
    return Content.fromParts(Part.fromText(text));
  }

  /** Returns the event's text or output, whichever it carries. */
  private static String describe(Event event) {
    return event.content().map(Content::text).orElse("") + event.output().orElse("");
  }

  /** A session service that stores each node event {@code delay} late, on {@code appendOn}. */
  private static final class SlowStore implements BaseSessionService {
    private final InMemorySessionService delegate = new InMemorySessionService();
    private final Scheduler appendOn;
    private final Duration delay;
    final List<String> storedTexts = new CopyOnWriteArrayList<>();
    final Semaphore appendsStarted = new Semaphore(0);

    SlowStore(Scheduler appendOn, Duration delay) {
      this.appendOn = appendOn;
      this.delay = delay;
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
      if (event.nodeInfo().isEmpty()) {
        return delegate.appendEvent(session, event);
      }
      appendsStarted.release();
      return Single.timer(delay.toMillis(), MILLISECONDS, appendOn)
          .flatMap(unused -> delegate.appendEvent(session, event))
          .doOnSuccess(unused -> storedTexts.add(describe(event)));
    }

    // BaseSessionService's only abstract createSession overload is deprecated.
    @SuppressWarnings("deprecation")
    @Override
    public Single<Session> createSession(
        String appName,
        String userId,
        @Nullable ConcurrentMap<String, Object> state,
        @Nullable String sessionId) {
      return delegate.createSession(appName, userId, state, sessionId);
    }

    @Override
    public Maybe<Session> getSession(
        String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
      return delegate.getSession(appName, userId, sessionId, config);
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
      return delegate.listSessions(appName, userId);
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
      return delegate.deleteSession(appName, userId, sessionId);
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
      return delegate.listEvents(appName, userId, sessionId);
    }
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
