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

package com.google.adk.plugins.reflectandretry;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class ReflectAndRetryToolPluginTest {

  private static final String INVOCATION_ID = "invocation-1";
  private static final String OTHER_INVOCATION_ID = "invocation-2";
  private static final String USER_ID = "user-42";
  private static final String TOOL_NAME = "flaky_tool";
  private static final String OTHER_TOOL_NAME = "other_tool";
  private static final ImmutableMap<String, Object> ARGS = ImmutableMap.of("city", "Phoenix");

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private BaseTool mockTool;
  @Mock private BaseTool mockOtherTool;
  @Mock private ToolContext mockToolContext;

  private final RuntimeException error = new IllegalStateException("boom");

  @Before
  public void setUp() {
    when(mockTool.name()).thenReturn(TOOL_NAME);
    when(mockToolContext.invocationId()).thenReturn(INVOCATION_ID);
  }

  @Test
  public void onToolError_withinRetryLimit_returnsReflectionGuidance() {
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(3);

    Map<String, Object> response = callOnError(plugin);

    assertThat(response)
        .containsEntry("response_type", "ERROR_HANDLED_BY_REFLECT_AND_RETRY_PLUGIN");
    assertThat(response).containsEntry("error_type", "IllegalStateException");
    assertThat(response).containsEntry("error_details", "boom");
    assertThat(response).containsEntry("retry_count", 1);
    assertThat((String) response.get("reflection_guidance")).contains("retry attempt **1 of 3**");
  }

  @Test
  public void onToolError_guidanceCarriesToolNameAndArguments() {
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(3);

    String guidance = (String) callOnError(plugin).get("reflection_guidance");

    assertThat(guidance).contains(TOOL_NAME);
    assertThat(guidance).contains("\"city\" : \"Phoenix\"");
    assertThat(guidance).contains("IllegalStateException: boom");
  }

  @Test
  public void onToolError_errorWithoutMessage_reportsEmptyDetails() {
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(3);

    Map<String, Object> response = callOnError(plugin, new IllegalStateException());

    assertThat(response).containsEntry("error_details", "");
    assertThat((String) response.get("reflection_guidance"))
        .contains("```\nIllegalStateException: \n```");
  }

  @Test
  public void onToolError_pastRetryLimit_errorWithoutMessage_reportsEmptyDetails() {
    ReflectAndRetryToolPlugin plugin =
        new ReflectAndRetryToolPlugin("p", 1, false, TrackingScope.INVOCATION);
    IllegalStateException messageless = new IllegalStateException();

    callOnError(plugin, messageless);
    Map<String, Object> exceeded = callOnError(plugin, messageless);

    assertThat(exceeded).containsEntry("error_details", "");
    assertThat((String) exceeded.get("reflection_guidance"))
        .contains("```\nIllegalStateException: \n```");
  }

  @Test
  public void onToolError_countsConsecutiveFailures() {
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(3);

    callOnError(plugin);
    callOnError(plugin);
    Map<String, Object> third = callOnError(plugin);

    assertThat(third).containsEntry("retry_count", 3);
  }

  @Test
  public void onToolError_pastRetryLimit_propagatesOriginalError() {
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(2);

    callOnError(plugin);
    callOnError(plugin);

    plugin.onToolErrorCallback(mockTool, ARGS, mockToolContext, error).test().assertError(error);
  }

  @Test
  public void onToolError_pastRetryLimit_whenNotThrowing_returnsGiveUpMessage() {
    ReflectAndRetryToolPlugin plugin =
        new ReflectAndRetryToolPlugin("p", 1, false, TrackingScope.INVOCATION);

    callOnError(plugin);
    Map<String, Object> exceeded = callOnError(plugin);

    assertThat((String) exceeded.get("reflection_guidance"))
        .contains("Do not attempt to use the `flaky_tool` tool again");
    assertThat(exceeded).containsEntry("retry_count", 2);
    assertThat((String) exceeded.get("reflection_guidance"))
        .contains("has failed consecutively 2 times");
  }

  @Test
  public void maxRetriesZero_propagatesImmediately() {
    ReflectAndRetryToolPlugin plugin =
        new ReflectAndRetryToolPlugin("p", 0, true, TrackingScope.INVOCATION);

    plugin.onToolErrorCallback(mockTool, ARGS, mockToolContext, error).test().assertError(error);
  }

  @Test
  public void maxRetriesZero_whenNotThrowing_returnsGiveUpMessage() {
    ReflectAndRetryToolPlugin plugin =
        new ReflectAndRetryToolPlugin("p", 0, false, TrackingScope.INVOCATION);

    Map<String, Object> exceeded = callOnError(plugin);

    assertThat(exceeded).containsEntry("retry_count", 1);
    assertThat((String) exceeded.get("reflection_guidance"))
        .contains("has failed consecutively 1 times");
  }

  @Test
  public void onToolError_whenArgumentsCannotBeSerialized_fallsBackToMapRendering() {
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(3);
    ImmutableMap<String, Object> unserializable = ImmutableMap.of("payload", new Opaque());

    Map<String, Object> response =
        plugin.onToolErrorCallback(mockTool, unserializable, mockToolContext, error).blockingGet();

    assertThat((String) response.get("reflection_guidance")).contains("{payload=<opaque>}");
  }

  @Test
  public void negativeMaxRetries_isRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReflectAndRetryToolPlugin("p", -1, true, TrackingScope.INVOCATION));
  }

  @Test
  public void nullTrackingScope_isRejectedAtConstruction() {
    assertThrows(
        NullPointerException.class, () -> new ReflectAndRetryToolPlugin("p", 3, true, null));
  }

  @Test
  public void afterTool_onSuccess_resetsThatToolsCounter() {
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(3);
    callOnError(plugin);
    callOnError(plugin);

    plugin
        .afterToolCallback(mockTool, ARGS, mockToolContext, ImmutableMap.of("ok", true))
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void afterTool_successOfOneTool_doesNotForgiveAnother() {
    when(mockOtherTool.name()).thenReturn(OTHER_TOOL_NAME);
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(3);
    callOnError(plugin);

    plugin
        .afterToolCallback(mockOtherTool, ARGS, mockToolContext, ImmutableMap.of("ok", true))
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);
  }

  @Test
  public void afterTool_ownReflectionResponse_isPassedThroughUncounted() {
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(3);
    Map<String, Object> reflection = callOnError(plugin);

    plugin
        .afterToolCallback(mockTool, ARGS, mockToolContext, reflection)
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);
  }

  @Test
  public void invocationScope_countsAreIsolatedPerInvocation() {
    ReflectAndRetryToolPlugin plugin = new ReflectAndRetryToolPlugin(3);
    callOnError(plugin);

    when(mockToolContext.invocationId()).thenReturn(OTHER_INVOCATION_ID);

    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void globalScope_countsSurviveAcrossInvocations() {
    ReflectAndRetryToolPlugin plugin =
        new ReflectAndRetryToolPlugin("p", 3, true, TrackingScope.GLOBAL);
    callOnError(plugin);

    when(mockToolContext.invocationId()).thenReturn(OTHER_INVOCATION_ID);

    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);
  }

  @Test
  public void extractErrorFromResult_whenOverridden_treatsSuccessfulResultAsFailure() {
    ReflectAndRetryToolPlugin plugin = new StatusAwarePlugin();

    Map<String, Object> response =
        plugin
            .afterToolCallback(mockTool, ARGS, mockToolContext, ImmutableMap.of("status", "error"))
            .blockingGet();

    assertThat(response).containsEntry("retry_count", 1);
    assertThat(response).containsEntry("error_type", "IllegalStateException");
  }

  @Test
  public void scopeKey_whenOverridden_outranksTheConfiguredTrackingScope() {
    ReflectAndRetryToolPlugin plugin = new PerUserPlugin();
    callOnError(plugin);

    when(mockToolContext.invocationId()).thenReturn(OTHER_INVOCATION_ID);

    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);
  }

  private Map<String, Object> callOnError(ReflectAndRetryToolPlugin plugin) {
    return callOnError(plugin, error);
  }

  private Map<String, Object> callOnError(ReflectAndRetryToolPlugin plugin, Throwable thrown) {
    return plugin.onToolErrorCallback(mockTool, ARGS, mockToolContext, thrown).blockingGet();
  }

  /** No properties, so Jackson refuses to serialize it; only its {@code toString} can be used. */
  private static final class Opaque {

    @Override
    public String toString() {
      return "<opaque>";
    }
  }

  /**
   * Tracks failures per user, the documented reason to override {@code scopeKey}. Configured with
   * {@link TrackingScope#INVOCATION} so that counts surviving a change of invocation id can only be
   * the override taking effect.
   */
  private static final class PerUserPlugin extends ReflectAndRetryToolPlugin {

    PerUserPlugin() {
      super("per_user", 3, true, TrackingScope.INVOCATION);
    }

    @Override
    protected String scopeKey(ToolContext toolContext) {
      return USER_ID;
    }
  }

  /** Treats {@code {"status": "error"}} as a failure, the documented reason to override. */
  private static final class StatusAwarePlugin extends ReflectAndRetryToolPlugin {

    StatusAwarePlugin() {
      super("status_aware", 3, true, TrackingScope.INVOCATION);
    }

    @Override
    protected Maybe<Throwable> extractErrorFromResult(
        BaseTool tool,
        Map<String, Object> toolArgs,
        ToolContext toolContext,
        Map<String, Object> result) {
      return "error".equals(result.get("status"))
          ? Maybe.just(new IllegalStateException("tool reported status=error"))
          : Maybe.empty();
    }
  }
}
