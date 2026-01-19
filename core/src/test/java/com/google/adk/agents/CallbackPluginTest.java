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
package com.google.adk.agents;

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.Callbacks.AfterAgentCallbackSync;
import com.google.adk.agents.Callbacks.AfterModelCallback;
import com.google.adk.agents.Callbacks.AfterModelCallbackSync;
import com.google.adk.agents.Callbacks.AfterToolCallback;
import com.google.adk.agents.Callbacks.AfterToolCallbackSync;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackSync;
import com.google.adk.agents.Callbacks.BeforeModelCallback;
import com.google.adk.agents.Callbacks.BeforeModelCallbackSync;
import com.google.adk.agents.Callbacks.BeforeToolCallback;
import com.google.adk.agents.Callbacks.BeforeToolCallbackSync;
import com.google.adk.events.EventActions;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestCallback;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class CallbackPluginTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock private BaseAgent agent;
  @Mock private BaseTool tool;
  @Mock private ToolContext toolContext;
  private InvocationContext invocationContext;
  private CallbackContext callbackContext;

  @Before
  public void setUp() {
    invocationContext = createInvocationContext(agent);
    callbackContext =
        new CallbackContext(
            invocationContext,
            EventActions.builder().stateDelta(new ConcurrentHashMap<>()).build());
  }

  @Test
  public void build_empty_successful() {
    CallbackPlugin plugin = CallbackPlugin.builder().build();
    assertThat(plugin.getName()).isEqualTo("CallbackPlugin");
    assertThat(plugin.getBeforeAgentCallback()).isEmpty();
    assertThat(plugin.getAfterAgentCallback()).isEmpty();
    assertThat(plugin.getBeforeModelCallback()).isEmpty();
    assertThat(plugin.getAfterModelCallback()).isEmpty();
    assertThat(plugin.getBeforeToolCallback()).isEmpty();
    assertThat(plugin.getAfterToolCallback()).isEmpty();
  }

  @Test
  public void addBeforeAgentCallback_isReturnedAndInvoked() {
    Content expectedContent = Content.fromParts(Part.fromText("test"));
    var testCallback = TestCallback.returning(expectedContent);
    BeforeAgentCallback callback = testCallback.asBeforeAgentCallback();

    CallbackPlugin plugin = CallbackPlugin.builder().addBeforeAgentCallback(callback).build();

    assertThat(plugin.getBeforeAgentCallback()).containsExactly(callback);

    Content result = plugin.beforeAgentCallback(agent, callbackContext).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedContent);
  }

  @Test
  public void addBeforeAgentCallbackSync_isReturnedAndInvoked() {
    Content expectedContent = Content.fromParts(Part.fromText("test"));
    var testCallback = TestCallback.returning(expectedContent);
    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addBeforeAgentCallbackSync(testCallback.asBeforeAgentCallbackSync())
            .build();

    assertThat(plugin.getBeforeAgentCallback()).hasSize(1);

    Content result = plugin.beforeAgentCallback(agent, callbackContext).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedContent);
  }

  @Test
  public void addAfterAgentCallback_isReturnedAndInvoked() {
    Content expectedContent = Content.fromParts(Part.fromText("test"));
    var testCallback = TestCallback.returning(expectedContent);
    AfterAgentCallback callback = testCallback.asAfterAgentCallback();

    CallbackPlugin plugin = CallbackPlugin.builder().addAfterAgentCallback(callback).build();

    assertThat(plugin.getAfterAgentCallback()).containsExactly(callback);

    Content result = plugin.afterAgentCallback(agent, callbackContext).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedContent);
  }

  @Test
  public void addAfterAgentCallbackSync_isReturnedAndInvoked() {
    Content expectedContent = Content.fromParts(Part.fromText("test"));
    var testCallback = TestCallback.returning(expectedContent);
    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addAfterAgentCallbackSync(testCallback.asAfterAgentCallbackSync())
            .build();

    assertThat(plugin.getAfterAgentCallback()).hasSize(1);

    Content result = plugin.afterAgentCallback(agent, callbackContext).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedContent);
  }

  @Test
  public void addBeforeModelCallback_isReturnedAndInvoked() {
    LlmResponse expectedResponse = LlmResponse.builder().build();
    var testCallback = TestCallback.returning(expectedResponse);
    BeforeModelCallback callback = testCallback.asBeforeModelCallback();

    CallbackPlugin plugin = CallbackPlugin.builder().addBeforeModelCallback(callback).build();

    assertThat(plugin.getBeforeModelCallback()).containsExactly(callback);

    LlmResponse result =
        plugin.beforeModelCallback(callbackContext, LlmRequest.builder()).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResponse);
  }

  @Test
  public void addBeforeModelCallbackSync_isReturnedAndInvoked() {
    LlmResponse expectedResponse = LlmResponse.builder().build();
    var testCallback = TestCallback.returning(expectedResponse);
    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addBeforeModelCallbackSync(testCallback.asBeforeModelCallbackSync())
            .build();

    assertThat(plugin.getBeforeModelCallback()).hasSize(1);

    LlmResponse result =
        plugin.beforeModelCallback(callbackContext, LlmRequest.builder()).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResponse);
  }

  @Test
  public void addAfterModelCallback_isReturnedAndInvoked() {
    LlmResponse initialResponse = LlmResponse.builder().build();
    LlmResponse expectedResponse =
        LlmResponse.builder().content(Content.fromParts(Part.fromText("test"))).build();
    var testCallback = TestCallback.returning(expectedResponse);
    AfterModelCallback callback = testCallback.asAfterModelCallback();

    CallbackPlugin plugin = CallbackPlugin.builder().addAfterModelCallback(callback).build();

    assertThat(plugin.getAfterModelCallback()).containsExactly(callback);

    LlmResponse result = plugin.afterModelCallback(callbackContext, initialResponse).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResponse);
  }

  @Test
  public void addAfterModelCallbackSync_isReturnedAndInvoked() {
    LlmResponse initialResponse = LlmResponse.builder().build();
    LlmResponse expectedResponse =
        LlmResponse.builder().content(Content.fromParts(Part.fromText("test"))).build();
    var testCallback = TestCallback.returning(expectedResponse);
    AfterModelCallbackSync callback = testCallback.asAfterModelCallbackSync();

    CallbackPlugin plugin = CallbackPlugin.builder().addAfterModelCallbackSync(callback).build();

    assertThat(plugin.getAfterModelCallback()).hasSize(1);

    LlmResponse result = plugin.afterModelCallback(callbackContext, initialResponse).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResponse);
  }

  @Test
  public void addBeforeToolCallback_isReturnedAndInvoked() {
    ImmutableMap<String, Object> expectedResult = ImmutableMap.of("key", "value");
    var testCallback = TestCallback.returning(expectedResult);
    BeforeToolCallback callback = testCallback.asBeforeToolCallback();

    CallbackPlugin plugin = CallbackPlugin.builder().addBeforeToolCallback(callback).build();

    assertThat(plugin.getBeforeToolCallback()).containsExactly(callback);

    Map<String, Object> result =
        plugin.beforeToolCallback(tool, ImmutableMap.of(), toolContext).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  public void addBeforeToolCallbackSync_isReturnedAndInvoked() {
    ImmutableMap<String, Object> expectedResult = ImmutableMap.of("key", "value");
    var testCallback = TestCallback.returning(expectedResult);
    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addBeforeToolCallbackSync(testCallback.asBeforeToolCallbackSync())
            .build();

    assertThat(plugin.getBeforeToolCallback()).hasSize(1);

    Map<String, Object> result =
        plugin.beforeToolCallback(tool, ImmutableMap.of(), toolContext).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  public void addAfterToolCallback_isReturnedAndInvoked() {
    ImmutableMap<String, Object> initialResult = ImmutableMap.of("initial", "result");
    ImmutableMap<String, Object> expectedResult = ImmutableMap.of("key", "value");
    var testCallback = TestCallback.returning(expectedResult);
    AfterToolCallback callback = testCallback.asAfterToolCallback();

    CallbackPlugin plugin = CallbackPlugin.builder().addAfterToolCallback(callback).build();

    assertThat(plugin.getAfterToolCallback()).containsExactly(callback);

    Map<String, Object> result =
        plugin.afterToolCallback(tool, ImmutableMap.of(), toolContext, initialResult).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  public void addAfterToolCallbackSync_isReturnedAndInvoked() {
    ImmutableMap<String, Object> initialResult = ImmutableMap.of("initial", "result");
    ImmutableMap<String, Object> expectedResult = ImmutableMap.of("key", "value");
    var testCallback = TestCallback.returning(expectedResult);
    AfterToolCallbackSync callback = testCallback.asAfterToolCallbackSync();

    CallbackPlugin plugin = CallbackPlugin.builder().addAfterToolCallbackSync(callback).build();

    assertThat(plugin.getAfterToolCallback()).hasSize(1);

    Map<String, Object> result =
        plugin.afterToolCallback(tool, ImmutableMap.of(), toolContext, initialResult).blockingGet();
    assertThat(testCallback.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResult);
  }

  @Test
  public void addCallback_beforeAgentCallback() {
    BeforeAgentCallback callback = ctx -> Maybe.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getBeforeAgentCallback()).containsExactly(callback);
  }

  @Test
  public void addCallback_beforeAgentCallbackSync() {
    BeforeAgentCallbackSync callback = ctx -> Optional.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getBeforeAgentCallback()).hasSize(1);
  }

  @Test
  public void addCallback_afterAgentCallback() {
    AfterAgentCallback callback = ctx -> Maybe.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getAfterAgentCallback()).containsExactly(callback);
  }

  @Test
  public void addCallback_afterAgentCallbackSync() {
    AfterAgentCallbackSync callback = ctx -> Optional.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getAfterAgentCallback()).hasSize(1);
  }

  @Test
  public void addCallback_beforeModelCallback() {
    BeforeModelCallback callback = (ctx, req) -> Maybe.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getBeforeModelCallback()).containsExactly(callback);
  }

  @Test
  public void addCallback_beforeModelCallbackSync() {
    BeforeModelCallbackSync callback = (ctx, req) -> Optional.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getBeforeModelCallback()).hasSize(1);
  }

  @Test
  public void addCallback_afterModelCallback() {
    AfterModelCallback callback = (ctx, res) -> Maybe.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getAfterModelCallback()).containsExactly(callback);
  }

  @Test
  public void addCallback_afterModelCallbackSync() {
    AfterModelCallbackSync callback = (ctx, res) -> Optional.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getAfterModelCallback()).hasSize(1);
  }

  @Test
  public void addCallback_beforeToolCallback() {
    BeforeToolCallback callback = (invCtx, tool, toolArgs, toolCtx) -> Maybe.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getBeforeToolCallback()).containsExactly(callback);
  }

  @Test
  public void addCallback_beforeToolCallbackSync() {
    BeforeToolCallbackSync callback = (invCtx, tool, toolArgs, toolCtx) -> Optional.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getBeforeToolCallback()).hasSize(1);
  }

  @Test
  public void addCallback_afterToolCallback() {
    AfterToolCallback callback = (invCtx, tool, toolArgs, toolCtx, res) -> Maybe.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getAfterToolCallback()).containsExactly(callback);
  }

  @Test
  public void addCallback_afterToolCallbackSync() {
    AfterToolCallbackSync callback = (invCtx, tool, toolArgs, toolCtx, res) -> Optional.empty();
    CallbackPlugin plugin = CallbackPlugin.builder().addCallback(callback).build();
    assertThat(plugin.getAfterToolCallback()).hasSize(1);
  }

  @Test
  public void addMultipleBeforeModelCallbacks_invokedInOrder() {
    LlmResponse expectedResponse = LlmResponse.builder().build();
    var testCallback1 = TestCallback.<LlmResponse>returningEmpty();
    var testCallback2 = TestCallback.returning(expectedResponse);
    BeforeModelCallback callback1 = testCallback1.asBeforeModelCallback();
    BeforeModelCallback callback2 = testCallback2.asBeforeModelCallback();

    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addBeforeModelCallback(callback1)
            .addBeforeModelCallback(callback2)
            .build();

    assertThat(plugin.getBeforeModelCallback()).containsExactly(callback1, callback2).inOrder();

    LlmResponse result =
        plugin.beforeModelCallback(callbackContext, LlmRequest.builder()).blockingGet();
    assertThat(testCallback1.wasCalled()).isTrue();
    assertThat(testCallback2.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResponse);
  }

  @Test
  public void addMultipleBeforeModelCallbacks_shortCircuit() {
    LlmResponse expectedResponse = LlmResponse.builder().build();
    var testCallback1 = TestCallback.returning(expectedResponse);
    var testCallback2 = TestCallback.<LlmResponse>returningEmpty();
    BeforeModelCallback callback1 = testCallback1.asBeforeModelCallback();
    BeforeModelCallback callback2 = testCallback2.asBeforeModelCallback();

    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addBeforeModelCallback(callback1)
            .addBeforeModelCallback(callback2)
            .build();

    assertThat(plugin.getBeforeModelCallback()).containsExactly(callback1, callback2).inOrder();

    LlmResponse result =
        plugin.beforeModelCallback(callbackContext, LlmRequest.builder()).blockingGet();
    assertThat(testCallback1.wasCalled()).isTrue();
    assertThat(testCallback2.wasCalled()).isFalse();
    assertThat(result).isEqualTo(expectedResponse);
  }

  @Test
  public void addMultipleAfterModelCallbacks_shortCircuit() {
    LlmResponse initialResponse = LlmResponse.builder().build();
    LlmResponse expectedResponse =
        LlmResponse.builder().content(Content.fromParts(Part.fromText("response"))).build();
    var testCallback1 = TestCallback.returning(expectedResponse);
    var testCallback2 = TestCallback.<LlmResponse>returningEmpty();
    AfterModelCallback callback1 = testCallback1.asAfterModelCallback();
    AfterModelCallback callback2 = testCallback2.asAfterModelCallback();
    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addAfterModelCallback(callback1)
            .addAfterModelCallback(callback2)
            .build();

    assertThat(plugin.getAfterModelCallback()).containsExactly(callback1, callback2).inOrder();
    LlmResponse result = plugin.afterModelCallback(callbackContext, initialResponse).blockingGet();
    assertThat(testCallback1.wasCalled()).isTrue();
    assertThat(testCallback2.wasCalled()).isFalse();
    assertThat(result).isEqualTo(expectedResponse);
  }

  @Test
  public void addMultipleAfterModelCallbacks_invokedInOrder() {
    LlmResponse initialResponse = LlmResponse.builder().build();
    LlmResponse expectedResponse =
        LlmResponse.builder().content(Content.fromParts(Part.fromText("second"))).build();
    var testCallback1 = TestCallback.<LlmResponse>returningEmpty();
    var testCallback2 = TestCallback.returning(expectedResponse);
    AfterModelCallback callback1 = testCallback1.asAfterModelCallback();
    AfterModelCallback callback2 = testCallback2.asAfterModelCallback();

    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addAfterModelCallback(callback1)
            .addAfterModelCallback(callback2)
            .build();

    assertThat(plugin.getAfterModelCallback()).containsExactly(callback1, callback2).inOrder();

    LlmResponse result = plugin.afterModelCallback(callbackContext, initialResponse).blockingGet();
    assertThat(testCallback1.wasCalled()).isTrue();
    assertThat(testCallback2.wasCalled()).isTrue();
    assertThat(result).isEqualTo(expectedResponse);
  }

  @Test
  public void addMultipleBeforeModelCallbacks_bothEmpty_returnsEmpty() {
    var testCallback1 = TestCallback.<LlmResponse>returningEmpty();
    var testCallback2 = TestCallback.<LlmResponse>returningEmpty();
    BeforeModelCallback callback1 = testCallback1.asBeforeModelCallback();
    BeforeModelCallback callback2 = testCallback2.asBeforeModelCallback();

    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addBeforeModelCallback(callback1)
            .addBeforeModelCallback(callback2)
            .build();

    assertThat(plugin.getBeforeModelCallback()).containsExactly(callback1, callback2).inOrder();

    LlmResponse result =
        plugin.beforeModelCallback(callbackContext, LlmRequest.builder()).blockingGet();
    assertThat(testCallback1.wasCalled()).isTrue();
    assertThat(testCallback2.wasCalled()).isTrue();
    assertThat(result).isNull();
  }

  @Test
  public void addMultipleAfterModelCallbacks_bothEmpty_returnsEmpty() {
    LlmResponse initialResponse = LlmResponse.builder().build();
    var testCallback1 = TestCallback.<LlmResponse>returningEmpty();
    var testCallback2 = TestCallback.<LlmResponse>returningEmpty();
    AfterModelCallback callback1 = testCallback1.asAfterModelCallback();
    AfterModelCallback callback2 = testCallback2.asAfterModelCallback();
    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addAfterModelCallback(callback1)
            .addAfterModelCallback(callback2)
            .build();

    assertThat(plugin.getAfterModelCallback()).containsExactly(callback1, callback2).inOrder();
    LlmResponse result = plugin.afterModelCallback(callbackContext, initialResponse).blockingGet();
    assertThat(testCallback1.wasCalled()).isTrue();
    assertThat(testCallback2.wasCalled()).isTrue();
    assertThat(result).isNull();
  }

  @Test
  public void addBeforeModelCallback_isIdempotent() {
    LlmResponse expectedResponse = LlmResponse.builder().build();
    var testCallback = TestCallback.returning(expectedResponse);
    BeforeModelCallback callback = testCallback.asBeforeModelCallback();
    CallbackPlugin plugin =
        CallbackPlugin.builder()
            .addBeforeModelCallback(callback)
            .addBeforeModelCallback(callback)
            .build();
    assertThat(plugin.getBeforeModelCallback()).containsExactly(callback);
  }
}
