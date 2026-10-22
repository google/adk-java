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

package com.google.adk.plugins;

import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createRootAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.apps.App;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.ReflectAndRetryToolPlugin.TrackingScope;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ReflectAndRetryToolPluginTest {

  private static final String INVOCATION_ID = "invocation-1";
  private static final String OTHER_INVOCATION_ID = "invocation-2";
  private static final String BOOM = "boom";
  private static final String APP_NAME = "test";
  private static final String USER_ID = "user";
  private static final String THROWING_METHOD = "alwaysThrows";
  private static final String UNKNOWN_TOOL = "no_such_tool";
  private static final String CALL_ID = "call-%s";
  private static final String RESPONSE_TYPE_KEY = "response_type";
  private static final String RESPONSE_TYPE = "ERROR_HANDLED_BY_REFLECT_AND_RETRY_PLUGIN";
  private static final int RUN_TIMEOUT_SECONDS = 10;
  private static final ImmutableMap<String, Object> FAIL = ImmutableMap.of("fail", true);
  private static final ImmutableMap<String, Object> SUCCEED = ImmutableMap.of("fail", false);
  private static final String STATUS_ERROR_MESSAGE = "tool reported status=error";
  private static final String GENERIC_EXCEEDED_MESSAGE =
      "A tool result reported an error and the retry limit was exceeded.";
  private static final ImmutableMap<String, Object> STATUS_ERROR_RESULT =
      ImmutableMap.of("status", "error");
  private static final String TOOL_NAME = "flaky_tool";
  private static final String OTHER_TOOL_NAME = "other_tool";
  private static final ImmutableMap<String, Object> ARGS = ImmutableMap.of("city", "Phoenix");
  private static final int THREADS = 8;
  private static final int FAILURES_PER_THREAD = 2_000;
  private static final int TOTAL_FAILURES = THREADS * FAILURES_PER_THREAD;
  private static final int PARALLEL_TIMEOUT_SECONDS = 30;

  private final BaseTool tool = new FlakyTool(TOOL_NAME);
  private final BaseTool otherTool = new FlakyTool(OTHER_TOOL_NAME);
  private final InvocationContext invocation = invocation(INVOCATION_ID);
  private final InvocationContext otherInvocation = invocation(OTHER_INVOCATION_ID);
  private final ToolContext toolContext = ToolContext.builder(invocation).build();
  private final ToolContext otherToolContext = ToolContext.builder(otherInvocation).build();
  private final RuntimeException error = new IllegalStateException(BOOM);

  @Test
  public void onToolError_withinRetryLimit_returnsReflectionGuidance() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();

    Map<String, Object> response = callOnError(plugin);

    assertThat(response).containsEntry(RESPONSE_TYPE_KEY, RESPONSE_TYPE);
    assertThat(response).containsEntry("error_type", "IllegalStateException");
    assertThat(response).containsEntry("error_details", BOOM);
    assertThat(response).containsEntry("retry_count", 1);
    assertThat((String) response.get("reflection_guidance")).contains("retry attempt **1 of 3**");
  }

  @Test
  public void onToolError_guidanceCarriesToolNameAndArguments() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();

    String guidance = (String) callOnError(plugin).get("reflection_guidance");

    assertThat(guidance).contains(TOOL_NAME);
    assertThat(guidance).contains("\"city\" : \"Phoenix\"");
    assertThat(guidance).contains("IllegalStateException: boom");
  }

  @Test
  public void onToolError_errorWithoutMessage_reportsEmptyDetails() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();

    Map<String, Object> response = callOnError(plugin, new IllegalStateException());

    assertThat(response).containsEntry("error_details", "");
    assertThat((String) response.get("reflection_guidance"))
        .contains("```\nIllegalStateException: \n```");
  }

  @Test
  public void onToolError_pastRetryLimit_errorWithoutMessage_reportsEmptyDetails() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .maxRetries(1)
            .throwExceptionIfRetryExceeded(false)
            .build();
    IllegalStateException messageless = new IllegalStateException();

    callOnError(plugin, messageless);
    Map<String, Object> exceeded = callOnError(plugin, messageless);

    assertThat(exceeded).containsEntry("error_details", "");
    assertThat((String) exceeded.get("reflection_guidance"))
        .contains("```\nIllegalStateException: \n```");
  }

  @Test
  public void onToolError_countsNothingUntilSubscribed() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();

    Maybe<Map<String, Object>> failure = plugin.onToolErrorCallback(tool, ARGS, toolContext, error);
    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);

    assertThat(failure.blockingGet()).containsEntry("retry_count", 2);
  }

  @Test
  public void afterTool_resetsNothingUntilSubscribed() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    callOnError(plugin);
    callOnError(plugin);

    Maybe<Map<String, Object>> success =
        plugin.afterToolCallback(tool, ARGS, toolContext, ImmutableMap.of("ok", true));
    assertThat(callOnError(plugin)).containsEntry("retry_count", 3);

    success.test().assertNoValues().assertComplete();
    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void afterRun_clearsNothingUntilSubscribed() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    callOnError(plugin);

    Completable cleanup = plugin.afterRunCallback(invocation);
    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);

    cleanup.test().assertComplete();
    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void onRunError_clearsNothingUntilSubscribed() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    callOnError(plugin);

    Completable cleanup = plugin.onRunErrorCallback(invocation, error);
    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);

    cleanup.test().assertComplete();
    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void onToolError_countsConsecutiveFailures() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();

    callOnError(plugin);
    callOnError(plugin);
    Map<String, Object> third = callOnError(plugin);

    assertThat(third).containsEntry("retry_count", 3);
  }

  @Test
  public void onToolError_pastRetryLimit_completesEmptySoTheOriginalErrorPropagates() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(2).build();

    callOnError(plugin);
    callOnError(plugin);

    plugin
        .onToolErrorCallback(tool, ARGS, toolContext, error)
        .test()
        .assertNoValues()
        .assertComplete();
  }

  @Test
  public void onToolError_pastRetryLimit_whenNotThrowing_returnsGiveUpMessage() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .maxRetries(1)
            .throwExceptionIfRetryExceeded(false)
            .build();

    callOnError(plugin);
    Map<String, Object> exceeded = callOnError(plugin);

    assertThat((String) exceeded.get("reflection_guidance"))
        .contains("Do not attempt to use the `flaky_tool` tool again");
    assertThat(exceeded).containsEntry("retry_count", 1);
    assertThat((String) exceeded.get("reflection_guidance"))
        .contains("has failed consecutively 1 times");
  }

  @Test
  public void maxRetriesZero_completesEmptyImmediately() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(0).build();

    plugin
        .onToolErrorCallback(tool, ARGS, toolContext, error)
        .test()
        .assertNoValues()
        .assertComplete();
  }

  @Test
  public void maxRetriesZero_whenNotThrowing_returnsGiveUpMessage() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .maxRetries(0)
            .throwExceptionIfRetryExceeded(false)
            .build();

    Map<String, Object> exceeded = callOnError(plugin);

    assertThat(exceeded).containsEntry("retry_count", 0);
    assertThat((String) exceeded.get("reflection_guidance"))
        .contains("has failed consecutively 0 times");
  }

  @Test
  public void onToolError_wellPastRetryLimit_keepsReportingMaxRetries() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .maxRetries(2)
            .throwExceptionIfRetryExceeded(false)
            .build();

    callOnError(plugin);
    callOnError(plugin);
    callOnError(plugin);
    Map<String, Object> fourth = callOnError(plugin);

    assertThat(fourth).containsEntry("retry_count", 2);
    assertThat((String) fourth.get("reflection_guidance"))
        .contains("has failed consecutively 2 times");
  }

  @Test
  public void onToolError_whenAnArgumentCannotBeSerialized_writesOnlyThatValueAsText() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    ImmutableMap<String, Object> args = ImmutableMap.of("city", "Phoenix", "payload", new Opaque());

    String guidance = guidance(plugin, args);

    assertThat(guidance).contains("\"city\" : \"Phoenix\"");
    assertThat(guidance).contains("\"payload\" : \"<opaque>\"");
  }

  @Test
  public void onToolError_whenAnArrayElementCannotBeSerialized_writesOnlyThatElementAsText() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    ImmutableMap<String, Object> args = ImmutableMap.of("items", new Object[] {1, new Opaque()});

    String guidance = guidance(plugin, args);

    assertThat(guidance).containsMatch("\"items\" : \\[ 1, \"<opaque>\" \\]");
  }

  @Test
  public void onToolError_whenArgumentsContainThemselves_marksTheCycleInsteadOfThrowing() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    Map<String, Object> args = new HashMap<>();
    args.put("self", args);

    String guidance = guidance(plugin, args);

    assertThat(guidance).contains("\"self\" : \"<circular reference>\"");
  }

  @Test
  public void onToolError_whenAnArgumentAppearsTwice_writesItBothTimes() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    ImmutableMap<String, Object> shared = ImmutableMap.of("unit", "F");
    ImmutableMap<String, Object> args =
        ImmutableMap.of("first", shared, "second", shared, "payload", new Opaque());

    String guidance = guidance(plugin, args);

    assertThat(guidance).doesNotContain("<circular reference>");
    assertThat(guidance).containsMatch("\"second\" : \\{\\s*\"unit\" : \"F\"");
  }

  @Test
  public void onToolError_whenANestedArgumentCannotBeSerialized_writesOnlyThatValueAsText() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    ImmutableMap<String, Object> args =
        ImmutableMap.of("items", ImmutableList.of(1, new Opaque()), "meta", ImmutableMap.of());

    String guidance = guidance(plugin, args);

    assertThat(guidance).containsMatch("\"items\" : \\[ 1, \"<opaque>\" \\]");
    assertThat(guidance).contains("\"meta\" : { }");
  }

  @Test
  public void builder_defaults_retryThreeTimesThenLetTheErrorPropagate() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();

    callOnError(plugin);
    callOnError(plugin);

    assertThat(plugin.getName()).isEqualTo("reflect_retry_tool_plugin");
    assertThat(callOnError(plugin)).containsEntry("retry_count", 3);
    plugin
        .onToolErrorCallback(tool, ARGS, toolContext, error)
        .test()
        .assertNoValues()
        .assertComplete();
  }

  @Test
  public void builder_name_namesThePlugin() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().name("custom").build();

    assertThat(plugin.getName()).isEqualTo("custom");
  }

  @Test
  public void builder_negativeMaxRetries_isRejected() {
    assertThrows(
        IllegalArgumentException.class, () -> ReflectAndRetryToolPlugin.builder().maxRetries(-1));
  }

  @Test
  public void builder_nullTrackingScope_isRejected() {
    assertThrows(
        NullPointerException.class, () -> ReflectAndRetryToolPlugin.builder().trackingScope(null));
  }

  @Test
  public void builder_nullResultErrorExtractor_isRejected() {
    assertThrows(
        NullPointerException.class,
        () -> ReflectAndRetryToolPlugin.builder().resultErrorExtractor(null));
  }

  @Test
  public void builder_nullName_isRejected() {
    assertThrows(NullPointerException.class, () -> ReflectAndRetryToolPlugin.builder().name(null));
  }

  @Test
  public void afterTool_onSuccess_resetsThatToolsCounter() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();
    callOnError(plugin);
    callOnError(plugin);

    plugin
        .afterToolCallback(tool, ARGS, toolContext, ImmutableMap.of("ok", true))
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void afterTool_successOfOneTool_doesNotForgiveAnother() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();
    callOnError(plugin);
    callOnErrorFor(plugin, otherTool);

    plugin
        .afterToolCallback(otherTool, ARGS, toolContext, ImmutableMap.of("ok", true))
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);
    assertThat(callOnErrorFor(plugin, otherTool)).containsEntry("retry_count", 1);
  }

  @Test
  public void onToolError_countsEachToolSeparately() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    callOnError(plugin);
    callOnError(plugin);

    Map<String, Object> other = callOnErrorFor(plugin, otherTool);

    assertThat(other).containsEntry("retry_count", 1);
    assertThat(callOnError(plugin)).containsEntry("retry_count", 3);
  }

  @Test
  public void afterTool_successInOneInvocation_leavesAnotherInvocationsCount() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    callOnErrorIn(plugin, otherToolContext);
    callOnError(plugin);

    plugin
        .afterToolCallback(tool, ARGS, toolContext, ImmutableMap.of("ok", true))
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(callOnErrorIn(plugin, otherToolContext)).containsEntry("retry_count", 2);
    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void afterTool_ownReflectionResponse_isPassedThroughUncounted() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();
    Map<String, Object> reflection = callOnError(plugin);

    plugin
        .afterToolCallback(tool, ARGS, toolContext, reflection)
        .test()
        .assertNoValues()
        .assertComplete();
    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);

    plugin
        .afterToolCallback(tool, ARGS, toolContext, ImmutableMap.of("ok", true))
        .test()
        .assertComplete();
    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void invocationScope_countsAreIsolatedPerInvocation() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();
    callOnError(plugin);

    assertThat(callOnErrorIn(plugin, otherToolContext)).containsEntry("retry_count", 1);
  }

  @Test
  public void globalScope_countsSurviveAcrossInvocations() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder().trackingScope(TrackingScope.GLOBAL).build();
    callOnError(plugin);

    assertThat(callOnErrorIn(plugin, otherToolContext)).containsEntry("retry_count", 2);
  }

  @Test
  public void globalScope_successInAnotherInvocation_resetsTheCount() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder().trackingScope(TrackingScope.GLOBAL).build();
    callOnError(plugin);
    callOnError(plugin);

    plugin
        .afterToolCallback(tool, ARGS, otherToolContext, ImmutableMap.of("ok", true))
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void afterRun_clearsTheEndedInvocationsCounts() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();
    callOnError(plugin);
    callOnError(plugin);

    plugin.afterRunCallback(invocation).test().assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void onRunError_clearsTheEndedInvocationsCounts() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();
    callOnError(plugin);
    callOnError(plugin);

    plugin.onRunErrorCallback(invocation, error).test().assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void afterRun_leavesOtherInvocationsCountsAlone() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();
    callOnError(plugin);
    callOnErrorIn(plugin, otherToolContext);

    plugin.afterRunCallback(otherInvocation).test().assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);
    assertThat(callOnErrorIn(plugin, otherToolContext)).containsEntry("retry_count", 1);
  }

  @Test
  public void globalScope_afterRun_keepsCounts() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder().trackingScope(TrackingScope.GLOBAL).build();
    callOnError(plugin);

    plugin.afterRunCallback(invocation).test().assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);
  }

  @Test
  public void onRunError_leavesOtherInvocationsCountsAlone() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().maxRetries(3).build();
    callOnError(plugin);
    callOnErrorIn(plugin, otherToolContext);

    plugin.onRunErrorCallback(otherInvocation, error).test().assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);
    assertThat(callOnErrorIn(plugin, otherToolContext)).containsEntry("retry_count", 1);
  }

  @Test
  public void globalScope_onRunError_keepsCounts() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder().trackingScope(TrackingScope.GLOBAL).build();
    callOnError(plugin);

    plugin.onRunErrorCallback(invocation, error).test().assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 2);
  }

  @Test
  public void defaultExtractor_treatsEveryResultAsSuccess() {
    ReflectAndRetryToolPlugin plugin = ReflectAndRetryToolPlugin.builder().build();
    callOnError(plugin);

    plugin
        .afterToolCallback(tool, ARGS, toolContext, STATUS_ERROR_RESULT)
        .test()
        .assertNoValues()
        .assertComplete();

    assertThat(callOnError(plugin)).containsEntry("retry_count", 1);
  }

  @Test
  public void resultErrorExtractor_throwableError_isReportedByItsType() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .resultErrorExtractor(ReflectAndRetryToolPluginTest::statusErrorAsException)
            .build();

    Map<String, Object> response = callAfterTool(plugin);

    assertThat(response).containsEntry("retry_count", 1);
    assertThat(response).containsEntry("error_type", "IllegalStateException");
    assertThat(response).containsEntry("error_details", STATUS_ERROR_MESSAGE);
  }

  @Test
  public void resultErrorExtractor_mapError_isReportedAsToolErrorWithTheMapAsData() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .resultErrorExtractor(ReflectAndRetryToolPluginTest::statusErrorAsResult)
            .build();

    Map<String, Object> response = callAfterTool(plugin);

    assertThat(response).containsEntry("error_type", "ToolError");
    assertThat(response).containsEntry("error_details", STATUS_ERROR_RESULT);
    assertThat((String) response.get("reflection_guidance")).contains("\"status\" : \"error\"");
  }

  @Test
  public void resultErrorExtractor_textError_isReportedAsToolErrorText() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .resultErrorExtractor(ReflectAndRetryToolPluginTest::statusErrorAsText)
            .build();

    Map<String, Object> response = callAfterTool(plugin);

    assertThat(response).containsEntry("error_type", "ToolError");
    assertThat(response).containsEntry("error_details", STATUS_ERROR_MESSAGE);
    assertThat((String) response.get("reflection_guidance"))
        .contains("```\n%s\n```".formatted(STATUS_ERROR_MESSAGE));
  }

  @Test
  public void resultErrorExtractor_pastRetryLimit_emitsTheThrowableError() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .maxRetries(1)
            .resultErrorExtractor(ReflectAndRetryToolPluginTest::statusErrorAsException)
            .build();
    callAfterTool(plugin);

    plugin
        .afterToolCallback(tool, ARGS, toolContext, STATUS_ERROR_RESULT)
        .test()
        .assertError(
            e -> e instanceof IllegalStateException && STATUS_ERROR_MESSAGE.equals(e.getMessage()));
  }

  @Test
  public void resultErrorExtractor_pastRetryLimit_wrapsANonThrowableErrorGenerically() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .maxRetries(1)
            .resultErrorExtractor(ReflectAndRetryToolPluginTest::statusErrorAsResult)
            .build();
    callAfterTool(plugin);

    plugin
        .afterToolCallback(tool, ARGS, toolContext, STATUS_ERROR_RESULT)
        .test()
        .assertError(
            e ->
                e instanceof IllegalStateException
                    && GENERIC_EXCEEDED_MESSAGE.equals(e.getMessage()));
  }

  @Test
  public void resultErrorExtractor_pastRetryLimit_whenNotThrowing_returnsGiveUpMessage() {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .maxRetries(1)
            .throwExceptionIfRetryExceeded(false)
            .resultErrorExtractor(ReflectAndRetryToolPluginTest::statusErrorAsResult)
            .build();
    callAfterTool(plugin);

    Map<String, Object> exceeded = callAfterTool(plugin);

    assertThat(exceeded).containsEntry("error_type", "ToolError");
    assertThat(exceeded).containsEntry("retry_count", 1);
    assertThat((String) exceeded.get("reflection_guidance"))
        .contains("Do not attempt to use the `flaky_tool` tool again");
  }

  @Test
  public void toolReturningSingleError_modelReceivesReflectionGuidance() {
    TestLlm llm = createTestLlm(call(TOOL_NAME, FAIL), createTextLlmResponse("done"));

    run(llm, tool, ReflectAndRetryToolPlugin.builder().build()).assertComplete();

    Map<String, Object> response = functionResponseIn(llm.getRequests().get(1));
    assertThat(response).containsEntry(RESPONSE_TYPE_KEY, RESPONSE_TYPE);
    assertThat(response).containsEntry("error_type", "IllegalStateException");
    assertThat(response).containsEntry("error_details", BOOM);
    assertThat(response).containsEntry("retry_count", 1);
    assertThat(hasGuidance(llm.getRequests().get(1))).isTrue();
  }

  @Test
  public void successfulCall_resetsTheCountForTheNextFailure() {
    TestLlm llm =
        createTestLlm(
            call(TOOL_NAME, FAIL),
            call(TOOL_NAME, FAIL),
            call(TOOL_NAME, SUCCEED),
            call(TOOL_NAME, FAIL),
            createTextLlmResponse("done"));

    run(llm, tool, ReflectAndRetryToolPlugin.builder().build()).assertComplete();

    assertThat(functionResponseIn(llm.getRequests().get(2))).containsEntry("retry_count", 2);
    assertThat(functionResponseIn(llm.getRequests().get(3))).containsEntry("ok", true);
    assertThat(functionResponseIn(llm.getRequests().get(4))).containsEntry("retry_count", 1);
  }

  @Test
  public void pastRetryLimit_runFailsWithTheToolsOwnError() {
    TestLlm llm =
        createTestLlm(call(TOOL_NAME, FAIL), call(TOOL_NAME, FAIL), createTextLlmResponse("x"));

    run(llm, tool, ReflectAndRetryToolPlugin.builder().maxRetries(1).build())
        .assertError(e -> e instanceof IllegalStateException && BOOM.equals(e.getMessage()));
  }

  @Test
  public void functionToolThatThrows_reachesThePluginAsAStatusErrorResult() {
    TestLlm llm =
        createTestLlm(call(THROWING_METHOD, ImmutableMap.of()), createTextLlmResponse("x"));
    BaseTool tool = FunctionTool.create(ReflectAndRetryToolPluginTest.class, THROWING_METHOD);

    run(llm, tool, ReflectAndRetryToolPlugin.builder().build()).assertComplete();

    Map<String, Object> response = functionResponseIn(llm.getRequests().get(1));
    assertThat(response).doesNotContainKey(RESPONSE_TYPE_KEY);
    assertThat(response).containsEntry("status", "error");
  }

  @Test
  public void functionToolThatThrows_withAStatusExtractor_modelReceivesReflectionGuidance() {
    TestLlm llm =
        createTestLlm(call(THROWING_METHOD, ImmutableMap.of()), createTextLlmResponse("x"));
    BaseTool tool = FunctionTool.create(ReflectAndRetryToolPluginTest.class, THROWING_METHOD);
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder()
            .resultErrorExtractor(ReflectAndRetryToolPluginTest::statusErrorAsResult)
            .build();

    run(llm, tool, plugin).assertComplete();

    Map<String, Object> response = functionResponseIn(llm.getRequests().get(1));
    assertThat(response).containsEntry(RESPONSE_TYPE_KEY, RESPONSE_TYPE);
    assertThat(response).containsEntry("error_type", "ToolError");
    assertThat(response).containsEntry("retry_count", 1);
  }

  @Test
  public void unknownToolName_neverReachesThePlugin() {
    TestLlm llm = createTestLlm(call(UNKNOWN_TOOL, ImmutableMap.of()), createTextLlmResponse("x"));

    run(llm, tool, ReflectAndRetryToolPlugin.builder().build()).assertComplete();

    assertThat(llm.getRequests().stream().anyMatch(ReflectAndRetryToolPluginTest::hasGuidance))
        .isFalse();
  }

  /**
   * Every thread fails the same tool from a common start. A lost update repeats a count and a
   * dropped one skips it, so the {@code retry_count}s handed back across all threads are exactly
   * {@code 1..n} if and only if every update was atomic.
   */
  @Test
  public void onToolError_inParallel_countsEachFailureExactlyOnce() throws Exception {
    ReflectAndRetryToolPlugin plugin =
        ReflectAndRetryToolPlugin.builder().maxRetries(TOTAL_FAILURES).build();
    CyclicBarrier startTogether = new CyclicBarrier(THREADS);
    ExecutorService threads = Executors.newFixedThreadPool(THREADS);

    List<Future<ImmutableList<Integer>>> counts =
        threads.invokeAll(Collections.nCopies(THREADS, failRepeatedlyAt(plugin, startTogether)));
    threads.shutdown();

    assertThat(threads.awaitTermination(PARALLEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(resolve(counts)).containsExactlyElementsIn(oneTo(TOTAL_FAILURES));
  }

  private Callable<ImmutableList<Integer>> failRepeatedlyAt(
      ReflectAndRetryToolPlugin plugin, CyclicBarrier barrier) {
    return () -> failRepeatedlyAfterBarrier(plugin, barrier);
  }

  private ImmutableList<Integer> failRepeatedlyAfterBarrier(
      ReflectAndRetryToolPlugin plugin, CyclicBarrier barrier) throws Exception {
    barrier.await(PARALLEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    return IntStream.range(0, FAILURES_PER_THREAD)
        .mapToObj(unused -> retryCount(callOnError(plugin)))
        .collect(ImmutableList.toImmutableList());
  }

  private static int retryCount(Map<String, Object> response) {
    return (Integer) response.get("retry_count");
  }

  private static ImmutableList<Integer> resolve(List<Future<ImmutableList<Integer>>> futures)
      throws Exception {
    ImmutableList.Builder<Integer> counts = ImmutableList.builder();
    for (Future<ImmutableList<Integer>> future : futures) {
      counts.addAll(future.get());
    }
    return counts.build();
  }

  private static ImmutableList<Integer> oneTo(int last) {
    return IntStream.rangeClosed(1, last).boxed().collect(ImmutableList.toImmutableList());
  }

  private Map<String, Object> callOnError(ReflectAndRetryToolPlugin plugin) {
    return callOnError(plugin, error);
  }

  private Map<String, Object> callOnError(ReflectAndRetryToolPlugin plugin, Throwable thrown) {
    return plugin.onToolErrorCallback(tool, ARGS, toolContext, thrown).blockingGet();
  }

  private Map<String, Object> callOnErrorFor(ReflectAndRetryToolPlugin plugin, BaseTool failing) {
    return plugin.onToolErrorCallback(failing, ARGS, toolContext, error).blockingGet();
  }

  private Map<String, Object> callOnErrorIn(ReflectAndRetryToolPlugin plugin, ToolContext context) {
    return plugin.onToolErrorCallback(tool, ARGS, context, error).blockingGet();
  }

  private static InvocationContext invocation(String invocationId) {
    return createInvocationContext(createRootAgent()).toBuilder()
        .invocationId(invocationId)
        .build();
  }

  private String guidance(ReflectAndRetryToolPlugin plugin, Map<String, Object> args) {
    return (String)
        plugin
            .onToolErrorCallback(tool, args, toolContext, error)
            .blockingGet()
            .get("reflection_guidance");
  }

  private Map<String, Object> callAfterTool(ReflectAndRetryToolPlugin plugin) {
    return plugin.afterToolCallback(tool, ARGS, toolContext, STATUS_ERROR_RESULT).blockingGet();
  }

  /** A {@code FunctionTool} method that throws, which {@code FunctionTool} turns into a result. */
  public static Map<String, Object> alwaysThrows() {
    throw new IllegalStateException(BOOM);
  }

  private static TestSubscriber<Event> run(
      TestLlm llm, BaseTool tool, ReflectAndRetryToolPlugin plugin) {
    LlmAgent agent = createTestAgentBuilder(llm).tools(ImmutableList.of(tool)).build();
    Runner runner =
        Runner.builder()
            .app(
                App.builder()
                    .name(APP_NAME)
                    .rootAgent(agent)
                    .plugins(ImmutableList.of(plugin))
                    .build())
            .build();
    Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
    return runner
        .runAsync(USER_ID, session.id(), userMessage())
        .test()
        .awaitDone(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  private static Content userMessage() {
    return Content.builder().role("user").parts(Part.fromText("go")).build();
  }

  /** A model turn calling {@code toolName}, with an id of its own so responses pair correctly. */
  private static LlmResponse call(String toolName, Map<String, Object> args) {
    return createFunctionCallLlmResponse(CALL_ID.formatted(UUID.randomUUID()), toolName, args);
  }

  /** The function response carried by the last content of {@code request}. */
  private static Map<String, Object> functionResponseIn(LlmRequest request) {
    return functionResponses(Iterables.getLast(request.contents())).stream()
        .findFirst()
        .orElseThrow();
  }

  private static boolean hasGuidance(LlmRequest request) {
    return request.contents().stream()
        .flatMap(content -> functionResponses(content).stream())
        .anyMatch(response -> RESPONSE_TYPE.equals(response.get(RESPONSE_TYPE_KEY)));
  }

  private static ImmutableList<Map<String, Object>> functionResponses(Content content) {
    return content.parts().orElse(ImmutableList.of()).stream()
        .flatMap(part -> part.functionResponse().stream())
        .flatMap(response -> response.response().stream())
        .collect(ImmutableList.toImmutableList());
  }

  /** Reports {@code {"status": "error"}} as an exception. */
  private static Optional<Object> statusErrorAsException(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    return isStatusError(result)
        ? Optional.of(new IllegalStateException(STATUS_ERROR_MESSAGE))
        : Optional.empty();
  }

  /** Reports the result itself as the error, the way upstream's documentation suggests. */
  private static Optional<Object> statusErrorAsResult(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    return isStatusError(result) ? Optional.of(result) : Optional.empty();
  }

  /** Reports {@code {"status": "error"}} as plain text. */
  private static Optional<Object> statusErrorAsText(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    return isStatusError(result) ? Optional.of(STATUS_ERROR_MESSAGE) : Optional.empty();
  }

  private static boolean isStatusError(Map<String, Object> result) {
    return "error".equals(result.get("status"));
  }

  /** Fails with {@code Single.error} when called with {@code "fail": true}, else succeeds. */
  private static final class FlakyTool extends BaseTool {

    FlakyTool(String name) {
      super(name, "Fails on request");
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
      return Optional.of(FunctionDeclaration.builder().name(name()).build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return Boolean.TRUE.equals(args.get("fail"))
          ? Single.error(new IllegalStateException(BOOM))
          : Single.just(ImmutableMap.of("ok", true));
    }
  }

  /** No properties, so Jackson refuses to serialize it; only its {@code toString} can be used. */
  private static final class Opaque {

    @Override
    public String toString() {
      return "<opaque>";
    }
  }
}
