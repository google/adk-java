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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.testing.TestUtils;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BaseNodeTest {

  @Test
  public void constructor_setsEveryProperty() {
    NodeConfig config = NodeConfig.builder().timeout(Duration.ofSeconds(5)).build();
    Schema inputSchema = Schema.builder().description("input").build();
    Schema outputSchema = Schema.builder().description("output").build();
    Schema stateSchema = Schema.builder().description("state").build();

    BaseNode node =
        new TestNode(
            "summarize",
            "Summarizes its input.",
            /* rerunOnResume= */ true,
            /* waitForOutput= */ true,
            config,
            inputSchema,
            outputSchema,
            stateSchema);

    assertThat(node.name()).isEqualTo("summarize");
    assertThat(node.description()).isEqualTo("Summarizes its input.");
    assertThat(node.rerunOnResume()).isTrue();
    assertThat(node.waitForOutput()).isTrue();
    assertThat(node.config()).isEqualTo(config);
    assertThat(node.inputSchema()).hasValue(inputSchema);
    assertThat(node.outputSchema()).hasValue(outputSchema);
    assertThat(node.stateSchema()).hasValue(stateSchema);
  }

  @Test
  public void constructor_withNameAndDescription_leavesTheRestAtDefaults() {
    BaseNode node = new TestNode("summarize", "Summarizes its input.", Flowable.empty());

    assertThat(node.name()).isEqualTo("summarize");
    assertThat(node.description()).isEqualTo("Summarizes its input.");
    assertThat(node.rerunOnResume()).isFalse();
    assertThat(node.waitForOutput()).isFalse();
    assertThat(node.config()).isEqualTo(NodeConfig.builder().build());
    assertThat(node.inputSchema()).isEmpty();
    assertThat(node.outputSchema()).isEmpty();
    assertThat(node.stateSchema()).isEmpty();
  }

  @Test
  public void constructor_rejectsANullName() {
    assertThrows(NullPointerException.class, () -> new TestNode(null, "", Flowable.empty()));
  }

  @Test
  public void constructor_rejectsANullDescription() {
    assertThrows(NullPointerException.class, () -> new TestNode("node", null, Flowable.empty()));
  }

  @Test
  public void constructor_rejectsANullConfig() {
    assertThrows(
        NullPointerException.class,
        () ->
            new TestNode(
                "node",
                "",
                /* rerunOnResume= */ false,
                /* waitForOutput= */ false,
                /* config= */ null,
                /* inputSchema= */ null,
                /* outputSchema= */ null,
                /* stateSchema= */ null));
  }

  @Test
  public void run_passesEventsThrough() {
    Event event = Event.builder().id("event_id").author("agent").build();
    BaseNode node = new TestNode("node", "", Flowable.just(event));

    List<Event> events = node.run(context(), /* nodeInput= */ null).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0)).isSameInstanceAs(event);
  }

  @Test
  public void run_turnsANonEventValueIntoAnOutputEvent() {
    BaseNode node = new TestNode("node", "", Flowable.just("text"));

    Event event = node.run(context(), /* nodeInput= */ null).blockingSingle();

    assertThat(event.output()).hasValue("text");
    assertThat(event.author()).isEmpty();
  }

  @Test
  public void run_takesOutputEventIdAndTimestampFromTheContext() {
    InvocationContext invocationContext =
        invocationContext().toBuilder()
            .uuidProvider(() -> "event_id")
            .instantSource(InstantSource.fixed(Instant.ofEpochMilli(1234)))
            .build();
    BaseNode node = new TestNode("node", "", Flowable.just("text"));

    Event event = node.run(context(invocationContext), /* nodeInput= */ null).blockingSingle();

    assertThat(event.id()).isEqualTo("event_id");
    assertThat(event.timestamp()).isEqualTo(1234);
  }

  @Test
  public void run_emitsNothingWhenRunNodeEmitsNothing() {
    BaseNode node = new TestNode("node", "", Flowable.empty());

    assertThat(node.run(context(), /* nodeInput= */ null).toList().blockingGet()).isEmpty();
  }

  @Test
  public void run_deliversAnExceptionThrownByRunNodeAsAnError() {
    BaseNode node =
        new BaseNode("node", "") {
          @Override
          public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
            throw new IllegalStateException("runNode failed");
          }
        };

    Flowable<Event> events = node.run(context(), /* nodeInput= */ null);

    events.test().assertError(IllegalStateException.class);
  }

  @Test
  public void run_rejectsANullContext() {
    BaseNode node = new TestNode("node", "", Flowable.empty());

    assertThrows(NullPointerException.class, () -> node.run(null, /* nodeInput= */ null));
  }

  private static Context context() {
    return context(invocationContext());
  }

  private static Context context(InvocationContext invocationContext) {
    return new CallbackContext(invocationContext, /* eventActions= */ null);
  }

  private static InvocationContext invocationContext() {
    return TestUtils.createInvocationContext(TestUtils.createRootAgent());
  }

  private static final class TestNode extends BaseNode {
    private final Flowable<?> emissions;

    TestNode(String name, String description, Flowable<?> emissions) {
      super(name, description);
      this.emissions = emissions;
    }

    TestNode(
        String name,
        String description,
        boolean rerunOnResume,
        boolean waitForOutput,
        NodeConfig config,
        @Nullable Schema inputSchema,
        @Nullable Schema outputSchema,
        @Nullable Schema stateSchema) {
      super(
          name,
          description,
          rerunOnResume,
          waitForOutput,
          config,
          inputSchema,
          outputSchema,
          stateSchema);
      this.emissions = Flowable.empty();
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return emissions;
    }
  }
}
