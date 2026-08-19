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
import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgent;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
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
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
            .response(ImmutableMap.of("field1", "rawResponse"))
            .build();
    Event event =
        Event.builder()
            .actions(
                EventActions.builder()
                    .setModelResponse(ImmutableMap.of("field1", "validatedValue"))
                    .build())
            .content(
                Content.builder()
                    .parts(Part.builder().functionResponse(fr).build())
                    .role("model")
                    .build())
            .build();

    // The result must come from the validated response on the event actions, not from the
    // function response content.
    assertThat(OutputSchema.getStructuredModelResponse(event))
        .hasValue("{\"field1\":\"validatedValue\"}");
  }

  @Test
  public void getStructuredModelResponse_withValidationFeedback_returnsEmpty() {
    FunctionResponse fr =
        FunctionResponse.builder()
            .name(SetModelResponseTool.NAME)
            .response(
                ImmutableMap.of(
                    "error",
                    "Validation Error found: field1 is required. Fix the errors and call it again."))
            .build();
    Event event =
        Event.builder()
            .content(
                Content.builder()
                    .parts(Part.builder().functionResponse(fr).build())
                    .role("user")
                    .build())
            .build();

    assertThat(OutputSchema.getStructuredModelResponse(event)).isEmpty();
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

  @Test
  public void run_invalidThenValidSetModelResponse_emitsFeedbackThenFinalResponse() {
    Content invalidCall =
        Content.fromParts(Part.fromFunctionCall(SetModelResponseTool.NAME, ImmutableMap.of()));
    Content validCall =
        Content.fromParts(
            Part.fromFunctionCall(SetModelResponseTool.NAME, ImmutableMap.of("field1", "value1")));
    TestLlm testLlm = createTestLlm(createLlmResponse(invalidCall), createLlmResponse(validCall));
    InvocationContext invocationContext = createInvocationContext(createTestAgent(testLlm));
    // Registers set_model_response on the request the same way OutputSchema.processRequest does,
    // without the model-name gating which is covered separately above.
    RequestProcessor injectSetModelResponseTool =
        (context, request) -> {
          LlmRequest.Builder builder = request.toBuilder();
          return new SetModelResponseTool(TEST_OUTPUT_SCHEMA)
              .processLlmRequest(builder, ToolContext.builder(context).build())
              .andThen(
                  Single.fromCallable(
                      () -> RequestProcessingResult.create(builder.build(), ImmutableList.of())));
        };
    BaseLlmFlow flow =
        new BaseLlmFlow(
            ImmutableList.of(injectSetModelResponseTool), ImmutableList.of(), Optional.empty()) {};

    List<Event> events = flow.run(invocationContext).toList().blockingGet();

    // The invalid call must trigger a second LLM call (the retry); the tool must be declared on
    // both requests. History assembly for the retry request is the Contents processor's own
    // responsibility, covered by ContentsTest.
    assertThat(testLlm.getRequests()).hasSize(2);
    assertThat(testLlm.getRequests().get(0).tools()).containsKey(SetModelResponseTool.NAME);
    assertThat(testLlm.getRequests().get(1).tools()).containsKey(SetModelResponseTool.NAME);

    // Invalid call produces only the feedback function response (no promoted final event), the
    // corrected call produces the validated function response plus the final model response.
    assertThat(events).hasSize(5);

    Event feedbackEvent = events.get(1);
    Map<String, Object> feedback = feedbackEvent.functionResponses().get(0).response().get();
    assertThat(feedback).containsKey("error");
    assertThat((String) feedback.get("error")).contains("field1");
    assertThat(feedbackEvent.actions().setModelResponse()).isEmpty();

    Event validatedEvent = events.get(3);
    assertThat(validatedEvent.functionResponses().get(0).response().get())
        .containsExactly("field1", "value1");
    assertThat(validatedEvent.actions().setModelResponse())
        .hasValue(ImmutableMap.of("field1", "value1"));

    Event finalEvent = events.get(4);
    assertThat(finalEvent.functionCalls()).isEmpty();
    assertThat(finalEvent.functionResponses()).isEmpty();
    assertThat(finalEvent.content().get().role()).hasValue("model");
    assertThat(finalEvent.content().get().parts().get().get(0).text())
        .hasValue("{\"field1\":\"value1\"}");
  }

  @Test
  public void runner_invalidSetModelResponse_feedbackIsSentBackToModel() {
    Content invalidCall =
        Content.fromParts(Part.fromFunctionCall(SetModelResponseTool.NAME, ImmutableMap.of()));
    Content validCall =
        Content.fromParts(
            Part.fromFunctionCall(SetModelResponseTool.NAME, ImmutableMap.of("field1", "value1")));
    TestLlm scriptedLlm =
        createTestLlm(createLlmResponse(invalidCall), createLlmResponse(validCall));
    // The output-schema workaround only activates for models that cannot combine tools with an
    // output schema, so the scripted TestLlm is wrapped under a gemini-2 model name.
    BaseLlm gemini2NamedLlm =
        new BaseLlm("gemini-2.0-flash") {
          @Override
          public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
            return scriptedLlm.generateContent(llmRequest, stream);
          }

          @Override
          public BaseLlmConnection connect(LlmRequest llmRequest) {
            return scriptedLlm.connect(llmRequest);
          }
        };
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(gemini2NamedLlm)
            .outputSchema(TEST_OUTPUT_SCHEMA)
            .tools(ImmutableList.of(new TestTool()))
            .build();
    InMemoryRunner runner = new InMemoryRunner(agent, "test-app");
    Session session = runner.sessionService().createSession("test-app", "user").blockingGet();

    List<Event> events =
        runner
            .runAsync("user", session.id(), Content.fromParts(Part.fromText("hello")))
            .toList()
            .blockingGet();

    // The retry request must carry the validation feedback from the first call back to the model:
    // the feedback names the missing required field, tying it to the first call's failure.
    assertThat(scriptedLlm.getRequests()).hasSize(2);
    boolean feedbackSentBack =
        scriptedLlm.getRequests().get(1).contents().stream()
            .flatMap(content -> content.parts().orElse(ImmutableList.of()).stream())
            .map(Part::functionResponse)
            .flatMap(Optional::stream)
            .anyMatch(
                fr ->
                    Objects.equals(fr.name().orElse(""), SetModelResponseTool.NAME)
                        && fr.response().orElse(ImmutableMap.of()).get("error")
                            instanceof String error
                        && error.contains("Validation Error found")
                        && error.contains("field1"));
    assertThat(feedbackSentBack).isTrue();

    // Only the corrected, validated response becomes the final structured output.
    Event finalEvent = events.get(events.size() - 1);
    assertThat(finalEvent.content().get().parts().get().get(0).text())
        .hasValue("{\"field1\":\"value1\"}");
  }
}
