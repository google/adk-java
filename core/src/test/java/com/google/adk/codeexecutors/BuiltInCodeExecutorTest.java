package com.google.adk.codeexecutors;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.models.LlmRequest;
import com.google.genai.types.Tool;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BuiltInCodeExecutorTest {

  @Test
  public void executeCode_throwsUnsupportedOperationException() {
    BuiltInCodeExecutor executor = new BuiltInCodeExecutor();
    assertThrows(UnsupportedOperationException.class, () -> executor.executeCode(null, null));
  }

  @Test
  public void processLlmRequest_withGemini2_addsCodeExecutionTool() {
    BuiltInCodeExecutor executor = new BuiltInCodeExecutor();
    LlmRequest.Builder requestBuilder = LlmRequest.builder().model("gemini-2.5-flash");

    executor.processLlmRequest(requestBuilder);

    List<Tool> tools = requestBuilder.build().config().get().tools().get();
    assertThat(tools).hasSize(1);
    assertThat(tools.get(0).codeExecution()).isPresent();
  }

  @Test
  public void processLlmRequest_withGemini3_addsCodeExecutionTool() {
    BuiltInCodeExecutor executor = new BuiltInCodeExecutor();
    LlmRequest.Builder requestBuilder = LlmRequest.builder().model("gemini-3.0-pro");

    executor.processLlmRequest(requestBuilder);

    List<Tool> tools = requestBuilder.build().config().get().tools().get();
    assertThat(tools).hasSize(1);
    assertThat(tools.get(0).codeExecution()).isPresent();
  }

  @Test
  public void processLlmRequest_withGemini1_throwsException() {
    BuiltInCodeExecutor executor = new BuiltInCodeExecutor();
    LlmRequest.Builder requestBuilder = LlmRequest.builder().model("gemini-1.5-pro");

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> executor.processLlmRequest(requestBuilder));

    assertThat(exception)
        .hasMessageThat()
        .contains("Gemini code execution tool is not supported for model gemini-1.5-pro");
  }

  @Test
  public void processLlmRequest_withoutModel_throwsException() {
    BuiltInCodeExecutor executor = new BuiltInCodeExecutor();
    LlmRequest.Builder requestBuilder = LlmRequest.builder();

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> executor.processLlmRequest(requestBuilder));

    assertThat(exception)
        .hasMessageThat()
        .contains("Gemini code execution tool is not supported for model");
  }
}
