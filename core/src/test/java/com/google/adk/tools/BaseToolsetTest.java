package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BaseToolsetTest {

  @Test
  public void testGetTools() {
    BaseTool mockTool1 = mock(BaseTool.class);
    BaseTool mockTool2 = mock(BaseTool.class);
    ReadonlyContext mockContext = mock(ReadonlyContext.class);

    BaseToolset toolset =
        new BaseToolset() {
          @Override
          public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
            return Flowable.just(mockTool1, mockTool2);
          }

          @Override
          public void close() throws Exception {}
        };

    List<BaseTool> tools = toolset.getTools(mockContext).toList().blockingGet();
    assertThat(tools).containsExactly(mockTool1, mockTool2);
  }

  @Test
  public void testProcessLlmRequest_defaultIsNoOp() {
    BaseToolset toolset =
        new BaseToolset() {
          @Override
          public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
            return Flowable.empty();
          }

          @Override
          public void close() throws Exception {}
        };

    LlmRequest.Builder builder =
        LlmRequest.builder().model("test-model").config(GenerateContentConfig.builder().build());
    ToolContext toolContext = mock(ToolContext.class);

    // Default implementation should complete without error
    toolset.processLlmRequest(builder, toolContext).blockingAwait();

    // Request should be unchanged
    LlmRequest request = builder.build();
    assertThat(request.model()).hasValue("test-model");
  }

  @Test
  public void testProcessLlmRequest_canBeOverridden() {
    AtomicBoolean called = new AtomicBoolean(false);

    BaseToolset toolset =
        new BaseToolset() {
          @Override
          public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
            return Flowable.empty();
          }

          @Override
          public Completable processLlmRequest(
              LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
            return Completable.fromAction(
                () -> {
                  called.set(true);
                  llmRequestBuilder.appendInstructions(List.of("Custom toolset instruction"));
                });
          }

          @Override
          public void close() throws Exception {}
        };

    LlmRequest.Builder builder =
        LlmRequest.builder()
            .model("test-model")
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder().parts(List.of(Part.fromText("original"))).build())
                    .build());
    ToolContext toolContext = mock(ToolContext.class);

    toolset.processLlmRequest(builder, toolContext).blockingAwait();

    assertThat(called.get()).isTrue();
    LlmRequest request = builder.build();
    List<String> instructions = request.getSystemInstructions();
    assertThat(instructions.stream().anyMatch(i -> i.contains("Custom toolset instruction")))
        .isTrue();
  }
}
