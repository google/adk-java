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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
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
public class ContextFilterPluginTest {
  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();
  @Mock private CallbackContext mockCallbackContext;

  @Before
  public void setUp() {
    when(mockCallbackContext.invocationId()).thenReturn("invocation_id");
    when(mockCallbackContext.agentName()).thenReturn("agent_name");
  }

  @Test
  public void beforeModelCallback_noFiltering() {
    ContextFilterPlugin plugin = ContextFilterPlugin.builder().numInvocationsToKeep(10).build();
    LlmRequest.Builder llmRequestBuilder =
        LlmRequest.builder().contents(ImmutableList.of(userContent("hello")));

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).hasSize(1);
  }

  @Test
  public void beforeModelCallback_doFiltering() {
    ContextFilterPlugin plugin = ContextFilterPlugin.builder().numInvocationsToKeep(1).build();
    LlmRequest.Builder llmRequestBuilder =
        LlmRequest.builder()
            .contents(
                ImmutableList.of(
                    userContent("hello"),
                    modelResponse("world"),
                    userContent("how are you"),
                    modelResponse("good")));

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).hasSize(2);
  }

  @Test
  public void beforeModelCallback_functionResponseWithCallInWindow_noExpansionNeeded() {
    ContextFilterPlugin plugin = ContextFilterPlugin.builder().numInvocationsToKeep(2).build();
    ImmutableList<Content> contents =
        ImmutableList.of(
            userContent("hello"), // 0
            modelResponse("world"), // 1
            modelFunctionCall("id1", "func1", ImmutableMap.of("arg", "val")), // 2 - FunctionCall
            userFunctionResponse(
                "id1", "func1", ImmutableMap.of("result", "ok")), // 3 - FunctionResponse
            userContent("how are you"), // 4
            modelResponse("good")); // 5

    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);
    // With numInvocationsToKeep = 2, the initial split index based on the last two model turns
    // (at indices 5 and 2) is 2.
    // The FunctionResponse is at index 3, and its FunctionCall is at index 2, which is already
    // included.

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    // Expected contents: indices 2, 3, 4, 5.
    assertThat(llmRequestBuilder.build().contents())
        .containsExactlyElementsIn(contents.subList(2, 6))
        .inOrder();
    assertThat(llmRequestBuilder.build().contents()).hasSize(4);
  }

  @Test
  public void beforeModelCallback_functionResponseWithCallOutsideWindow_expandsWindow() {
    ContextFilterPlugin plugin = ContextFilterPlugin.builder().numInvocationsToKeep(1).build();
    ImmutableList<Content> contents =
        ImmutableList.of(
            userContent("hello"), // 0
            modelResponse("world"), // 1
            modelFunctionCall("id2", "func2", ImmutableMap.of("arg", "val")), // 2 - FunctionCall
            modelResponse("some text"), // 3
            userFunctionResponse(
                "id2", "func2", ImmutableMap.of("result", "ok")), // 4 - FunctionResponse
            userContent("how are you")); // 5

    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);
    // With numInvocationsToKeep = 1, the initial split index based on the last model turn
    // (at index 3) is 3.
    // Content 4 is a FunctionResponse for "func2". Its FunctionCall is at index 2, which is outside
    // the initial window (3, 4, 5).
    // The adjustSplitIndexToAvoidOrphanedFunctionResponses should expand the window to include
    // index 2.

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    // Expected contents: indices 2, 3, 4, 5
    assertThat(llmRequestBuilder.build().contents())
        .containsExactlyElementsIn(contents.subList(2, 6))
        .inOrder();
    assertThat(llmRequestBuilder.build().contents()).hasSize(4);
  }

  @Test
  public void beforeModelCallback_multipleFunctionCallsAndResponses_expandsCorrectly() {
    ContextFilterPlugin plugin = ContextFilterPlugin.builder().numInvocationsToKeep(1).build();
    ImmutableList<Content> contents =
        ImmutableList.of(
            userContent("init"), // 0
            modelResponse("ok"), // 1
            modelFunctionCall("id-f1", "f1", ImmutableMap.of()), // 2 - FC(f1)
            userFunctionResponse("id-f1", "f1", ImmutableMap.of()), // 3 - FR(f1)
            modelFunctionCall("id-f2", "f2", ImmutableMap.of()), // 4 - FC(f2)
            modelResponse("interim"), // 5
            userFunctionResponse("id-f2", "f2", ImmutableMap.of()), // 6 - FR(f2)
            userContent("last")); // 7

    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);
    // With numInvocationsToKeep = 1, the initial split index based on the last model turn
    // (at index 5) is 5.
    // Content 6 is FR(f2). Its FC is at index 4. This expands the window to include index 4.
    // The resulting indices are {4, 5, 6, 7}.

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).hasSize(4);
    assertThat(llmRequestBuilder.build().contents())
        .containsExactlyElementsIn(contents.subList(4, 8))
        .inOrder();
  }

  @Test
  public void beforeModelCallback_customFilterOnly() {
    ContextFilterPlugin plugin =
        ContextFilterPlugin.builder()
            .customFilter(contents -> contents.subList(contents.size() - 1, contents.size()))
            .build();
    ImmutableList<Content> contents = ImmutableList.of(userContent("hello"), userContent("world"));
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).hasSize(1);
    assertThat(llmRequestBuilder.build().contents().get(0).parts().get().get(0).text())
        .hasValue("world");
  }

  @Test
  public void beforeModelCallback_functionResponseAtEndOfContext_expandsToIncludeCall() {
    ContextFilterPlugin plugin = ContextFilterPlugin.builder().numInvocationsToKeep(1).build();
    ImmutableList<Content> contents =
        ImmutableList.of(
            userContent("hello"),
            modelFunctionCall("id3", "f3", ImmutableMap.of()),
            userFunctionResponse("id3", "f3", ImmutableMap.of()));

    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).containsExactlyElementsIn(contents).inOrder();
    assertThat(llmRequestBuilder.build().contents()).hasSize(3);
  }

  @Test
  public void beforeModelCallback_functionResponseExpanded_includesPrecedingUserTurn() {
    ContextFilterPlugin plugin = ContextFilterPlugin.builder().numInvocationsToKeep(1).build();
    ImmutableList<Content> contents =
        ImmutableList.of(
            userContent("user prompt"),
            modelFunctionCall("id4", "f4", ImmutableMap.of()),
            userFunctionResponse("id4", "f4", ImmutableMap.of()),
            modelResponse("final answer"));

    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).hasSize(4);
    assertThat(llmRequestBuilder.build().contents()).containsExactlyElementsIn(contents).inOrder();
  }

  @Test
  public void beforeModelCallback_filterWithFunctionAndLastNInvocations() {
    ContextFilterPlugin plugin =
        ContextFilterPlugin.builder()
            .numInvocationsToKeep(1)
            // When numInvocationsToKeep=1, we are left with 2 elements. This filter removes them.
            .customFilter(contents -> contents.subList(2, contents.size()))
            .build();
    ImmutableList<Content> contents =
        ImmutableList.of(
            userContent("user_prompt_1"),
            modelResponse("model_response_1"),
            userContent("user_prompt_2"),
            modelResponse("model_response_2"),
            userContent("user_prompt_3"),
            modelResponse("model_response_3"));
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).isEmpty();
  }

  @Test
  public void beforeModelCallback_noFilteringWhenNoOptionsProvided() {
    ContextFilterPlugin plugin = ContextFilterPlugin.builder().build();
    ImmutableList<Content> contents =
        ImmutableList.of(userContent("user_prompt_1"), modelResponse("model_response_1"));

    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).containsExactlyElementsIn(contents).inOrder();
  }

  @Test
  public void beforeModelCallback_lastNInvocationsWithMultipleUserTurns() {
    ContextFilterPlugin plugin = ContextFilterPlugin.builder().numInvocationsToKeep(1).build();
    ImmutableList<Content> contents =
        ImmutableList.of(
            userContent("user_prompt_1"),
            modelResponse("model_response_1"),
            userContent("user_prompt_2a"),
            userContent("user_prompt_2b"),
            modelResponse("model_response_2"));
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).hasSize(3);
    assertThat(llmRequestBuilder.build().contents().get(0).parts().get().get(0).text())
        .hasValue("user_prompt_2a");
    assertThat(llmRequestBuilder.build().contents().get(1).parts().get().get(0).text())
        .hasValue("user_prompt_2b");
    assertThat(llmRequestBuilder.build().contents().get(2).parts().get().get(0).text())
        .hasValue("model_response_2");
  }

  @Test
  public void beforeModelCallback_filterFunctionRaisesException() {
    ContextFilterPlugin plugin =
        ContextFilterPlugin.builder()
            .customFilter(
                unusedContents -> {
                  throw new RuntimeException("Filter error");
                })
            .build();
    ImmutableList<Content> contents =
        ImmutableList.of(userContent("user_prompt_1"), modelResponse("model_response_1"));

    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().contents(contents);

    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();

    assertThat(llmRequestBuilder.build().contents()).containsExactlyElementsIn(contents).inOrder();
  }

  private static Content userContent(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }

  private static Content modelResponse(String text) {
    return Content.builder().role("model").parts(Part.fromText(text)).build();
  }

  private static Content modelFunctionCall(String id, String name, Map<String, Object> args) {
    return Content.builder()
        .role("model")
        .parts(
            Part.builder()
                .functionCall(FunctionCall.builder().id(id).name(name).args(args).build())
                .build())
        .build();
  }

  private static Content userFunctionResponse(
      String id, String name, Map<String, Object> response) {
    return Content.builder()
        .role("user")
        .parts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder().id(id).name(name).response(response).build())
                .build())
        .build();
  }
}
