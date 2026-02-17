package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Retrieval;
import com.google.genai.types.Tool;
import com.google.genai.types.VertexAISearch;
import com.google.genai.types.VertexAISearchDataStoreSpec;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class VertexAiSearchToolTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Mock InvocationContext invocationContext;
  Session session = Session.builder("test-session").build();

  @Before
  public void setUp() {
    when(invocationContext.session()).thenReturn(session);
  }

  @Test
  public void build_noDataStoreIdOrSearchEngineId_throwsException() {
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> VertexAiSearchTool.builder().build());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("One and only one of dataStoreId or searchEngineId must not be empty.");
  }

  @Test
  public void build_bothDataStoreIdAndSearchEngineId_throwsException() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> VertexAiSearchTool.builder().dataStoreId("ds1").searchEngineId("se1").build());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("One and only one of dataStoreId or searchEngineId must not be empty.");
  }

  @Test
  public void build_dataStoreSpecsWithoutSearchEngineId_throwsException() {
    VertexAISearchDataStoreSpec spec =
        VertexAISearchDataStoreSpec.builder().dataStore("ds1").build();
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                VertexAiSearchTool.builder()
                    .dataStoreId("ds1")
                    .dataStoreSpecs(ImmutableList.of(spec))
                    .build());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("searchEngineId must not be empty if dataStoreSpecs is not empty.");
  }

  @Test
  public void build_emptyDataStoreId_throwsException() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> VertexAiSearchTool.builder().dataStoreId("").build());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("One and only one of dataStoreId or searchEngineId must not be empty.");
  }

  @Test
  public void build_emptySearchEngineId_throwsException() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> VertexAiSearchTool.builder().searchEngineId("").build());
    assertThat(exception)
        .hasMessageThat()
        .isEqualTo("One and only one of dataStoreId or searchEngineId must not be empty.");
  }

  @Test
  public void build_withDataStoreId_succeeds() {
    VertexAiSearchTool tool = VertexAiSearchTool.builder().dataStoreId("ds1").build();
    assertThat(tool.dataStoreId()).hasValue("ds1");
  }

  @Test
  public void build_withSearchEngineId_succeeds() {
    VertexAiSearchTool tool = VertexAiSearchTool.builder().searchEngineId("se1").build();
    assertThat(tool.searchEngineId()).hasValue("se1");
  }

  @Test
  public void build_withSearchEngineIdAndDataStoreSpecs_succeeds() {
    VertexAISearchDataStoreSpec spec =
        VertexAISearchDataStoreSpec.builder().dataStore("ds1").build();
    VertexAiSearchTool tool =
        VertexAiSearchTool.builder()
            .searchEngineId("se1")
            .dataStoreSpecs(ImmutableList.of(spec))
            .build();
    assertThat(tool.searchEngineId()).hasValue("se1");
    assertThat(tool.dataStoreSpecs()).containsExactly(spec);
  }

  @Test
  public void processLlmRequest_addsRetrievalTool() {
    VertexAiSearchTool tool =
        VertexAiSearchTool.builder().searchEngineId("se1").filter("filter1").maxResults(10).build();
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();

    tool.processLlmRequest(llmRequestBuilder, ToolContext.builder(invocationContext).build())
        .blockingAwait();

    LlmRequest llmRequest = llmRequestBuilder.build();
    assertThat(llmRequest.config()).isPresent();
    GenerateContentConfig config = llmRequest.config().get();
    assertThat(config.tools()).isPresent();
    List<Tool> tools = config.tools().get();
    assertThat(tools).hasSize(1);
    Tool retrievalTool = tools.get(0);
    assertThat(retrievalTool.retrieval()).isPresent();
    Retrieval retrieval = retrievalTool.retrieval().get();
    assertThat(retrieval.vertexAiSearch()).isPresent();
    VertexAISearch vertexAiSearch = retrieval.vertexAiSearch().get();
    assertThat(vertexAiSearch.engine()).hasValue("se1");
    assertThat(vertexAiSearch.filter()).hasValue("filter1");
    assertThat(vertexAiSearch.maxResults()).hasValue(10);
  }

  @Test
  public void processLlmRequest_withDataStoreSpecs_addsRetrievalTool() {
    VertexAISearchDataStoreSpec spec =
        VertexAISearchDataStoreSpec.builder().dataStore("ds1").build();
    VertexAiSearchTool tool =
        VertexAiSearchTool.builder()
            .searchEngineId("se1")
            .dataStoreSpecs(ImmutableList.of(spec))
            .build();
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();
    tool.processLlmRequest(llmRequestBuilder, ToolContext.builder(invocationContext).build())
        .blockingAwait();
    LlmRequest llmRequest = llmRequestBuilder.build();
    assertThat(
            llmRequest
                .config()
                .get()
                .tools()
                .get()
                .get(0)
                .retrieval()
                .get()
                .vertexAiSearch()
                .get()
                .dataStoreSpecs()
                .get())
        .containsExactly(spec);
  }

  @Test
  public void processLlmRequest_nonGeminiModel_throwsException() {
    VertexAiSearchTool tool = VertexAiSearchTool.builder().searchEngineId("se1").build();
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().model("other-model");
    tool.processLlmRequest(llmRequestBuilder, ToolContext.builder(invocationContext).build())
        .test()
        .assertError(
            throwable ->
                throwable instanceof IllegalArgumentException
                    && throwable
                        .getMessage()
                        .equals("Vertex AI Search tool is only supported for Gemini models."));
  }
}
