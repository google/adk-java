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

import static com.google.adk.testing.TestUtils.assertEqualIgnoringFunctionIds;
import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.events.Event;
import com.google.adk.models.LlmRegistry;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.Model;
import com.google.adk.testing.TestLlm;
import com.google.adk.testing.TestUtils.EchoTool;
import com.google.adk.tools.Annotations;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.utils.ComponentRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link LlmAgent}. */
@RunWith(JUnit4.class)
public final class LlmAgentTest {

  /** A test tool with multiple methods annotated with @Schema. */
  public static class TestToolWithMethods {
    @Annotations.Schema(name = "method1", description = "This is the first test method.")
    public static String method1(
        @Annotations.Schema(name = "param1", description = "A test parameter") String param1) {
      return "method1 response: " + param1;
    }

    @Annotations.Schema(name = "method2", description = "This is the second test method.")
    public static int method2(
        @Annotations.Schema(name = "param2", description = "Another test parameter") int param2) {
      return param2 * 2;
    }

    // This method is not annotated and should not be picked up
    public void nonToolMethod() {
      // No-op
    }
  }

  /** A public subclass of ComponentRegistry to allow instantiation in the test. */
  public static class TestComponentRegistry extends ComponentRegistry {
    public TestComponentRegistry() {
      super();
    }
  }

  @Test
  public void testResolveClassTools() throws Exception {
    ComponentRegistry originalRegistry = ComponentRegistry.getInstance();
    try {
      ComponentRegistry testRegistry = new TestComponentRegistry();
      ComponentRegistry.setInstance(testRegistry);

      String className = TestToolWithMethods.class.getName();

      // Create and register tools from the test class
      FunctionTool tool1 = FunctionTool.create(TestToolWithMethods.class, "method1");
      FunctionTool tool2 = FunctionTool.create(TestToolWithMethods.class, "method2");
      String tool1Name = className + ".method1";
      String tool2Name = className + ".method2";
      testRegistry.register(tool1Name, tool1);
      testRegistry.register(tool2Name, tool2);

      // Call the method under test
      List<BaseTool> resolvedTools = LlmAgent.resolveClassTools(className);

      // Verify the results
      assertThat(resolvedTools).hasSize(2);
      assertThat(resolvedTools).containsExactly(tool1, tool2);
    } finally {
      // Clean up the registry to avoid side effects on other tests
      ComponentRegistry.setInstance(originalRegistry);
    }
  }

  @Test
  public void testResolveClassTools_withEmptyClassPrefix() throws Exception {
    ComponentRegistry originalRegistry = ComponentRegistry.getInstance();
    try {
      ComponentRegistry testRegistry = new LlmAgentTest.TestComponentRegistry();
      ComponentRegistry.setInstance(testRegistry);

      String className = "com.example.NonExistentClass";

      // Register some unrelated tools to verify they aren't picked up
      FunctionTool unrelatedTool = FunctionTool.create(TestToolWithMethods.class, "method1");
      testRegistry.register("com.other.SomeClass.method", unrelatedTool);
      testRegistry.register("different.package.Tool.function", unrelatedTool);

      // Call the method under test with a class that has no registered tools
      List<BaseTool> resolvedTools = LlmAgent.resolveClassTools(className);

      // Verify no tools are returned
      assertThat(resolvedTools).isEmpty();
    } finally {
      // Clean up the registry to avoid side effects on other tests
      ComponentRegistry.setInstance(originalRegistry);
    }
  }

  @Test
  public void testResolveClassTools_withPartialMatches() throws Exception {
    ComponentRegistry originalRegistry = ComponentRegistry.getInstance();
    try {
      ComponentRegistry testRegistry = new LlmAgentTest.TestComponentRegistry();
      ComponentRegistry.setInstance(testRegistry);

      String className = "com.example.TestClass";

      // Create tools for testing
      FunctionTool correctTool1 = FunctionTool.create(TestToolWithMethods.class, "method1");
      FunctionTool correctTool2 = FunctionTool.create(TestToolWithMethods.class, "method2");
      FunctionTool similarButDifferent = FunctionTool.create(TestToolWithMethods.class, "method1");

      // Register tools with exact matches and partial matches
      testRegistry.register("com.example.TestClass.method1", correctTool1);
      testRegistry.register("com.example.TestClass.method2", correctTool2);
      testRegistry.register(
          "com.example.TestClassExtended.method3", similarButDifferent); // Should not match
      testRegistry.register("com.other.TestClass.method4", similarButDifferent); // Should not match
      testRegistry.register(
          "com.example.TestClass", similarButDifferent); // Exact class name, should not match

      // Call the method under test
      List<BaseTool> resolvedTools = LlmAgent.resolveClassTools(className);

      // Verify only exact prefix matches are returned
      assertThat(resolvedTools).hasSize(2);
      assertThat(resolvedTools).containsExactly(correctTool1, correctTool2);
    } finally {
      // Clean up the registry to avoid side effects on other tests
      ComponentRegistry.setInstance(originalRegistry);
    }
  }

  @Test
  public void testRun_withNoCallbacks() {
    Content modelContent = Content.fromParts(Part.fromText("Real LLM response"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgent(testLlm);
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(getOnlyElement(events).content()).hasValue(modelContent);
  }

  @Test
  public void testRun_withOutputKey_savesState() {
    Content modelContent = Content.fromParts(Part.fromText("Saved output"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).outputKey("myOutput").build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta()).containsEntry("myOutput", "Saved output");
  }

  @Test
  public void testRun_withOutputKey_savesMultiPartState() {
    Content modelContent = Content.fromParts(Part.fromText("Part 1."), Part.fromText(" Part 2."));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).outputKey("myMultiPartOutput").build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta())
        .containsEntry("myMultiPartOutput", "Part 1. Part 2.");
  }

