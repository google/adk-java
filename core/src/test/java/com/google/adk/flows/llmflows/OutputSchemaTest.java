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

package com.google.adk.flows.llmflows;

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.SetModelResponseTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class OutputSchemaTest {

  private static final Schema TEST_OUTPUT_SCHEMA =
      Schema.builder()
          .type("OBJECT")
          .properties(ImmutableMap.of("field1", Schema.builder().type("STRING").build()))
          .required(ImmutableList.of("field1"))
          .build();

  private OutputSchema outputSchemaProcessor;
  private TestLlm testLlm;
  private LlmRequest initialRequest;

  @Before
  public void setUp() {
    outputSchemaProcessor = new OutputSchema();
    testLlm = createTestLlm(LlmResponse.builder().build());
    initialRequest = LlmRequest.builder().model("gemini-2.0-pro").build();
  }

  public static class TestTool extends BaseTool {
    public TestTool() {
      super("test_tool", "test description");
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
      return Single.just(ImmutableMap.of());
    }
  }

  @Test
  public void processRequest_noOutputSchema_doesNothing() {
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(testLlm)
            .tools(ImmutableList.of(new TestTool()))
            .build();
    InvocationContext context = createInvocationContext(agent);

    RequestProcessingResult result =
        outputSchemaProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest()).isEqualTo(initialRequest);
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_noTools_doesNothing() {
    LlmAgent agent =
        LlmAgent.builder().name("agent").model(testLlm).outputSchema(TEST_OUTPUT_SCHEMA).build();
    InvocationContext context = createInvocationContext(agent);

    RequestProcessingResult result =
        outputSchemaProcessor.processRequest(context, initialRequest).blockingGet();

    assertThat(result.updatedRequest()).isEqualTo(initialRequest);
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void processRequest_withOutputSchemaAndTools_addsSetModelResponseTool() {
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(testLlm)
            .outputSchema(TEST_OUTPUT_SCHEMA)
            .tools(ImmutableList.of(new TestTool()))
            .build();
    InvocationContext context = createInvocationContext(agent);
    LlmRequest requestWithTools =
        LlmRequest.builder()
            .model("gemini-2.5-pro")
            .tools(ImmutableMap.of("test_tool", new TestTool()))
            .build();

    RequestProcessingResult result =
        outputSchemaProcessor.processRequest(context, requestWithTools).blockingGet();

    LlmRequest updatedRequest = result.updatedRequest();
    assertThat(updatedRequest.tools()).hasSize(2);
    assertThat(
            updatedRequest.tools().values().stream()
                .anyMatch(t -> t instanceof SetModelResponseTool))
        .isTrue();
    assertThat(updatedRequest.tools().values().stream().anyMatch(t -> t.name().equals("test_tool")))
        .isTrue();
    assertThat(updatedRequest.getSystemInstructions()).isNotEmpty();
    assertThat(updatedRequest.getSystemInstructions().get(0))
        .contains("you must provide your final response using the set_model_response tool");
    assertThat(result.events()).isEmpty();
  }

  @Test
  public void getStructuredModelResponse_withSetModelResponse_returnsJson() {
    FunctionResponse fr =
        FunctionResponse.builder()
            .name(SetModelResponseTool.NAME)
            .response(ImmutableMap.of("field1", "value1"))
            .build();
    Event event =
        Event.builder()
            .content(
                Content.builder()
                    .parts(Part.builder().functionResponse(fr).build())
                    .role("model")
                    .build())
            .build();

    assertThat(OutputSchema.getStructuredModelResponse(event)).hasValue("{\"field1\":\"value1\"}");
  }

  @Test
  public void getStructuredModelResponse_withoutSetModelResponse_returnsEmpty() {
    FunctionResponse fr =
        FunctionResponse.builder()
            .name("other_tool")
            .response(ImmutableMap.of("field1", "value1"))
            .build();
    Event event =
        Event.builder()
            .content(
                Content.builder()
                    .parts(Part.builder().functionResponse(fr).build())
                    .role("model")
                    .build())
            .build();

    assertThat(OutputSchema.getStructuredModelResponse(event)).isEmpty();
  }

  @Test
  public void createFinalModelResponseEvent_createsModelResponseEvent() {
    LlmAgent agent = LlmAgent.builder().name("agent").model(testLlm).build();
    InvocationContext context = createInvocationContext(agent);
    String jsonResponse = "{\"field1\":\"value1\"}";

    Event event = OutputSchema.createFinalModelResponseEvent(context, jsonResponse);

    assertThat(event.invocationId()).isEqualTo(context.invocationId());
    assertThat(event.author()).isEqualTo("agent");
    assertThat(event.content().get().role()).hasValue("model");
    assertThat(event.content().get().parts().get()).containsExactly(Part.fromText(jsonResponse));
  }
}
