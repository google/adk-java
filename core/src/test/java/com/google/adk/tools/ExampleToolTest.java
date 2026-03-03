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

package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.ConfigAgentUtils.ConfigurationException;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.examples.BaseExampleProvider;
import com.google.adk.examples.Example;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ExampleToolTest {

  /** Helper to create a minimal agent & context for testing. */
  private InvocationContext buildInvocationContext() {
    TestLlm testLlm = new TestLlm(() -> Flowable.just(LlmResponse.builder().build()));
    LlmAgent agent = TestUtils.createTestAgent(testLlm);
    return TestUtils.createInvocationContext(agent);
  }

  private static Example makeExample(String in, String out) {
    return Example.builder()
        .input(Content.fromParts(Part.fromText(in)))
        .output(ImmutableList.of(Content.fromParts(Part.fromText(out))))
        .build();
  }

  @Test
  public void processLlmRequest_withInlineExamples_appendsFewShot() {
    ExampleTool tool = ExampleTool.builder().addExample(makeExample("qin", "qout")).build();

    InvocationContext ctx = buildInvocationContext();
    LlmRequest.Builder builder = LlmRequest.builder().model("gemini-2.0-flash");

    tool.processLlmRequest(builder, ToolContext.builder(ctx).build()).blockingAwait();
    LlmRequest updated = builder.build();

    assertThat(updated.getSystemInstructions()).isNotEmpty();
    String si = String.join("\n", updated.getSystemInstructions());
    assertThat(si).contains("Begin few-shot");
    assertThat(si).contains("qin");
    assertThat(si).contains("qout");
  }

  @Test
  public void processLlmRequest_withProvider_appendsFewShot() {
    ExampleTool tool = ExampleTool.builder().setExampleProvider(ProviderHolder.EXAMPLES).build();

    InvocationContext ctx = buildInvocationContext();
    LlmRequest.Builder builder = LlmRequest.builder().model("gemini-2.0-flash");

    tool.processLlmRequest(builder, ToolContext.builder(ctx).build()).blockingAwait();
    LlmRequest updated = builder.build();

    assertThat(updated.getSystemInstructions()).isNotEmpty();
    String si = String.join("\n", updated.getSystemInstructions());
    assertThat(si).contains("Begin few-shot");
    assertThat(si).contains("qin");
    assertThat(si).contains("qout");
  }

  @Test
  public void processLlmRequest_withEmptyUserContent_doesNotAppendFewShot() {
    ExampleTool tool = ExampleTool.builder().addExample(makeExample("qin", "qout")).build();
    InvocationContext ctxWithContent = buildInvocationContext();
    InvocationContext ctx =
        InvocationContext.builder()
            .invocationId(ctxWithContent.invocationId())
            .agent(ctxWithContent.agent())
            .session(ctxWithContent.session())
            .sessionService(ctxWithContent.sessionService())
            .userContent(Content.fromParts(Part.fromText("")))
            .runConfig(ctxWithContent.runConfig())
            .build();
    LlmRequest.Builder builder = LlmRequest.builder().model("gemini-2.0-flash");

    tool.processLlmRequest(builder, ToolContext.builder(ctx).build()).blockingAwait();
    LlmRequest updated = builder.build();

    assertThat(updated.getSystemInstructions()).isEmpty();
  }

  @Test
  public void fromConfig_withInlineExamples_buildsTool() throws Exception {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // args.examples = [{ input: {parts:[{text:q}]}, output:[{parts:[{text:a}]}] }]
    args.setAdditionalProperty(
        "examples",
        ImmutableList.of(
            ImmutableMap.of(
                "input", Content.fromParts(Part.fromText("q")),
                "output", ImmutableList.of(Content.fromParts(Part.fromText("a"))))));

    ExampleTool tool = ExampleTool.fromConfig(args);
    InvocationContext ctx = buildInvocationContext();
    LlmRequest.Builder builder = LlmRequest.builder().model("gemini-2.0-flash");
    tool.processLlmRequest(builder, ToolContext.builder(ctx).build()).blockingAwait();

    String si = String.join("\n", builder.build().getSystemInstructions());
    assertThat(si).contains("q");
    assertThat(si).contains("a");
  }

  /** Holder for a provider referenced via ClassName.FIELD reflection. */
  static final class ProviderHolder {
    public static final BaseExampleProvider EXAMPLES =
        (unusedQuery) -> ImmutableList.of(makeExample("qin", "qout"));

    private ProviderHolder() {}
  }

  @Test
  public void fromConfig_withProviderReference_buildsTool() throws Exception {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    args.setAdditionalProperty(
        "examples", ExampleToolTest.ProviderHolder.class.getName() + ".EXAMPLES");

    ExampleTool tool = ExampleTool.fromConfig(args);
    InvocationContext ctx = buildInvocationContext();
    LlmRequest.Builder builder = LlmRequest.builder().model("gemini-2.0-flash");
    tool.processLlmRequest(builder, ToolContext.builder(ctx).build()).blockingAwait();

    String si = String.join("\n", builder.build().getSystemInstructions());
    assertThat(si).contains("Begin few-shot");
    assertThat(si).contains("qin");
    assertThat(si).contains("qout");
  }

  @Test
  public void fromConfig_withNonMapExampleEntry_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Create a list with a non-Map entry (e.g., a String) to trigger line 121
    args.setAdditionalProperty("examples", ImmutableList.of("not a map"));

    assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));
  }

  @Test
  public void fromConfig_withUnsupportedExamplesType_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Use an Integer instead of String or List to trigger line 149
    args.setAdditionalProperty("examples", 123);

    assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));
  }

  @Test
  public void fromConfig_withInvalidProviderReference_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Provider reference without a dot to trigger line 162
    args.setAdditionalProperty("examples", "InvalidProviderRef");

    ConfigurationException ex =
        assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));

    assertThat(ex).hasMessageThat().contains("Invalid example provider reference");
    assertThat(ex).hasMessageThat().contains("InvalidProviderRef");
    assertThat(ex).hasMessageThat().contains("Expected ClassName.FIELD");
  }

  @Test
  public void fromConfig_withNullArgs_throwsConfigurationException() {
    ConfigurationException ex =
        assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(null));

    assertThat(ex).hasMessageThat().contains("ExampleTool requires 'examples' argument");
  }

  @Test
  public void fromConfig_withEmptyArgs_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Empty args map triggers line 103 (isEmpty() check)

    ConfigurationException ex =
        assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));

    assertThat(ex).hasMessageThat().contains("ExampleTool requires 'examples' argument");
  }

  @Test
  public void fromConfig_withMissingExamplesKey_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Add some other key but not 'examples' to trigger line 107
    args.setAdditionalProperty("someOtherKey", "someValue");

    assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));
  }

  @Test
  public void fromConfig_withExampleMissingInput_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Example with only output, missing input
    args.setAdditionalProperty(
        "examples",
        ImmutableList.of(
            ImmutableMap.of(
                "output", ImmutableList.of(Content.fromParts(Part.fromText("answer"))))));

    assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));
  }

  @Test
  public void fromConfig_withExampleMissingOutput_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Example with only input, missing output
    args.setAdditionalProperty(
        "examples",
        ImmutableList.of(ImmutableMap.of("input", Content.fromParts(Part.fromText("question")))));

    assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));
  }

  @Test
  public void fromConfig_withNonStaticProviderField_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Reference to a non-static field
    args.setAdditionalProperty(
        "examples", ExampleToolTest.NonStaticProviderHolder.class.getName() + ".INSTANCE");

    ConfigurationException ex =
        assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));

    assertThat(ex).hasMessageThat().contains("is not static");
  }

  @Test
  public void fromConfig_withNonExistentProviderField_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Reference to a field that doesn't exist
    args.setAdditionalProperty(
        "examples", ExampleToolTest.ProviderHolder.class.getName() + ".NONEXISTENT");

    ConfigurationException ex =
        assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));

    assertThat(ex).hasMessageThat().contains("Field 'NONEXISTENT' not found");
  }

  @Test
  public void fromConfig_withNonExistentProviderClass_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Reference to a class that doesn't exist
    args.setAdditionalProperty("examples", "com.nonexistent.Class.FIELD");

    ConfigurationException ex =
        assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));

    assertThat(ex).hasMessageThat().contains("Example provider class not found");
  }

  @Test
  public void fromConfig_withWrongTypeProviderField_throwsConfigurationException() {
    BaseTool.ToolArgsConfig args = new BaseTool.ToolArgsConfig();
    // Reference to a field that is not a BaseExampleProvider
    args.setAdditionalProperty(
        "examples", ExampleToolTest.WrongTypeProviderHolder.class.getName() + ".NOT_A_PROVIDER");

    ConfigurationException ex =
        assertThrows(ConfigurationException.class, () -> ExampleTool.fromConfig(args));

    assertThat(ex).hasMessageThat().contains("is not a BaseExampleProvider");
  }

  /** Holder with non-static field for testing. */
  static final class NonStaticProviderHolder {
    @SuppressWarnings("ConstantField") // Intentionally non-static for testing
    public final BaseExampleProvider INSTANCE =
        (unusedQuery) -> ImmutableList.of(makeExample("q", "a"));

    private NonStaticProviderHolder() {}
  }

  /** Holder with wrong type field for testing. */
  static final class WrongTypeProviderHolder {
    public static final String NOT_A_PROVIDER = "This is not a provider";

    private WrongTypeProviderHolder() {}
  }
}
