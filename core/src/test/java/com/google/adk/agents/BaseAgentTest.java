/*
 * Copyright 2025 Google LLC
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

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.events.Event;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.testing.TestCallback;
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BaseAgentTest {

  private static final String TEST_AGENT_NAME = "testAgent";
  private static final String TEST_AGENT_DESCRIPTION = "A test agent";

  @Test
  public void constructor_setsNameAndDescription() {
    String name = "testName";
    String description = "testDescription";
    TestBaseAgent agent =
        new TestBaseAgent(name, description, null, ImmutableList.of(), null, null);

    assertThat(agent.name()).isEqualTo(name);
    assertThat(agent.description()).isEqualTo(description);
  }

  @Test
  public void findAgent_returnsCorrectAgent() {
    TestBaseAgent subSubAgent =
        new TestBaseAgent("subSubAgent", "subSubAgent", null, ImmutableList.of(), null, null);
    TestBaseAgent subAgent =
        new TestBaseAgent("subAgent", "subAgent", null, ImmutableList.of(subSubAgent), null, null);
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME, TEST_AGENT_DESCRIPTION, null, ImmutableList.of(subAgent), null, null);
    assertThat(agent.findAgent("subSubAgent")).isEqualTo(subSubAgent);
    assertThat(agent.findAgent("subAgent")).isEqualTo(subAgent);
    assertThat(agent.findAgent(TEST_AGENT_NAME)).isEqualTo(agent);
    assertThat(agent.findAgent("nonExistent")).isNull();
  }

  @Test
  public void rootAgent_returnsRootAgent() {
    TestBaseAgent subSubAgent =
        new TestBaseAgent("subSubAgent", "subSubAgent", null, ImmutableList.of(), null, null);
    TestBaseAgent subAgent =
        new TestBaseAgent("subAgent", "subAgent", null, ImmutableList.of(subSubAgent), null, null);
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME, TEST_AGENT_DESCRIPTION, null, ImmutableList.of(subAgent), null, null);
    assertThat(subSubAgent.rootAgent()).isEqualTo(agent);
    assertThat(subAgent.rootAgent()).isEqualTo(agent);
    assertThat(agent.rootAgent()).isEqualTo(agent);
  }

  @Test
  public void
      runAsync_beforeAgentCallbackReturnsContent_endsInvocationAndSkipsRunAsyncImplAndAfterCallback() {
    var runAsyncImpl = TestCallback.<Void>returningEmpty();
    Content callbackContent = Content.fromParts(Part.fromText("before_callback_output"));
    var beforeCallback = TestCallback.returning(callbackContent);
    var afterCallback = TestCallback.<Content>returningEmpty();
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            ImmutableList.of(beforeCallback.asBeforeAgentCallback()),
            ImmutableList.of(afterCallback.asAfterAgentCallback()),
            runAsyncImpl.asRunAsyncImplSupplier("main_output"));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);

    List<Event> results = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(results).hasSize(1);
    assertThat(results.get(0).content()).hasValue(callbackContent);
    assertThat(runAsyncImpl.wasCalled()).isFalse();
    assertThat(beforeCallback.wasCalled()).isTrue();
    assertThat(afterCallback.wasCalled()).isFalse();
  }

  @Test
  public void runAsync_firstBeforeCallbackReturnsContent_skipsSecondBeforeCallback() {
    Content callbackContent = Content.fromParts(Part.fromText("before_callback_output"));
    var beforeCallback1 = TestCallback.returning(callbackContent);
    var beforeCallback2 = TestCallback.<Content>returningEmpty();
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            ImmutableList.of(
                beforeCallback1.asBeforeAgentCallback(), beforeCallback2.asBeforeAgentCallback()),
            ImmutableList.of(),
            TestCallback.<Void>returningEmpty().asRunAsyncImplSupplier("main_output"));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);
    var unused = agent.runAsync(invocationContext).toList().blockingGet();
    assertThat(beforeCallback1.wasCalled()).isTrue();
    assertThat(beforeCallback2.wasCalled()).isFalse();
  }

  @Test
  public void runAsync_noCallbacks_invokesRunAsyncImpl() {
    var runAsyncImpl = TestCallback.<Void>returningEmpty();
    Content runAsyncImplContent = Content.fromParts(Part.fromText("main_output"));
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            /* beforeAgentCallbacks= */ ImmutableList.of(),
            /* afterAgentCallbacks= */ ImmutableList.of(),
            runAsyncImpl.asRunAsyncImplSupplier(runAsyncImplContent));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);

    List<Event> results = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(results).hasSize(1);
    assertThat(results.get(0).content()).hasValue(runAsyncImplContent);
    assertThat(runAsyncImpl.wasCalled()).isTrue();
  }

  @Test
  public void
      runAsync_beforeCallbackReturnsEmptyAndAfterCallbackReturnsEmpty_invokesRunAsyncImplAndAfterCallbacks() {
    var runAsyncImpl = TestCallback.<Void>returningEmpty();
    Content runAsyncImplContent = Content.fromParts(Part.fromText("main_output"));
    var beforeCallback = TestCallback.<Content>returningEmpty();
    var afterCallback = TestCallback.<Content>returningEmpty();
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            ImmutableList.of(beforeCallback.asBeforeAgentCallback()),
            ImmutableList.of(afterCallback.asAfterAgentCallback()),
            runAsyncImpl.asRunAsyncImplSupplier(runAsyncImplContent));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);

    List<Event> results = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(results).hasSize(1);
    assertThat(results.get(0).content()).hasValue(runAsyncImplContent);
    assertThat(runAsyncImpl.wasCalled()).isTrue();
    assertThat(beforeCallback.wasCalled()).isTrue();
    assertThat(afterCallback.wasCalled()).isTrue();
  }

  @Test
  public void
      runAsync_afterCallbackReturnsContent_invokesRunAsyncImplAndAfterCallbacksAndReturnsAllContent() {
    var runAsyncImpl = TestCallback.<Void>returningEmpty();
    Content runAsyncImplContent = Content.fromParts(Part.fromText("main_output"));
    Content afterCallbackContent = Content.fromParts(Part.fromText("after_callback_output"));
    var beforeCallback = TestCallback.<Content>returningEmpty();
    var afterCallback = TestCallback.returning(afterCallbackContent);
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            ImmutableList.of(beforeCallback.asBeforeAgentCallback()),
            ImmutableList.of(afterCallback.asAfterAgentCallback()),
            runAsyncImpl.asRunAsyncImplSupplier(runAsyncImplContent));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);

    List<Event> results = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(results).hasSize(2);
    assertThat(results.get(0).content()).hasValue(runAsyncImplContent);
    assertThat(results.get(1).content()).hasValue(afterCallbackContent);
    assertThat(runAsyncImpl.wasCalled()).isTrue();
    assertThat(beforeCallback.wasCalled()).isTrue();
    assertThat(afterCallback.wasCalled()).isTrue();
  }

  @Test
  public void
      runAsync_beforeCallbackMutatesStateAndReturnsEmpty_invokesRunAsyncImplAndReturnsStateEvent() {
    var runAsyncImpl = TestCallback.<Void>returningEmpty();
    Content runAsyncImplContent = Content.fromParts(Part.fromText("main_output"));
    BeforeAgentCallback beforeCallback =
        new BeforeAgentCallback() {
          @Override
          public Maybe<Content> call(CallbackContext context) {
            context.state().put("key", "value");
            return Maybe.empty();
          }
        };
    var afterCallback = TestCallback.<Content>returningEmpty();
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            ImmutableList.of(beforeCallback),
            ImmutableList.of(afterCallback.asAfterAgentCallback()),
            runAsyncImpl.asRunAsyncImplSupplier(runAsyncImplContent));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);

    List<Event> results = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(results).hasSize(2);
    // State event from before callback
    assertThat(results.get(0).content()).isEmpty();
    assertThat(results.get(0).actions().stateDelta()).containsEntry("key", "value");
    // Content event from runAsyncImpl
    assertThat(results.get(1).content()).hasValue(runAsyncImplContent);
    assertThat(runAsyncImpl.wasCalled()).isTrue();
    assertThat(afterCallback.wasCalled()).isTrue();
  }

  @Test
  public void
      runAsync_afterCallbackMutatesStateAndReturnsEmpty_invokesRunAsyncImplAndReturnsStateEvent() {
    var runAsyncImpl = TestCallback.<Void>returningEmpty();
    Content runAsyncImplContent = Content.fromParts(Part.fromText("main_output"));
    var beforeCallback = TestCallback.<Content>returningEmpty();
    AfterAgentCallback afterCallback =
        new AfterAgentCallback() {
          @Override
          public Maybe<Content> call(CallbackContext context) {
            context.state().put("key", "value");
            return Maybe.empty();
          }
        };
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            ImmutableList.of(beforeCallback.asBeforeAgentCallback()),
            ImmutableList.of(afterCallback),
            runAsyncImpl.asRunAsyncImplSupplier(runAsyncImplContent));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);

    List<Event> results = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(results).hasSize(2);
    // Content event from runAsyncImpl
    assertThat(results.get(0).content()).hasValue(runAsyncImplContent);
    // State event from after callback
    assertThat(results.get(1).content()).isEmpty();
    assertThat(results.get(1).actions().stateDelta()).containsEntry("key", "value");
    assertThat(runAsyncImpl.wasCalled()).isTrue();
    assertThat(beforeCallback.wasCalled()).isTrue();
  }

  @Test
  public void runAsync_firstAfterCallbackReturnsContent_skipsSecondAfterCallback() {
    var runAsyncImpl = TestCallback.<Void>returningEmpty();
    Content runAsyncImplContent = Content.fromParts(Part.fromText("main_output"));
    Content afterCallbackContent = Content.fromParts(Part.fromText("after_callback_output"));
    var afterCallback1 = TestCallback.returning(afterCallbackContent);
    var afterCallback2 = TestCallback.<Content>returningEmpty();
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            ImmutableList.of(),
            ImmutableList.of(
                afterCallback1.asAfterAgentCallback(), afterCallback2.asAfterAgentCallback()),
            runAsyncImpl.asRunAsyncImplSupplier(runAsyncImplContent));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);

    List<Event> results = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(results).hasSize(2);
    assertThat(results.get(0).content()).hasValue(runAsyncImplContent);
    assertThat(results.get(1).content()).hasValue(afterCallbackContent);
    assertThat(runAsyncImpl.wasCalled()).isTrue();
    assertThat(afterCallback1.wasCalled()).isTrue();
    assertThat(afterCallback2.wasCalled()).isFalse();
  }

  @Test
  public void canonicalCallbacks_returnsEmptyListWhenNull() {
    TestBaseAgent agent =
        new TestBaseAgent(TEST_AGENT_NAME, TEST_AGENT_DESCRIPTION, null, null, null);

    assertThat(agent.canonicalBeforeAgentCallbacks()).isEmpty();
    assertThat(agent.canonicalAfterAgentCallbacks()).isEmpty();
  }

  @Test
  public void canonicalCallbacks_returnsListWhenPresent() {
    BeforeAgentCallback bc = unused -> Maybe.empty();
    AfterAgentCallback ac = unused -> Maybe.empty();
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            ImmutableList.of(bc),
            ImmutableList.of(ac),
            null);

    assertThat(agent.canonicalBeforeAgentCallbacks()).containsExactly(bc);
    assertThat(agent.canonicalAfterAgentCallbacks()).containsExactly(ac);
  }

  @Test
  public void runLive_invokesRunLiveImpl() {
    var runLiveCallback = TestCallback.<Void>returningEmpty();
    Content runLiveImplContent = Content.fromParts(Part.fromText("live_output"));
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            /* beforeAgentCallbacks= */ ImmutableList.of(),
            /* afterAgentCallbacks= */ ImmutableList.of(),
            runLiveCallback.asRunLiveImplSupplier(runLiveImplContent));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);

    List<Event> results = agent.runLive(invocationContext).toList().blockingGet();

    assertThat(results).hasSize(1);
    assertThat(results.get(0).content()).hasValue(runLiveImplContent);
    assertThat(runLiveCallback.wasCalled()).isTrue();
  }
}
