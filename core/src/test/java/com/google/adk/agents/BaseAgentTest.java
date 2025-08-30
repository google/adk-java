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
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    AtomicBoolean runAsyncImplCalled = new AtomicBoolean(false);
    AtomicBoolean afterAgentCallbackCalled = new AtomicBoolean(false);
    Content callbackContent = Content.fromParts(Part.fromText("before_callback_output"));
    Callbacks.BeforeAgentCallback beforeCallback = (callbackContext) -> Maybe.just(callbackContent);
    Callbacks.AfterAgentCallback afterCallback =
        (callbackContext) -> {
          afterAgentCallbackCalled.set(true);
          return Maybe.empty();
        };
    TestBaseAgent agent =
        new TestBaseAgent(
            TEST_AGENT_NAME,
            TEST_AGENT_DESCRIPTION,
            ImmutableList.of(beforeCallback),
            ImmutableList.of(afterCallback),
            () ->
                Flowable.defer(
                    () -> {
                      runAsyncImplCalled.set(true);
                      return Flowable.just(
                          Event.builder()
                              .content(Content.fromParts(Part.fromText("main_output")))
                              .build());
                    }));
    InvocationContext invocationContext = TestUtils.createInvocationContext(agent);

    List<Event> results = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(results).hasSize(1);
    assertThat(results.get(0).content()).hasValue(callbackContent);
    assertThat(runAsyncImplCalled.get()).isFalse();
    assertThat(afterAgentCallbackCalled.get()).isFalse();
  }
}
