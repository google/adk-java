package com.google.adk.tools.retrieval;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.ToolContext;
import com.google.cloud.aiplatform.v1.RagContexts;
import com.google.cloud.aiplatform.v1.RagQuery;
import com.google.cloud.aiplatform.v1.RetrieveContextsRequest;
import com.google.cloud.aiplatform.v1.RetrieveContextsRequest.VertexRagStore.RagResource;
import com.google.cloud.aiplatform.v1.RetrieveContextsResponse;
import com.google.cloud.aiplatform.v1.VertexRagServiceClient;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Retrieval;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.VertexRagStore;
import com.google.genai.types.VertexRagStoreRagResource;
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
public final class VertexAiRagRetrievalTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock private VertexRagServiceClient vertexRagServiceClient;

  private InMemorySessionService sessionService;
  private LlmAgent agent;

  @Before
  public void setUp() {
    sessionService = new InMemorySessionService();
    agent = LlmAgent.builder().name("test-agent").build();
  }

  @Test
  public void runAsync_withResults_returnsContexts() throws Exception {
    ImmutableList<RagResource> ragResources =
        ImmutableList.of(RagResource.newBuilder().setRagCorpus("corpus1").build());
    Double vectorDistanceThreshold = 0.5;
    VertexAiRagRetrieval tool =
        new VertexAiRagRetrieval(
            "testTool",
            "test description",
            vertexRagServiceClient,
            "projects/test-project/locations/us-central1",
            ragResources,
            vectorDistanceThreshold);
    String query = "test query";
    ToolContext toolContext = buildToolContext();
    RetrieveContextsRequest expectedRequest =
        RetrieveContextsRequest.newBuilder()
            .setParent("projects/test-project/locations/us-central1")
            .setQuery(RagQuery.newBuilder().setText(query))
            .setVertexRagStore(
                com.google.cloud.aiplatform.v1.RetrieveContextsRequest.VertexRagStore.newBuilder()
                    .addAllRagResources(ragResources)
                    .setVectorDistanceThreshold(vectorDistanceThreshold))
            .build();
    when(vertexRagServiceClient.retrieveContexts(eq(expectedRequest)))
        .thenReturn(
            RetrieveContextsResponse.newBuilder()
                .setContexts(
                    RagContexts.newBuilder()
                        .addContexts(RagContexts.Context.newBuilder().setText("context1"))
                        .addContexts(RagContexts.Context.newBuilder().setText("context2")))
                .build());

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of("query", query), toolContext).blockingGet();

    assertThat(result).containsExactly("response", ImmutableList.of("context1", "context2"));
    verify(vertexRagServiceClient).retrieveContexts(eq(expectedRequest));
  }

  @Test
  public void runAsync_noResults_returnsNoResultFoundMessage() throws Exception {
    ImmutableList<RagResource> ragResources =
        ImmutableList.of(RagResource.newBuilder().setRagCorpus("corpus1").build());
    Double vectorDistanceThreshold = 0.5;
    VertexAiRagRetrieval tool =
        new VertexAiRagRetrieval(
            "testTool",
            "test description",
            vertexRagServiceClient,
            "projects/test-project/locations/us-central1",
            ragResources,
            vectorDistanceThreshold);
    String query = "test query";
    ToolContext toolContext = buildToolContext();
    RetrieveContextsRequest expectedRequest =
        RetrieveContextsRequest.newBuilder()
            .setParent("projects/test-project/locations/us-central1")
            .setQuery(RagQuery.newBuilder().setText(query))
            .setVertexRagStore(
                com.google.cloud.aiplatform.v1.RetrieveContextsRequest.VertexRagStore.newBuilder()
                    .addAllRagResources(ragResources)
                    .setVectorDistanceThreshold(vectorDistanceThreshold))
            .build();
    when(vertexRagServiceClient.retrieveContexts(eq(expectedRequest)))
        .thenReturn(
            RetrieveContextsResponse.newBuilder()
                .setContexts(RagContexts.getDefaultInstance())
                .build());

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of("query", query), toolContext).blockingGet();

    assertThat(result)
        .containsExactly(
            "response",
            "No matching result found with the config: resources: [rag_corpus: \"corpus1\"\n]");
    verify(vertexRagServiceClient).retrieveContexts(eq(expectedRequest));
  }

  @Test
  public void processLlmRequest_gemini2Model_addVertexRagStoreToConfig() {
    // This test's behavior depends on the GOOGLE_GENAI_USE_VERTEXAI environment variable
    boolean useVertexAi = Boolean.parseBoolean(System.getenv("GOOGLE_GENAI_USE_VERTEXAI"));
    ImmutableList<RagResource> ragResources =
        ImmutableList.of(RagResource.newBuilder().setRagCorpus("corpus1").build());
    Double vectorDistanceThreshold = 0.5;
    VertexAiRagRetrieval tool =
        new VertexAiRagRetrieval(
            "testTool",
            "test description",
            vertexRagServiceClient,
            "projects/test-project/locations/us-central1",
            ragResources,
            vectorDistanceThreshold);
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().model("gemini-2-pro");
    ToolContext toolContext = buildToolContext();

    tool.processLlmRequest(llmRequestBuilder, toolContext).blockingAwait();

    if (useVertexAi) {
      // Assert that VertexRagStore is added to the config
      assertThat(llmRequestBuilder.build().config().get().tools().get())
          .containsExactly(
              Tool.builder()
                  .retrieval(
                      Retrieval.builder()
                          .vertexRagStore(
                              VertexRagStore.builder()
                                  .ragResources(
                                      ImmutableList.of(
                                          VertexRagStoreRagResource.builder()
                                              .ragCorpus("corpus1")
                                              .build()))
                                  .vectorDistanceThreshold(0.5)
                                  .build())
                          .build())
                  .build());
    } else {
      // Assert that the function declaration is added instead
      assertThat(llmRequestBuilder.build().config().get().tools().get())
          .containsExactly(
              Tool.builder()
                  .functionDeclarations(
                      ImmutableList.of(
                          FunctionDeclaration.builder()
                              .name("testTool")
                              .description("test description")
                              .parameters(
                                  Schema.builder()
                                      .properties(
                                          ImmutableMap.of(
                                              "query",
                                              Schema.builder()
                                                  .description("The query to retrieve.")
                                                  .type("STRING")
                                                  .build()))
                                      .type("OBJECT")
                                      .build())
                              .build()))
                  .build());
    }
  }

  @Test
  public void processLlmRequest_otherModel_doNotAddVertexRagStoreToConfig() {
    ImmutableList<RagResource> ragResources =
        ImmutableList.of(RagResource.newBuilder().setRagCorpus("corpus1").build());
    Double vectorDistanceThreshold = 0.5;
    VertexAiRagRetrieval tool =
        new VertexAiRagRetrieval(
            "testTool",
            "test description",
            vertexRagServiceClient,
            "projects/test-project/locations/us-central1",
            ragResources,
            vectorDistanceThreshold);
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder().model("other-model");
    ToolContext toolContext = buildToolContext();
    GenerateContentConfig initialConfig = GenerateContentConfig.builder().build();
    llmRequestBuilder.config(initialConfig);

    tool.processLlmRequest(llmRequestBuilder, toolContext).blockingAwait();

    assertThat(llmRequestBuilder.build().config().get().tools().get())
        .containsExactly(
            Tool.builder()
                .functionDeclarations(
                    ImmutableList.of(
                        FunctionDeclaration.builder()
                            .name("testTool")
                            .description("test description")
                            .parameters(
                                Schema.builder()
                                    .properties(
                                        ImmutableMap.of(
                                            "query",
                                            Schema.builder()
                                                .description("The query to retrieve.")
                                                .type("STRING")
                                                .build()))
                                    .type("OBJECT")
                                    .build())
                            .build()))
                .build());
  }

  private ToolContext buildToolContext() {
    Session session =
        sessionService.createSession("test-app", "test-user", null, "test-session").blockingGet();
    return ToolContext.builder(
            InvocationContext.builder()
                .invocationId(InvocationContext.newInvocationContextId())
                .agent(agent)
                .session(session)
                .sessionService(sessionService)
                .build())
        .build();
  }
}