  @Test
  public void testRun_withoutOutputKey_doesNotSaveState() {
    Content modelContent = Content.fromParts(Part.fromText("Some output"));
    TestLlm testLlm = createTestLlm(createLlmResponse(modelContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).content()).hasValue(modelContent);
    assertThat(events.get(0).finalResponse()).isTrue();

    assertThat(events.get(0).actions().stateDelta()).isEmpty();
  }

  @Test
  public void run_withToolsAndMaxSteps_stopsAfterMaxSteps() {
    ImmutableMap<String, Object> echoArgs = ImmutableMap.of("arg", "value");
    Content contentWithFunctionCall =
        Content.fromParts(Part.fromText("text"), Part.fromFunctionCall("echo_tool", echoArgs));
    Content unreachableContent = Content.fromParts(Part.fromText("This should never be returned."));
    TestLlm testLlm =
        createTestLlm(
            createLlmResponse(contentWithFunctionCall),
            createLlmResponse(contentWithFunctionCall),
            createLlmResponse(unreachableContent));
    LlmAgent agent = createTestAgentBuilder(testLlm).tools(new EchoTool()).maxSteps(2).build();
    InvocationContext invocationContext = createInvocationContext(agent);

    List<Event> events = agent.runAsync(invocationContext).toList().blockingGet();

    Content expectedFunctionResponseContent =
        Content.fromParts(
            Part.fromFunctionResponse(
                "echo_tool", ImmutableMap.<String, Object>of("result", echoArgs)));
    assertThat(events).hasSize(4);
    assertEqualIgnoringFunctionIds(events.get(0).content().get(), contentWithFunctionCall);
    assertEqualIgnoringFunctionIds(events.get(1).content().get(), expectedFunctionResponseContent);
    assertEqualIgnoringFunctionIds(events.get(2).content().get(), contentWithFunctionCall);
    assertEqualIgnoringFunctionIds(events.get(3).content().get(), expectedFunctionResponseContent);
  }

  @Test
  public void build_withOutputSchemaAndTools_throwsIllegalArgumentException() {
    BaseTool tool =
        new BaseTool("test_tool", "test_description") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.empty();
          }
        };

    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("status", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("status"))
            .build();

    // Expecting an IllegalArgumentException when building the agent
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LlmAgent.builder() // Use the agent builder directly
                    .name("agent with invalid tool config")
                    .outputSchema(outputSchema) // Set the output schema
                    .tools(ImmutableList.of(tool)) // Set tools (this should cause the error)
                    .build()); // Attempt to build the agent

    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Invalid config for agent agent with invalid tool config: if outputSchema is set, tools"
                + " must be empty");
  }

  @Test
  public void build_withOutputSchemaAndSubAgents_throwsIllegalArgumentException() {
    ImmutableList<BaseAgent> subAgents =
        ImmutableList.of(
            createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
                .name("test_sub_agent")
                .description("test_sub_agent_description")
                .build());

    Schema outputSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(ImmutableMap.of("status", Schema.builder().type("STRING").build()))
            .required(ImmutableList.of("status"))
            .build();

    // Expecting an IllegalArgumentException when building the agent
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                LlmAgent.builder() // Use the agent builder directly
                    .name("agent with invalid tool config")
                    .outputSchema(outputSchema) // Set the output schema
                    .subAgents(subAgents) // Set subAgents (this should cause the error)
                    .build()); // Attempt to build the agent

    assertThat(exception)
        .hasMessageThat()
        .contains(
            "Invalid config for agent agent with invalid tool config: if outputSchema is set,"
                + " subAgents must be empty to disable agent transfer.");
  }

  @Test
  public void testBuild_withNullInstruction_setsInstructionToEmptyString() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction((String) null)
            .build();

    assertThat(agent.instruction()).isEqualTo(new Instruction.Static(""));
  }

  @Test
  public void testCanonicalInstruction_acceptsPlainString() {
    String instruction = "Test static instruction";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction(instruction)
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction);
  }

  @Test
  public void testCanonicalInstruction_providerInstructionInjectsContext() {
    String instruction = "Test provider instruction for invocation: ";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction(
                new Instruction.Provider(
                    context -> Single.just(instruction + context.invocationId())))
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction + invocationContext.invocationId());
  }

  @Test
  public void testBuild_withNullGlobalInstruction_setsGlobalInstructionToEmptyString() {
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction((String) null)
            .build();

    assertThat(agent.globalInstruction()).isEqualTo(new Instruction.Static(""));
  }

  @Test
  public void testCanonicalGlobalInstruction_acceptsPlainString() {
    String instruction = "Test static global instruction";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction(instruction)
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalGlobalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction);
  }

  @Test
  public void testCanonicalGlobalInstruction_providerInstructionInjectsContext() {
    String instruction = "Test provider global instruction for invocation: ";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction(
                new Instruction.Provider(
                    context -> Single.just(instruction + context.invocationId())))
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction =
        agent.canonicalGlobalInstruction(invocationContext).blockingGet().getKey();

    assertThat(canonicalInstruction).isEqualTo(instruction + invocationContext.invocationId());
  }

  @Test
  public void resolveModel_withModelName_resolvesFromRegistry() {
    String modelName = "test-model";
    TestLlm testLlm = createTestLlm(LlmResponse.builder().build());
    LlmRegistry.registerLlm(modelName, (name) -> testLlm);
    LlmAgent agent = createTestAgentBuilder(testLlm).model(modelName).build();
    Model resolvedModel = agent.resolvedModel();

    assertThat(resolvedModel.modelName()).hasValue(modelName);
    assertThat(resolvedModel.model()).hasValue(testLlm);
  }
}
