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

import com.google.adk.events.Event;
import com.google.adk.testing.TestBaseAgent;
import com.google.adk.testing.TestCallback;
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
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
    TestBaseAgent agent = new TestBaseAgent(name, description, ImmutableList.of(), null, null);

    assertThat(agent.name()).isEqualTo(name);
    assertThat(agent.description()).isEqualTo(description);
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
}
