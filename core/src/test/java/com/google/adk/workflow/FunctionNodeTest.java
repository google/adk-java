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
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.OptBoolean;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
  public void builder_withoutABody_failsAtBuild() {
    FunctionNode.Builder<String, String> builder =
        FunctionNodes.builder(String.class, String.class).name("empty");

    IllegalStateException error = assertThrows(IllegalStateException.class, builder::build);

    assertThat(error).hasMessageThat().contains("requires body");
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
