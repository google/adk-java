package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GoogleMaps;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.GoogleSearchRetrieval;
import com.google.genai.types.Tool;
import com.google.genai.types.ToolCodeExecution;
import com.google.genai.types.UrlContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

// TODO(b/410859954): Cover more of the behavior of the default processLlmRequest
@RunWith(JUnit4.class)
public final class BaseToolTest {

  @Test
  public void processLlmRequestNoDeclarationReturnsSameRequest() {
    BaseTool tool =
        new BaseTool("test_tool", "test_description") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.empty();
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            return Single.just(null);
          }
        };
    LlmRequest llmRequest = LlmRequest.builder().model("Senatus Populusque Romanus").build();
    LlmRequest.Builder llmRequestBuilder = llmRequest.toBuilder();
    Completable unused = tool.processLlmRequest(llmRequestBuilder, /* toolContext= */ null);
    assertThat(llmRequestBuilder.build()).isEqualTo(llmRequest);
  }

  @Test
  public void processLlmRequestWithDeclarationAddsToolToConfig() {
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder().name("test_function").build();
    BaseTool tool =
        new BaseTool("test_tool", "test_description") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(functionDeclaration);
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            return Single.just(null);
          }
        };
    LlmRequest llmRequest = LlmRequest.builder().build();
    LlmRequest.Builder llmRequestBuilder = llmRequest.toBuilder();
    Completable unused = tool.processLlmRequest(llmRequestBuilder, /* toolContext= */ null);
    LlmRequest updatedLlmRequest = llmRequestBuilder.build();
    assertThat(updatedLlmRequest.config().get().tools().get())
        .containsExactly(
            Tool.builder().functionDeclarations(ImmutableList.of(functionDeclaration)).build());
  }

  @Test
  public void processLlmRequestWithExistingToolMergesFunctionDeclarations() {
    FunctionDeclaration functionDeclaration1 =
        FunctionDeclaration.builder().name("test_function_1").build();
    FunctionDeclaration functionDeclaration2 =
        FunctionDeclaration.builder().name("test_function_2").build();
    BaseTool tool =
        new BaseTool("test_tool", "test_description") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(functionDeclaration2);
          }

          @Override
          public Single<Map<String, Object>> runAsync(
              Map<String, Object> args, ToolContext toolContext) {
            return Single.just(null);
          }
        };
    LlmRequest llmRequest =
        LlmRequest.builder()
            .config(
                GenerateContentConfig.builder()
                    .tools(
                        ImmutableList.of(
                            Tool.builder()
                                .functionDeclarations(ImmutableList.of(functionDeclaration1))
                                .build()))
                    .build())
            .build();
    LlmRequest.Builder llmRequestBuilder = llmRequest.toBuilder();
    Completable unused = tool.processLlmRequest(llmRequestBuilder, /* toolContext= */ null);
    LlmRequest updatedLlmRequest = llmRequestBuilder.build();
    assertThat(llmRequest.config().get().tools().get())
        .containsExactly(
            Tool.builder().functionDeclarations(ImmutableList.of(functionDeclaration1)).build());
    assertThat(updatedLlmRequest.config().get().tools().get())
        .containsExactly(
            Tool.builder()
                .functionDeclarations(ImmutableList.of(functionDeclaration1, functionDeclaration2))
                .build());
  }

  @Test
  public void processLlmRequestWithGoogleSearchToolAddsToolToConfig() {
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder().name("test_function").build();
    GoogleSearchTool googleSearchTool = new GoogleSearchTool();
    LlmRequest llmRequest =
        LlmRequest.builder()
            .config(
                GenerateContentConfig.builder()
                    .tools(
                        ImmutableList.of(
                            Tool.builder()
                                .functionDeclarations(ImmutableList.of(functionDeclaration))
                                .build()))
                    .build())
            .model("gemini-2")
            .build();
    LlmRequest.Builder llmRequestBuilder = llmRequest.toBuilder();
    Completable unused =
        googleSearchTool.processLlmRequest(llmRequestBuilder, /* toolContext= */ null);
    LlmRequest updatedLlmRequest = llmRequestBuilder.build();
    assertThat(updatedLlmRequest.config()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools().get())
        .containsExactly(
            Tool.builder().functionDeclarations(ImmutableList.of(functionDeclaration)).build(),
            Tool.builder().googleSearch(GoogleSearch.builder().build()).build());
  }

  @Test
  public void processLlmRequestWithGoogleSearchRetrievalToolAddsToolToConfig() {
    GoogleSearchTool googleSearchTool = new GoogleSearchTool();
    LlmRequest llmRequest =
        LlmRequest.builder()
            .config(GenerateContentConfig.builder().build())
            .model("gemini-1")
            .build();
    LlmRequest.Builder llmRequestBuilder = llmRequest.toBuilder();
    Completable unused =
        googleSearchTool.processLlmRequest(llmRequestBuilder, /* toolContext= */ null);
    LlmRequest updatedLlmRequest = llmRequestBuilder.build();
    assertThat(updatedLlmRequest.config()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools().get())
        .containsExactly(
            Tool.builder().googleSearchRetrieval(GoogleSearchRetrieval.builder().build()).build());
  }

  @Test
  public void processLlmRequestWithUrlContextToolAddsToolToConfig() {
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder().name("test_function").build();
    UrlContextTool urlContextTool = new UrlContextTool();
    LlmRequest llmRequest =
        LlmRequest.builder()
            .config(
                GenerateContentConfig.builder()
                    .tools(
                        ImmutableList.of(
                            Tool.builder()
                                .functionDeclarations(ImmutableList.of(functionDeclaration))
                                .build()))
                    .build())
            .model("gemini-2")
            .build();
    LlmRequest.Builder llmRequestBuilder = llmRequest.toBuilder();
    Completable unused =
        urlContextTool.processLlmRequest(llmRequestBuilder, /* toolContext= */ null);
    LlmRequest updatedLlmRequest = llmRequestBuilder.build();
    assertThat(updatedLlmRequest.config()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools().get())
        .containsExactly(
            Tool.builder().functionDeclarations(ImmutableList.of(functionDeclaration)).build(),
            Tool.builder().urlContext(UrlContext.builder().build()).build());
  }

  @Test
  public void processLlmRequestWithBuiltInCodeExecutionToolAddsToolToConfig() {
    BuiltInCodeExecutionTool builtInCodeExecutionTool = new BuiltInCodeExecutionTool();
    LlmRequest llmRequest =
        LlmRequest.builder()
            .config(GenerateContentConfig.builder().build())
            .model("gemini-2")
            .build();
    LlmRequest.Builder llmRequestBuilder = llmRequest.toBuilder();
    Completable unused =
        builtInCodeExecutionTool.processLlmRequest(llmRequestBuilder, /* toolContext= */ null);
    LlmRequest updatedLlmRequest = llmRequestBuilder.build();
    assertThat(updatedLlmRequest.config()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools().get())
        .containsExactly(Tool.builder().codeExecution(ToolCodeExecution.builder().build()).build());
  }

  @Test
  public void processLlmRequestWithGoogleMapsToolAddsToolToConfig() {
    GoogleMapsTool googleMapsTool = new GoogleMapsTool();
    LlmRequest llmRequest =
        LlmRequest.builder()
            .config(GenerateContentConfig.builder().build())
            .model("gemini-2")
            .build();
    LlmRequest.Builder llmRequestBuilder = llmRequest.toBuilder();
    Completable unused =
        googleMapsTool.processLlmRequest(llmRequestBuilder, /* toolContext= */ null);
    LlmRequest updatedLlmRequest = llmRequestBuilder.build();
    assertThat(updatedLlmRequest.config()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools()).isPresent();
    assertThat(updatedLlmRequest.config().get().tools().get())
        .containsExactly(Tool.builder().googleMaps(GoogleMaps.builder().build()).build());
  }
}
