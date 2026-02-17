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

package com.google.adk.examples;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ExampleUtilsTest {

  private static class TestExampleProvider implements BaseExampleProvider {
    private final ImmutableList<Example> examples;

    TestExampleProvider(ImmutableList<Example> examples) {
      this.examples = examples;
    }

    @Override
    public List<Example> getExamples(String query) {
      return examples;
    }
  }

  // TODO: sduskis - Should this 0 examples use case actually return ""?
  @Test
  public void buildFewShotFewShot_noExamples() {
    TestExampleProvider exampleProvider = new TestExampleProvider(ImmutableList.of());
    String expected =
        """
        <EXAMPLES>
        Begin few-shot
        The following are examples of user queries and model responses using the available tools.

        End few-shot
        Now, try to follow these examples and complete the following conversation
        <EXAMPLES>\
        """;
    assertThat(ExampleUtils.buildExampleSi(exampleProvider, "test query")).isEqualTo(expected);
  }

  @Test
  public void buildFewShotFewShot_singleTextExample() {
    Example example =
        Example.builder()
            .input(Content.builder().role("user").parts(Part.fromText("User input")).build())
            .output(
                ImmutableList.of(
                    Content.builder().role("model").parts(Part.fromText("Model response")).build()))
            .build();
    TestExampleProvider exampleProvider = new TestExampleProvider(ImmutableList.of(example));
    String expected =
        """
        <EXAMPLES>
        Begin few-shot
        The following are examples of user queries and model responses using the available tools.

        EXAMPLE 1:
        Begin example
        [user]
        User input

        [model]
        Model response
        End example

        End few-shot
        Now, try to follow these examples and complete the following conversation
        <EXAMPLES>\
        """;
    assertThat(ExampleUtils.buildExampleSi(exampleProvider, "test query")).isEqualTo(expected);
  }

  @Test
  public void buildFewShotFewShot_singleFunctionCallExample() {
    Example example =
        Example.builder()
            .input(Content.builder().role("user").parts(Part.fromText("User input")).build())
            .output(
                ImmutableList.of(
                    Content.builder()
                        .role("model")
                        .parts(
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder()
                                        .name("test_function")
                                        .args(ImmutableMap.of("arg1", "value1", "arg2", 123))
                                        .build())
                                .build())
                        .build()))
            .build();
    TestExampleProvider exampleProvider = new TestExampleProvider(ImmutableList.of(example));
    String expected =
        """
        <EXAMPLES>
        Begin few-shot
        The following are examples of user queries and model responses using the available tools.

        EXAMPLE 1:
        Begin example
        [user]
        User input

        [model]
        ```tool_code
        test_function(arg1='value1', arg2=123)
        ```
        End example

        End few-shot
        Now, try to follow these examples and complete the following conversation
        <EXAMPLES>\
        """;
    assertThat(ExampleUtils.buildExampleSi(exampleProvider, "test query")).isEqualTo(expected);
  }

  @Test
  public void buildFewShotFewShot_singleFunctionResponseExample() {
    Example example =
        Example.builder()
            .input(Content.builder().role("user").parts(Part.fromText("User input")).build())
            .output(
                ImmutableList.of(
                    Content.builder()
                        .role("model")
                        .parts(
                            Part.builder()
                                .functionResponse(
                                    FunctionResponse.builder()
                                        .name("test_function")
                                        .response(ImmutableMap.of("result", "success"))
                                        .build())
                                .build())
                        .build()))
            .build();
    TestExampleProvider exampleProvider = new TestExampleProvider(ImmutableList.of(example));
    String expected =
        """
        <EXAMPLES>
        Begin few-shot
        The following are examples of user queries and model responses using the available tools.

        EXAMPLE 1:
        Begin example
        [user]
        User input

        ```tool_outputs
        {"result":"success"}
        ```
        End example

        End few-shot
        Now, try to follow these examples and complete the following conversation
        <EXAMPLES>\
        """;
    assertThat(ExampleUtils.buildExampleSi(exampleProvider, "test query")).isEqualTo(expected);
  }

  @Test
  public void buildFewShotFewShot_mixedExample() {
    Example example =
        Example.builder()
            .input(Content.builder().role("user").parts(Part.fromText("User input")).build())
            .output(
                ImmutableList.of(
                    Content.builder()
                        .role("model")
                        .parts(
                            Part.fromText("Some text"),
                            Part.builder()
                                .functionCall(
                                    FunctionCall.builder()
                                        .name("func1")
                                        .args(ImmutableMap.of("a", "b"))
                                        .build())
                                .build(),
                            Part.builder()
                                .functionResponse(
                                    FunctionResponse.builder()
                                        .name("func1")
                                        .response(ImmutableMap.of("c", "d"))
                                        .build())
                                .build(),
                            Part.fromText("More text"))
                        .build()))
            .build();
    TestExampleProvider exampleProvider = new TestExampleProvider(ImmutableList.of(example));
    String expected =
        """
        <EXAMPLES>
        Begin few-shot
        The following are examples of user queries and model responses using the available tools.

        EXAMPLE 1:
        Begin example
        [user]
        User input

        [model]
        Some text
        [model]
        ```tool_code
        func1(a='b')
        ```
        ```tool_outputs
        {"c":"d"}
        ```
        [model]
        More text
        End example

        End few-shot
        Now, try to follow these examples and complete the following conversation
        <EXAMPLES>\
        """;
    assertThat(ExampleUtils.buildExampleSi(exampleProvider, "test query")).isEqualTo(expected);
  }

  @Test
  public void buildFewShotFewShot_multipleExamples() {
    Example example1 =
        Example.builder()
            .input(Content.builder().role("user").parts(Part.fromText("User input 1")).build())
            .output(
                ImmutableList.of(
                    Content.builder()
                        .role("model")
                        .parts(Part.fromText("Model response 1"))
                        .build()))
            .build();
    Example example2 =
        Example.builder()
            .input(Content.builder().role("user").parts(Part.fromText("User input 2")).build())
            .output(
                ImmutableList.of(
                    Content.builder()
                        .role("model")
                        .parts(Part.fromText("Model response 2"))
                        .build()))
            .build();
    TestExampleProvider exampleProvider =
        new TestExampleProvider(ImmutableList.of(example1, example2));
    String expected =
        """
        <EXAMPLES>
        Begin few-shot
        The following are examples of user queries and model responses using the available tools.

        EXAMPLE 1:
        Begin example
        [user]
        User input 1

        [model]
        Model response 1
        End example

        EXAMPLE 2:
        Begin example
        [user]
        User input 2

        [model]
        Model response 2
        End example

        End few-shot
        Now, try to follow these examples and complete the following conversation
        <EXAMPLES>\
        """;
    assertThat(ExampleUtils.buildExampleSi(exampleProvider, "test query")).isEqualTo(expected);
  }

  @Test
  public void buildFewShotFewShot_onlyOutputExample() {
    Example example =
        Example.builder()
            .input(Content.builder().build()) // Provide an empty Content for input
            .output(
                ImmutableList.of(
                    Content.builder().role("model").parts(Part.fromText("Model response")).build()))
            .build();
    TestExampleProvider exampleProvider = new TestExampleProvider(ImmutableList.of(example));
    String expected =
        """
        <EXAMPLES>
        Begin few-shot
        The following are examples of user queries and model responses using the available tools.

        EXAMPLE 1:
        Begin example
        [model]
        Model response
        End example

        End few-shot
        Now, try to follow these examples and complete the following conversation
        <EXAMPLES>\
        """;
    assertThat(ExampleUtils.buildExampleSi(exampleProvider, "test query")).isEqualTo(expected);
  }
}
