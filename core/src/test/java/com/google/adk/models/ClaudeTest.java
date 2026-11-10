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

package com.google.adk.models;

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.anthropic.backends.Backend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.OutputTokensDetails;
import com.anthropic.models.messages.RedactedThinkingBlock;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.flows.llmflows.Basic;
import com.google.adk.flows.llmflows.OutputSchema;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.SetModelResponseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FileData;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.ThinkingConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class ClaudeTest {

  private Claude claude;
  private AnthropicClient mockClient;
  private MessageService messageService;
  private Method functionDeclarationToAnthropicToolMethod;

  @Before
  public void setUp() throws Exception {
    mockClient = Mockito.mock(AnthropicClient.class);
    messageService = Mockito.mock(MessageService.class);
    when(mockClient.messages()).thenReturn(messageService);
    claude = new Claude("claude-3-opus", mockClient);

    functionDeclarationToAnthropicToolMethod =
        Claude.class.getDeclaredMethod(
            "functionDeclarationToAnthropicTool", FunctionDeclaration.class);
    functionDeclarationToAnthropicToolMethod.setAccessible(true);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> inputSchemaProperties(Tool tool) {
    JsonValue properties = (JsonValue) tool.inputSchema()._properties();
    return properties.convert(new TypeReference<Map<String, Object>>() {});
  }

  private static Usage usage(long inputTokens, long outputTokens) {
    return Usage.builder()
        .inputTokens(inputTokens)
        .outputTokens(outputTokens)
        .cacheCreation(Optional.empty())
        .cacheCreationInputTokens(Optional.empty())
        .cacheReadInputTokens(Optional.empty())
        .inferenceGeo(Optional.empty())
        .outputTokensDetails(Optional.empty())
        .serverToolUse(Optional.empty())
        .serviceTier(Optional.empty())
        .build();
  }

  private static Message message(ContentBlock... blocks) {
    return message(StopReason.END_TURN, usage(10, 5), blocks);
  }

  private static Message message(StopReason stopReason, Usage usage, ContentBlock... blocks) {
    return Message.builder()
        .id("msg_1")
        .container(Optional.empty())
        .content(ImmutableList.copyOf(blocks))
        .model("claude-3-opus-20240229")
        .stopDetails(Optional.empty())
        .stopReason(stopReason)
        .stopSequence(Optional.empty())
        .usage(usage)
        .build();
  }

  /** Sends {@code request} and returns the params Claude received. */
  private MessageCreateParams sentParams(LlmRequest request) {
    when(messageService.create(any(MessageCreateParams.class)))
        .thenReturn(message(textBlock("ok")));
    claude.generateContent(request, false).blockingFirst();
    ArgumentCaptor<MessageCreateParams> params = ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(messageService).create(params.capture());
    return params.getValue();
  }

  /** Sends {@code contents} in one request and returns the messages Claude received. */
  private List<MessageParam> sentMessages(Content... contents) {
    return sentParams(request(contents)).messages();
  }

  /** Returns the response to a request that Claude answers with {@code reply}. */
  private LlmResponse responseTo(Message reply) {
    when(messageService.create(any(MessageCreateParams.class))).thenReturn(reply);
    return claude.generateContent(request(userText("Hi")), false).blockingFirst();
  }

  /** A signature that is not UTF-8, like Gemini's, so it cannot be one Claude issued. */
  private static byte[] nonUtf8Signature() {
    return new byte[] {(byte) 0xC3, 0x28};
  }

  private static ContentBlock thinkingBlock(String thinking, String signature) {
    return ContentBlock.ofThinking(
        ThinkingBlock.builder().thinking(thinking).signature(signature).build());
  }

  private static ContentBlock redactedThinkingBlock(String data) {
    return ContentBlock.ofRedactedThinking(RedactedThinkingBlock.builder().data(data).build());
  }

  private static ContentBlock textBlock(String text) {
    return ContentBlock.ofText(
        TextBlock.builder().text(text).citations(ImmutableList.of()).build());
  }

  private static ContentBlock toolUseBlock(String id, String name, Map<String, Object> input) {
    return ContentBlock.ofToolUse(
        ToolUseBlock.builder()
            .id(id)
            .name(name)
            .input(JsonValue.from(input))
            .caller(DirectCaller.builder().build())
            .toolsetName(Optional.empty())
            .build());
  }

  private static Content userText(String text) {
    return Content.builder().role("user").parts(Part.fromText(text)).build();
  }

  private static Content userParts(Part... parts) {
    return Content.builder().role("user").parts(parts).build();
  }

  private static LlmRequest request(Content... contents) {
    return LlmRequest.builder().contents(ImmutableList.copyOf(contents)).build();
  }

  private static LlmRequest request(GenerateContentConfig config, Content... contents) {
    return LlmRequest.builder().contents(ImmutableList.copyOf(contents)).config(config).build();
  }

  private static String signatureOf(Part part) {
    return new String(part.thoughtSignature().get(), UTF_8);
  }

  private static FunctionCall weatherCall(String id, String city) {
    return FunctionCall.builder()
        .id(id)
        .name("getWeather")
        .args(ImmutableMap.of("city", city))
        .build();
  }

  private static FunctionResponse weatherResult(String id, String weather) {
    return FunctionResponse.builder()
        .id(id)
        .name("getWeather")
        .response(ImmutableMap.of("result", weather))
        .build();
  }

  private static FunctionDeclaration weatherDeclaration() {
    return FunctionDeclaration.builder()
        .name("getWeather")
        .description("Gets the weather")
        .parameters(
            Schema.builder()
                .type("OBJECT")
                .properties(ImmutableMap.of("city", Schema.builder().type("STRING").build()))
                .build())
        .build();
  }

  private static com.google.genai.types.Tool toolOf(FunctionDeclaration... declarations) {
    return com.google.genai.types.Tool.builder()
        .functionDeclarations(ImmutableList.copyOf(declarations))
        .build();
  }

  /** Returns the text Claude receives for a function response holding {@code response}. */
  private String toolResultTextFor(Map<String, Object> response) {
    Content callTurn =
        Content.builder()
            .role("model")
            .parts(Part.builder().functionCall(weatherCall("toolu_1", "Seoul")).build())
            .build();
    Content resultTurn =
        userParts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .id("toolu_1")
                        .name("getWeather")
                        .response(response)
                        .build())
                .build());
    List<MessageParam> messages = sentMessages(userText("Weather?"), callTurn, resultTurn);
    ToolResultBlockParam result = messages.get(2).content().asBlockParams().get(0).asToolResult();
    return result.content().get().asString();
  }

  @Test
  public void generateContent_legacyTextOutputKey_isSentAsJson() {
    String text =
        toolResultTextFor(
            ImmutableMap.of("text_output", ImmutableMap.of("text", "Legacy result text")));

    assertThat(text).isEqualTo("{\"text_output\":{\"text\":\"Legacy result text\"}}");
  }

  @Test
  public void generateContent_responseWithoutResultOrContent_isSentAsJson() {
    assertThat(toolResultTextFor(ImmutableMap.of("custom_key", "custom_value")))
        .isEqualTo("{\"custom_key\":\"custom_value\"}");
  }

  @Test
  public void generateContent_listResult_isSentAsJson() {
    // FunctionTool wraps a return value that is not a map as {"result": value}.
    String text =
        toolResultTextFor(
            ImmutableMap.of(
                "result", ImmutableList.of(ImmutableMap.of("city", "Zurich", "staff", 120))));

    assertThat(text).isEqualTo("[{\"city\":\"Zurich\",\"staff\":120}]");
  }

  @Test
  public void generateContent_resultBesideOtherKeys_isSentAsTheWholeResponse() {
    String text = toolResultTextFor(ImmutableMap.of("result", "ok", "order_id", "ORD-7731"));

    assertThat(text).isEqualTo("{\"result\":\"ok\",\"order_id\":\"ORD-7731\"}");
  }

  @Test
  public void generateContent_contentList_isSentAsItsText() {
    String text =
        toolResultTextFor(
            ImmutableMap.of(
                "content",
                ImmutableList.of(
                    ImmutableMap.of("type", "text", "text", "first"),
                    ImmutableMap.of("type", "text", "text", "second")),
                "isError",
                true));

    assertThat(text).isEqualTo("first\nsecond");
  }

  @Test
  public void generateContent_contentString_isSentAsIs() {
    assertThat(toolResultTextFor(ImmutableMap.of("content", "done", "extra", 1))).isEqualTo("done");
  }

  @Test
  public void generateContent_usage_foldsCacheTokensIntoPromptAndSplitsThinking() {
    Usage usage =
        usage(10, 50).toBuilder()
            .cacheReadInputTokens(100L)
            .cacheCreationInputTokens(5L)
            .outputTokensDetails(OutputTokensDetails.builder().thinkingTokens(20).build())
            .build();

    LlmResponse response = responseTo(message(StopReason.END_TURN, usage, textBlock("4")));

    GenerateContentResponseUsageMetadata metadata = response.usageMetadata().get();
    assertThat(metadata.promptTokenCount()).hasValue(115);
    assertThat(metadata.candidatesTokenCount()).hasValue(30);
    assertThat(metadata.thoughtsTokenCount()).hasValue(20);
    assertThat(metadata.cachedContentTokenCount()).hasValue(100);
    assertThat(metadata.totalTokenCount()).hasValue(165);
  }

  @Test
  public void generateContent_usageWithoutCacheOrThinking_countsInputAndOutput() {
    LlmResponse response = responseTo(message(textBlock("4")));

    GenerateContentResponseUsageMetadata metadata = response.usageMetadata().get();
    assertThat(metadata.promptTokenCount()).hasValue(10);
    assertThat(metadata.candidatesTokenCount()).hasValue(5);
    assertThat(metadata.totalTokenCount()).hasValue(15);
    assertThat(metadata.thoughtsTokenCount()).isEmpty();
    assertThat(metadata.cachedContentTokenCount()).isEmpty();
  }

  @Test
  public void generateContent_stopReasons_mapToFinishReasons() {
    assertThat(finishReasonFor(StopReason.END_TURN)).isEqualTo(FinishReason.Known.STOP);
    assertThat(finishReasonFor(StopReason.TOOL_USE)).isEqualTo(FinishReason.Known.STOP);
    assertThat(finishReasonFor(StopReason.STOP_SEQUENCE)).isEqualTo(FinishReason.Known.STOP);
    assertThat(finishReasonFor(StopReason.PAUSE_TURN)).isEqualTo(FinishReason.Known.STOP);
    assertThat(finishReasonFor(StopReason.MAX_TOKENS)).isEqualTo(FinishReason.Known.MAX_TOKENS);
    assertThat(finishReasonFor(StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED))
        .isEqualTo(FinishReason.Known.MAX_TOKENS);
    assertThat(finishReasonFor(StopReason.REFUSAL)).isEqualTo(FinishReason.Known.SAFETY);
  }

  private FinishReason.Known finishReasonFor(StopReason stopReason) {
    return responseTo(message(stopReason, usage(1, 1), textBlock("x")))
        .finishReason()
        .get()
        .knownEnum();
  }

  @Test
  public void generateContent_setsModelVersion() {
    assertThat(responseTo(message(textBlock("4"))).modelVersion())
        .hasValue("claude-3-opus-20240229");
  }

  @Test
  public void generateContent_toolUseWithNullInput_hasNoArguments() {
    ContentBlock toolUse =
        ContentBlock.ofToolUse(
            ToolUseBlock.builder()
                .id("toolu_1")
                .name("getTime")
                .input(JsonValue.from(null))
                .caller(DirectCaller.builder().build())
                .toolsetName(Optional.empty())
                .build());

    LlmResponse response = responseTo(message(StopReason.TOOL_USE, usage(10, 5), toolUse));

    FunctionCall call = getOnlyElement(response.content().get().parts().get()).functionCall().get();
    assertThat(call.name()).hasValue("getTime");
    assertThat(call.args()).hasValue(ImmutableMap.of());
  }

  @Test
  public void generateContent_toolUseCutOffByMaxTokens_returnsAnErrorInsteadOfTheCall() {
    LlmResponse response =
        responseTo(
            message(
                StopReason.MAX_TOKENS,
                usage(10, 60),
                textBlock("Saving."),
                toolUseBlock("toolu_1", "saveNote", ImmutableMap.of())));

    assertThat(response.content()).isEmpty();
    assertThat(response.errorCode().get().knownEnum()).isEqualTo(FinishReason.Known.MAX_TOKENS);
    assertThat(response.finishReason().get().knownEnum()).isEqualTo(FinishReason.Known.MAX_TOKENS);
    assertThat(response.errorMessage()).isPresent();
    assertThat(response.usageMetadata()).isPresent();
  }

  @Test
  public void generateContent_textCutOffByMaxTokens_isReturnedWithMaxTokens() {
    LlmResponse response =
        responseTo(message(StopReason.MAX_TOKENS, usage(10, 24), textBlock("Warsaw was")));

    assertThat(response.content().get().parts().get().get(0).text()).hasValue("Warsaw was");
    assertThat(response.finishReason().get().knownEnum()).isEqualTo(FinishReason.Known.MAX_TOKENS);
    assertThat(response.errorCode()).isEmpty();
  }

  @Test
  public void generateContent_doesNotCallClaudeUntilSubscribed() {
    when(messageService.create(any(MessageCreateParams.class)))
        .thenReturn(message(textBlock("ok")));

    var responses = claude.generateContent(request(userText("Hi")), false);

    verifyNoInteractions(messageService);
    responses.blockingFirst();
    verify(messageService).create(any(MessageCreateParams.class));
  }

  @Test
  public void generateContent_failure_isDeliveredToTheSubscriber() {
    when(messageService.create(any(MessageCreateParams.class)))
        .thenThrow(new IllegalStateException("boom"));

    // Assembling the Flowable must not throw, so the flow's model error callbacks see the error.
    var responses = claude.generateContent(request(userText("Hi")), false);

    responses.test().assertError(IllegalStateException.class);
  }

  @Test
  public void generateContent_maxOutputTokensAndStopSequences_areSent() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .maxOutputTokens(40)
            .stopSequences(ImmutableList.of("##"))
            .build();

    MessageCreateParams params = sentParams(request(config, userText("Count.")));

    assertThat(params.maxTokens()).isEqualTo(40L);
    assertThat(params.stopSequences()).hasValue(ImmutableList.of("##"));
  }

  @Test
  public void generateContent_withoutMaxOutputTokens_sendsTheConstructorMaxTokens() {
    assertThat(sentParams(request(userText("Hi"))).maxTokens()).isEqualTo(8192L);
  }

  @Test
  public void generateContent_vertexResourceName_sendsTheBareModelId() {
    LlmRequest request =
        LlmRequest.builder()
            .model("projects/p/locations/global/publishers/anthropic/models/claude-haiku-4-5")
            .contents(ImmutableList.of(userText("Hi")))
            .build();

    assertThat(sentParams(request).model().asString()).isEqualTo("claude-haiku-4-5");
  }

  @Test
  public void generateContent_tools_allowParallelToolUse() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .tools(ImmutableList.of(toolOf(weatherDeclaration())))
            .build();

    MessageCreateParams params = sentParams(request(config, userText("Weather?")));

    ToolChoice toolChoice = params.toolChoice().get();
    assertThat(toolChoice.isAuto()).isTrue();
    assertThat(toolChoice.asAuto().disableParallelToolUse()).isEmpty();
  }

  @Test
  public void generateContent_functionDeclarationsOfEveryTool_areSent() {
    FunctionDeclaration timeDeclaration =
        FunctionDeclaration.builder().name("getTime").description("Gets the time").build();
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .tools(ImmutableList.of(toolOf(weatherDeclaration()), toolOf(timeDeclaration)))
            .build();

    MessageCreateParams params = sentParams(request(config, userText("Weather and time?")));

    assertThat(params.tools().get().stream().map(tool -> tool.asTool().name()))
        .containsExactly("getWeather", "getTime");
  }

  @Test
  public void generateContent_zeroThinkingBudget_disablesThinking() {
    assertThat(thinkingParamFor(0).isDisabled()).isTrue();
  }

  @Test
  public void generateContent_negativeThinkingBudget_asksForSummarizedAdaptiveThinking() {
    ThinkingConfigParam thinking = thinkingParamFor(-1);

    assertThat(thinking.isAdaptive()).isTrue();
    assertThat(thinking.asAdaptive().display()).hasValue(ThinkingConfigAdaptive.Display.SUMMARIZED);
  }

  @Test
  public void generateContent_positiveThinkingBudget_setsTheBudget() {
    ThinkingConfigParam thinking = thinkingParamFor(2048);

    assertThat(thinking.isEnabled()).isTrue();
    assertThat(thinking.asEnabled().budgetTokens()).isEqualTo(2048L);
  }

  private ThinkingConfigParam thinkingParamFor(int budget) {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .thinkingConfig(ThinkingConfig.builder().thinkingBudget(budget).build())
            .build();
    return sentParams(request(config, userText("Think."))).thinking().get();
  }

  @Test
  public void generateContent_thinkingConfigWithoutBudget_sendsNoThinkingMode() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .thinkingConfig(ThinkingConfig.builder().includeThoughts(true).build())
            .build();

    assertThat(sentParams(request(config, userText("Think."))).thinking()).isEmpty();
  }

  @Test
  public void builder_effort_isSentAsOutputConfig() {
    claude =
        Claude.builder()
            .modelName("claude-opus-4-8")
            .anthropicClient(mockClient)
            .effort(OutputConfig.Effort.LOW)
            .build();

    MessageCreateParams params = sentParams(request(userText("Why is the sky blue?")));

    assertThat(params.outputConfig().get().effort()).hasValue(OutputConfig.Effort.LOW);
    assertThat(params.model().asString()).isEqualTo("claude-opus-4-8");
    assertThat(params.maxTokens()).isEqualTo(8192L);
  }

  @Test
  public void builder_maxTokens_isSentWhenTheConfigSetsNone() {
    claude =
        Claude.builder()
            .modelName("claude-haiku-4-5")
            .anthropicClient(mockClient)
            .maxTokens(1024)
            .build();

    MessageCreateParams params = sentParams(request(userText("Hi")));

    assertThat(params.maxTokens()).isEqualTo(1024L);
    assertThat(params.model().asString()).isEqualTo("claude-haiku-4-5");
    assertThat(params.outputConfig()).isEmpty();
  }

  @Test
  public void builder_withoutModelNameOrClient_throws() {
    Claude.Builder withoutModelName = Claude.builder().anthropicClient(mockClient);
    Claude.Builder withoutClient = Claude.builder().modelName("claude-haiku-4-5");

    assertThrows(NullPointerException.class, withoutModelName::build);
    assertThrows(NullPointerException.class, withoutClient::build);
  }

  @Test
  @SuppressWarnings("deprecation") // Claude deprecates the sampling parameters.
  public void generateContent_samplingParameters_areSentWithoutThinkingOrEffort() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().temperature(0.7f).topP(0.9f).topK(40f).build();

    MessageCreateParams params = sentParams(request(config, userText("Hi")));

    assertThat(params.temperature()).hasValue(0.7);
    assertThat(params.topP()).hasValue(0.9);
    assertThat(params.topK()).hasValue(40L);
  }

  @Test
  @SuppressWarnings("deprecation") // Claude deprecates the sampling parameters.
  public void generateContent_samplingParametersWhileThinking_areDropped() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .temperature(0.7f)
            .topK(40f)
            .thinkingConfig(ThinkingConfig.builder().thinkingBudget(1024).build())
            .build();

    MessageCreateParams params = sentParams(request(config, userText("Think.")));

    assertThat(params.temperature()).isEmpty();
    assertThat(params.topK()).isEmpty();
  }

  @Test
  @SuppressWarnings("deprecation") // Claude deprecates the sampling parameters.
  public void generateContent_samplingParametersWithThinkingOff_areSent() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .temperature(0.7f)
            .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
            .build();

    assertThat(sentParams(request(config, userText("Hi"))).temperature()).hasValue(0.7);
  }

  @Test
  @SuppressWarnings("deprecation") // Claude deprecates the sampling parameters.
  public void builder_effort_dropsSamplingParameters() {
    claude =
        Claude.builder()
            .modelName("claude-opus-4-8")
            .anthropicClient(mockClient)
            .effort(OutputConfig.Effort.LOW)
            .build();
    GenerateContentConfig config = GenerateContentConfig.builder().temperature(0.7f).build();

    assertThat(sentParams(request(config, userText("Hi"))).temperature()).isEmpty();
  }

  @Test
  public void generateContent_cacheConfig_marksTheLastToolTheSystemAndTheLastBlock() {
    FunctionDeclaration timeDeclaration =
        FunctionDeclaration.builder().name("getTime").description("Gets the time").build();
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .systemInstruction(Content.fromParts(Part.fromText("Be brief.")))
            .tools(ImmutableList.of(toolOf(weatherDeclaration(), timeDeclaration)))
            .build();
    Content modelTurn = Content.builder().role("model").parts(Part.fromText("Hello.")).build();
    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(userText("Hi"), modelTurn, userText("Weather?")))
            .config(config)
            .cacheConfig(new ContextCacheConfig())
            .build();

    MessageCreateParams params = sentParams(request);

    CacheControlEphemeral fiveMinutes = CacheControlEphemeral.builder().build();
    assertThat(getOnlyElement(params.system().get().asTextBlockParams()).cacheControl())
        .hasValue(fiveMinutes);
    assertThat(params.tools().get().get(0).asTool().cacheControl()).isEmpty();
    assertThat(params.tools().get().get(1).asTool().cacheControl()).hasValue(fiveMinutes);
    List<MessageParam> messages = params.messages();
    assertThat(messages.get(0).content().asBlockParams().get(0).asText().cacheControl()).isEmpty();
    assertThat(messages.get(2).content().asBlockParams().get(0).asText().cacheControl())
        .hasValue(fiveMinutes);
  }

  @Test
  public void generateContent_cacheConfig_marksTheStructuredOutputToolAndAToolResult() {
    Content callTurn =
        Content.builder()
            .role("model")
            .parts(Part.builder().functionCall(weatherCall("toolu_1", "Paris")).build())
            .build();
    Content resultTurn =
        userParts(Part.builder().functionResponse(weatherResult("toolu_1", "sunny")).build());
    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(userText("Weather?"), callTurn, resultTurn))
            .config(GenerateContentConfig.builder().responseSchema(cityInfoSchema()).build())
            .cacheConfig(new ContextCacheConfig())
            .build();

    MessageCreateParams params = sentParams(request);

    assertThat(getOnlyElement(params.tools().get()).asTool().cacheControl()).isPresent();
    ContentBlockParam lastBlock =
        getOnlyElement(params.messages().get(2).content().asBlockParams());
    assertThat(lastBlock.asToolResult().cacheControl()).isPresent();
  }

  @Test
  public void generateContent_cacheTtlOfAnHourOrMore_asksForTheHourLongCache() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(userText("Hi")))
            .cacheConfig(new ContextCacheConfig(10, Duration.ofHours(1), 0))
            .build();

    MessageParam message = getOnlyElement(sentParams(request).messages());

    assertThat(message.content().asBlockParams().get(0).asText().cacheControl().get().ttl())
        .hasValue(CacheControlEphemeral.Ttl.TTL_1H);
  }

  @Test
  public void generateContent_previousPromptBelowMinTokens_sendsNoCacheBreakpoint() {
    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(userText("Hi")))
            .cacheConfig(new ContextCacheConfig(10, Duration.ofMinutes(30), 4096))
            .cacheableContentsTokenCount(100)
            .build();

    MessageParam message = getOnlyElement(sentParams(request).messages());

    assertThat(message.content().asBlockParams().get(0).asText().cacheControl()).isEmpty();
  }

  @Test
  public void generateContent_cacheBreakpoint_skipsATrailingThinkingBlock() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.fromText("Hello."),
                Part.builder()
                    .text("Greeted.")
                    .thought(true)
                    .thoughtSignature("signature-1".getBytes(UTF_8))
                    .build())
            .build();
    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(userText("Hi"), modelTurn))
            .cacheConfig(new ContextCacheConfig())
            .build();

    List<ContentBlockParam> blocks =
        sentParams(request).messages().get(1).content().asBlockParams();

    assertThat(blocks.get(0).asText().cacheControl()).isPresent();
    assertThat(blocks.get(1).isThinking()).isTrue();
  }

  @Test
  public void generateContent_imagePart_isSentAsABase64ImageBlock() {
    byte[] png = {(byte) 0x89, 'P', 'N', 'G'};

    List<MessageParam> messages =
        sentMessages(
            userParts(
                Part.fromText("What is this?"), Part.fromBytes(png, "image/PNG; charset=binary")));

    ContentBlockParam block = messages.get(0).content().asBlockParams().get(1);
    Base64ImageSource source = block.asImage().source().asBase64();
    assertThat(source.data()).isEqualTo(Base64.getEncoder().encodeToString(png));
    assertThat(source.mediaType()).isEqualTo(Base64ImageSource.MediaType.IMAGE_PNG);
  }

  @Test
  public void generateContent_pdfPart_isSentAsABase64DocumentBlock() {
    byte[] pdf = "%PDF-1.4".getBytes(UTF_8);

    List<MessageParam> messages = sentMessages(userParts(Part.fromBytes(pdf, "application/pdf")));

    ContentBlockParam block = messages.get(0).content().asBlockParams().get(0);
    assertThat(block.asDocument().source().asBase64().data())
        .isEqualTo(Base64.getEncoder().encodeToString(pdf));
  }

  @Test
  public void generateContent_imageInAnAssistantTurn_isDropped() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(Part.fromBytes(new byte[] {1}, "image/png"), Part.fromText("A chart."))
            .build();

    List<MessageParam> messages = sentMessages(userText("Draw."), modelTurn, userText("Thanks."));

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("A chart.");
  }

  @Test
  public void generateContent_unsupportedInlineData_fails() {
    claude
        .generateContent(request(userParts(Part.fromBytes(new byte[] {1}, "audio/wav"))), false)
        .test()
        .assertError(IllegalArgumentException.class);
  }

  @Test
  public void generateContent_codeExecutionParts_areSentAsText() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .executableCode(
                        ExecutableCode.builder().language("PYTHON").code("print(6 * 7)").build())
                    .build(),
                Part.builder()
                    .codeExecutionResult(
                        CodeExecutionResult.builder().outcome("OUTCOME_OK").output("42\n").build())
                    .build())
            .build();

    List<MessageParam> messages = sentMessages(userText("Compute."), modelTurn, userText("And?"));

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks.get(0).asText().text()).isEqualTo("Code:```python\nprint(6 * 7)\n```");
    assertThat(blocks.get(1).asText().text())
        .isEqualTo("Execution Result:```code_output\n42\n\n```");
  }

  @Test
  public void generateContent_invalidToolUseIds_areRemappedConsistently() {
    // Simulates tool-call ids from another provider, which Claude rejects.
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().functionCall(weatherCall("functions.getWeather:0", "Oslo")).build())
            .build();
    Content resultTurn =
        userParts(
            Part.builder()
                .functionResponse(weatherResult("functions.getWeather:0", "snow"))
                .build());

    List<MessageParam> messages = sentMessages(userText("Weather?"), modelTurn, resultTurn);

    String callId = messages.get(1).content().asBlockParams().get(0).asToolUse().id();
    assertThat(callId).matches("[a-zA-Z0-9_-]+");
    assertThat(messages.get(2).content().asBlockParams().get(0).asToolResult().toolUseId())
        .isEqualTo(callId);
  }

  @Test
  public void generateContent_missingToolUseIds_pairResultsWithCallsByName() {
    FunctionCall timeCall = FunctionCall.builder().name("getTime").build();
    FunctionCall weatherCall =
        FunctionCall.builder().name("getWeather").args(ImmutableMap.of("city", "Oslo")).build();
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().functionCall(timeCall).build(),
                Part.builder().functionCall(weatherCall).build())
            .build();
    Content resultTurn =
        userParts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .name("getWeather")
                        .response(ImmutableMap.of("result", "snow"))
                        .build())
                .build(),
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .name("getTime")
                        .response(ImmutableMap.of("result", "noon"))
                        .build())
                .build());

    List<MessageParam> messages =
        sentMessages(userText("Time and weather?"), modelTurn, resultTurn);

    List<ContentBlockParam> calls = messages.get(1).content().asBlockParams();
    List<ContentBlockParam> results = messages.get(2).content().asBlockParams();
    assertThat(results.get(0).asToolResult().toolUseId()).isEqualTo(calls.get(1).asToolUse().id());
    assertThat(results.get(1).asToolResult().toolUseId()).isEqualTo(calls.get(0).asToolUse().id());
  }

  private static Schema cityInfoSchema() {
    return Schema.builder()
        .type("OBJECT")
        .properties(
            ImmutableMap.of(
                "city", Schema.builder().type("STRING").build(),
                "population", Schema.builder().type("NUMBER").build()))
        .required(ImmutableList.of("city"))
        .build();
  }

  @Test
  public void generateContent_responseSchema_isSentAsAForcedStructuredOutputTool() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().responseSchema(cityInfoSchema()).build();
    when(messageService.create(any(MessageCreateParams.class)))
        .thenReturn(
            message(
                StopReason.TOOL_USE,
                usage(10, 20),
                textBlock("Here it is."),
                toolUseBlock("toolu_1", "structured_output", ImmutableMap.of("city", "Paris"))));

    LlmResponse response =
        claude
            .generateContent(request(config, userText("Capital of France?")), false)
            .blockingFirst();

    ArgumentCaptor<MessageCreateParams> params = ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(messageService).create(params.capture());
    Tool tool = getOnlyElement(params.getValue().tools().get()).asTool();
    assertThat(tool.name()).isEqualTo("structured_output");
    assertThat(inputSchemaProperties(tool)).containsKey("population");
    ToolChoice toolChoice = params.getValue().toolChoice().get();
    assertThat(toolChoice.asTool().name()).isEqualTo("structured_output");
    // The narration before the call is not part of the structured answer.
    Part answer = getOnlyElement(response.content().get().parts().get());
    assertThat(answer.text()).hasValue("{\"city\":\"Paris\"}");
  }

  @Test
  public void generateContent_nonObjectResponseSchema_isNestedAndUnwrapped() {
    Schema listSchema =
        Schema.builder().type("ARRAY").items(Schema.builder().type("STRING").build()).build();
    GenerateContentConfig config =
        GenerateContentConfig.builder().responseSchema(listSchema).build();
    when(messageService.create(any(MessageCreateParams.class)))
        .thenReturn(
            message(
                StopReason.TOOL_USE,
                usage(10, 20),
                toolUseBlock(
                    "toolu_1",
                    "structured_output",
                    ImmutableMap.of("response", ImmutableList.of("a", "b")))));

    LlmResponse response =
        claude.generateContent(request(config, userText("Two letters?")), false).blockingFirst();

    ArgumentCaptor<MessageCreateParams> params = ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(messageService).create(params.capture());
    Tool tool = getOnlyElement(params.getValue().tools().get()).asTool();
    assertThat(inputSchemaProperties(tool)).containsKey("response");
    assertThat(tool.inputSchema().required()).hasValue(ImmutableList.of("response"));
    assertThat(getOnlyElement(response.content().get().parts().get()).text())
        .hasValue("[\"a\",\"b\"]");
  }

  @Test
  public void generateContent_responseSchemaWhileThinking_offersTheToolWithoutForcingIt() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseSchema(cityInfoSchema())
            .thinkingConfig(ThinkingConfig.builder().thinkingBudget(2048).build())
            .build();

    ToolChoice toolChoice = sentParams(request(config, userText("Capital?"))).toolChoice().get();

    assertThat(toolChoice.isAuto()).isTrue();
    assertThat(toolChoice.asAuto().disableParallelToolUse()).hasValue(true);
  }

  @Test
  public void generateContent_responseSchemaWithFunctionTools_sendsOnlyTheFunctionTools() {
    GenerateContentConfig config =
        GenerateContentConfig.builder()
            .responseSchema(cityInfoSchema())
            .tools(ImmutableList.of(toolOf(weatherDeclaration())))
            .build();

    MessageCreateParams params = sentParams(request(config, userText("Weather?")));

    assertThat(getOnlyElement(params.tools().get()).asTool().name()).isEqualTo("getWeather");
  }

  private LlmAgent claudeAgentWithOutputSchema(BaseTool... tools) {
    return LlmAgent.builder()
        .name("agent")
        .model(claude)
        .outputSchema(cityInfoSchema())
        .tools(ImmutableList.copyOf(tools))
        .build();
  }

  @Test
  public void outputSchemaWithTools_givesClaudeTheSetModelResponseTool() {
    BaseTool weatherTool =
        new BaseTool("getWeather", "Gets the weather") {
          @Override
          public Optional<FunctionDeclaration> declaration() {
            return Optional.of(weatherDeclaration());
          }
        };
    InvocationContext context = createInvocationContext(claudeAgentWithOutputSchema(weatherTool));
    LlmRequest request =
        LlmRequest.builder().tools(ImmutableMap.of(weatherTool.name(), weatherTool)).build();

    LlmRequest basicRequest =
        new Basic().processRequest(context, request).blockingGet().updatedRequest();
    LlmRequest finalRequest =
        new OutputSchema().processRequest(context, basicRequest).blockingGet().updatedRequest();

    assertThat(basicRequest.config().flatMap(GenerateContentConfig::responseSchema)).isEmpty();
    assertThat(finalRequest.tools()).containsKey(SetModelResponseTool.NAME);
  }

  @Test
  public void outputSchemaWithSubAgents_givesClaudeTheSetModelResponseTool() {
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(claude)
            .outputSchema(cityInfoSchema())
            .subAgents(LlmAgent.builder().name("helper").model(claude).build())
            .build();
    InvocationContext context = createInvocationContext(agent);

    LlmRequest basicRequest =
        new Basic()
            .processRequest(context, LlmRequest.builder().build())
            .blockingGet()
            .updatedRequest();
    LlmRequest finalRequest =
        new OutputSchema().processRequest(context, basicRequest).blockingGet().updatedRequest();

    assertThat(basicRequest.config().flatMap(GenerateContentConfig::responseSchema)).isEmpty();
    assertThat(finalRequest.tools()).containsKey(SetModelResponseTool.NAME);
  }

  @Test
  public void outputSchemaWithoutTools_givesClaudeTheResponseSchema() {
    InvocationContext context = createInvocationContext(claudeAgentWithOutputSchema());

    LlmRequest basicRequest =
        new Basic()
            .processRequest(context, LlmRequest.builder().build())
            .blockingGet()
            .updatedRequest();

    assertThat(basicRequest.config().flatMap(GenerateContentConfig::responseSchema)).isPresent();
  }

  @Test
  public void functionDeclarationToAnthropicTool_usesParameters() throws Exception {
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("retrievesItemByItemNumber")
            .description("Retrieves an item")
            .parameters(
                Schema.builder()
                    .type("OBJECT")
                    .properties(
                        ImmutableMap.of("itemNumber", Schema.builder().type("STRING").build()))
                    .required(ImmutableList.of("itemNumber"))
                    .build())
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(null, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(properties).containsKey("itemNumber");
    // The genai type "STRING" is lowercased to the JSON Schema "string" for Claude.
    assertThat(((Map<String, Object>) properties.get("itemNumber")).get("type"))
        .isEqualTo("string");
    assertThat(tool.inputSchema().required()).hasValue(ImmutableList.of("itemNumber"));
  }

  @Test
  public void functionDeclarationToAnthropicTool_fallsBackToParametersJsonSchema()
      throws Exception {
    // MCP tools populate parametersJsonSchema instead of the structured parameters() field.
    Map<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of(
                "dataset",
                ImmutableMap.of("type", "string", "description", "The dataset id"),
                "project",
                ImmutableMap.of("type", "string")),
            "required",
            ImmutableList.of("dataset"));
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("get_dataset_info")
            .description("Gets dataset info")
            .parametersJsonSchema(jsonSchema)
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(null, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    // Before the fix these properties were empty, so Claude could not invoke the MCP tool.
    assertThat(properties).containsKey("dataset");
    assertThat(properties).containsKey("project");
    assertThat(((Map<String, Object>) properties.get("dataset")).get("type")).isEqualTo("string");
    assertThat(tool.inputSchema().required()).hasValue(ImmutableList.of("dataset"));
  }

  @Test
  public void functionDeclarationToAnthropicTool_noParameters_hasEmptyProperties()
      throws Exception {
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder().name("no_args_tool").description("Takes no args").build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(null, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(properties).isEmpty();
    assertThat(tool.inputSchema().required()).isEmpty();
  }

  @Test
  public void functionDeclarationToAnthropicTool_unionTypeArray_doesNotThrow() throws Exception {
    // JSON Schema permits a union type array (e.g. ["string", "null"]), which MCP tools can emit.
    // It must not be cast to String, which previously threw ClassCastException.
    Map<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of(
                "nickname", ImmutableMap.of("type", ImmutableList.of("string", "null"))));
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("set_nickname")
            .description("Sets a nickname")
            .parametersJsonSchema(jsonSchema)
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(null, functionDeclaration);

    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(properties).containsKey("nickname");
    // The union type array is preserved rather than crashing on the String cast.
    assertThat(((Map<String, Object>) properties.get("nickname")).get("type"))
        .isEqualTo(ImmutableList.of("string", "null"));
  }

  @Test
  public void functionDeclarationToAnthropicTool_preservesRefsAndDefs() throws Exception {
    // MCP tools may use $ref/$defs. These top-level keywords must survive so Claude does not
    // receive dangling references.
    Map<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "properties",
            ImmutableMap.of("pet", ImmutableMap.of("$ref", "#/$defs/Pet")),
            "$defs",
            ImmutableMap.of(
                "Pet",
                ImmutableMap.of(
                    "type",
                    "object",
                    "properties",
                    ImmutableMap.of("name", ImmutableMap.of("type", "string")))));
    FunctionDeclaration functionDeclaration =
        FunctionDeclaration.builder()
            .name("register_pet")
            .description("Registers a pet")
            .parametersJsonSchema(jsonSchema)
            .build();

    Tool tool = (Tool) functionDeclarationToAnthropicToolMethod.invoke(null, functionDeclaration);

    // The $ref is kept on the property...
    Map<String, Object> properties = inputSchemaProperties(tool);
    assertThat(((Map<String, Object>) properties.get("pet")).get("$ref")).isEqualTo("#/$defs/Pet");
    // ...and the $defs block survives as a top-level keyword.
    JsonValue defsValue = (JsonValue) tool.inputSchema()._additionalProperties().get("$defs");
    assertThat(defsValue).isNotNull();
    Map<String, Object> defs = defsValue.convert(new TypeReference<Map<String, Object>>() {});
    assertThat(defs).containsKey("Pet");
  }

  @Test
  public void generateContent_thinkingBlocks_becomeThoughtParts() {
    // With display "omitted" (the Claude 5 default) a thinking block has empty text.
    Message reply =
        message(
            thinkingBlock("", "signature"), redactedThinkingBlock("redacted-data"), textBlock("4"));

    List<Part> parts = responseTo(reply).content().get().parts().get();

    assertThat(parts).hasSize(3);
    assertThat(parts.get(0).thought()).hasValue(true);
    assertThat(parts.get(0).text()).hasValue("");
    assertThat(signatureOf(parts.get(0))).isEqualTo("signature");
    assertThat(parts.get(1).thought()).hasValue(true);
    assertThat(parts.get(1).text()).isEmpty();
    assertThat(signatureOf(parts.get(1))).isEqualTo("redacted-data");
    assertThat(parts.get(2).thought()).isEmpty();
    assertThat(parts.get(2).text()).hasValue("4");
  }

  @Test
  public void generateContent_thinkingBlockWithEmptySignature_isNotSigned() {
    Part thought =
        responseTo(message(thinkingBlock("Thinking.", ""), textBlock("4")))
            .content()
            .get()
            .parts()
            .get()
            .get(0);

    assertThat(thought.thought()).hasValue(true);
    assertThat(thought.text()).hasValue("Thinking.");
    assertThat(thought.thoughtSignature()).isEmpty();
  }

  @Test
  public void generateContent_toolTurn_sendsThinkingBlocksBackUnchanged() {
    Message toolUseReply =
        message(
            StopReason.TOOL_USE,
            usage(10, 5),
            thinkingBlock("", "signature-1"),
            redactedThinkingBlock("redacted-data"),
            textBlock("Let me check."),
            thinkingBlock("Checking the weather.", "signature-2"),
            toolUseBlock("toolu_1", "getWeather", ImmutableMap.of("city", "Seoul")));
    Message finalReply = message(textBlock("It is sunny."));
    when(messageService.create(any(MessageCreateParams.class)))
        .thenReturn(toolUseReply, finalReply);
    Content toolResult =
        userParts(Part.builder().functionResponse(weatherResult("toolu_1", "sunny")).build());
    Content modelTurn =
        claude
            .generateContent(request(userText("Weather in Seoul?")), false)
            .blockingFirst()
            .content()
            .get();

    claude
        .generateContent(request(userText("Weather in Seoul?"), modelTurn, toolResult), false)
        .blockingFirst();

    ArgumentCaptor<MessageCreateParams> params = ArgumentCaptor.forClass(MessageCreateParams.class);
    verify(messageService, times(2)).create(params.capture());
    List<ContentBlockParam> blocks =
        params.getAllValues().get(1).messages().get(1).content().asBlockParams();
    assertThat(blocks).hasSize(5);
    assertThat(blocks.get(0).asThinking().thinking()).isEmpty();
    assertThat(blocks.get(0).asThinking().signature()).isEqualTo("signature-1");
    assertThat(blocks.get(1).asRedactedThinking().data()).isEqualTo("redacted-data");
    assertThat(blocks.get(2).asText().text()).isEqualTo("Let me check.");
    assertThat(blocks.get(3).asThinking().thinking()).isEqualTo("Checking the weather.");
    assertThat(blocks.get(3).asThinking().signature()).isEqualTo("signature-2");
    assertThat(blocks.get(4).asToolUse().id()).isEqualTo("toolu_1");
  }

  @Test
  public void generateContent_emptyThoughtWithoutSignature_isNotSent() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().text("").thought(true).build(),
                Part.builder().text("").thought(true).thoughtSignature(new byte[0]).build(),
                Part.fromText("Hello."))
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Bye"));

    // Neither thought has a signature to send back, and Anthropic rejects empty text blocks.
    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Hello.");
  }

  @Test
  public void generateContent_thoughtWithBinarySignature_isSentAsText() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .text("Looking up the weather.")
                    .thought(true)
                    .thoughtSignature(nonUtf8Signature())
                    .build(),
                Part.fromText("It is sunny."))
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Thanks"));

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(2);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Looking up the weather.");
    assertThat(blocks.get(1).asText().text()).isEqualTo("It is sunny.");
  }

  @Test
  public void generateContent_thoughtWithoutTextOrUsableSignature_isNotSent() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().thought(true).build(),
                Part.builder().thought(true).thoughtSignature(nonUtf8Signature()).build(),
                Part.fromText("Hello."))
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Bye"));

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Hello.");
  }

  @Test
  public void generateContent_thoughtFlaggedCallsAndResponses_areSentAsToolUseAndToolResult() {
    byte[] signature = "signature".getBytes(UTF_8);
    // A call or response flagged as a thought is still sent, whatever text or signature it has.
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .thought(true)
                    .thoughtSignature(signature)
                    .functionCall(weatherCall("toolu_1", "Seoul"))
                    .build(),
                Part.builder()
                    .text("")
                    .thought(true)
                    .functionCall(weatherCall("toolu_2", "Busan"))
                    .build(),
                Part.builder()
                    .text("Checking Jeju.")
                    .thought(true)
                    .thoughtSignature(signature)
                    .functionCall(weatherCall("toolu_3", "Jeju"))
                    .build())
            .build();
    Content toolResults =
        userParts(
            Part.builder()
                .thought(true)
                .thoughtSignature(signature)
                .functionResponse(weatherResult("toolu_1", "sunny"))
                .build(),
            Part.builder()
                .text("")
                .thought(true)
                .functionResponse(weatherResult("toolu_2", "rainy"))
                .build(),
            Part.builder()
                .text("Got Jeju.")
                .thought(true)
                .thoughtSignature(signature)
                .functionResponse(weatherResult("toolu_3", "windy"))
                .build());

    List<MessageParam> messages = sentMessages(userText("Weather?"), modelTurn, toolResults);

    List<ContentBlockParam> calls = messages.get(1).content().asBlockParams();
    assertThat(calls).hasSize(3);
    assertThat(calls.get(0).asToolUse().id()).isEqualTo("toolu_1");
    assertThat(calls.get(0).asToolUse().name()).isEqualTo("getWeather");
    assertThat(calls.get(0).asToolUse()._input())
        .isEqualTo(JsonValue.from(ImmutableMap.of("city", "Seoul")));
    assertThat(calls.get(1).asToolUse().id()).isEqualTo("toolu_2");
    assertThat(calls.get(1).asToolUse().name()).isEqualTo("getWeather");
    assertThat(calls.get(1).asToolUse()._input())
        .isEqualTo(JsonValue.from(ImmutableMap.of("city", "Busan")));
    assertThat(calls.get(2).asToolUse().id()).isEqualTo("toolu_3");
    assertThat(calls.get(2).asToolUse().name()).isEqualTo("getWeather");
    assertThat(calls.get(2).asToolUse()._input())
        .isEqualTo(JsonValue.from(ImmutableMap.of("city", "Jeju")));
    List<ContentBlockParam> results = messages.get(2).content().asBlockParams();
    assertThat(results).hasSize(3);
    assertThat(results.get(0).asToolResult().toolUseId()).isEqualTo("toolu_1");
    assertThat(results.get(0).asToolResult().content().get().asString()).isEqualTo("sunny");
    assertThat(results.get(1).asToolResult().toolUseId()).isEqualTo("toolu_2");
    assertThat(results.get(1).asToolResult().content().get().asString()).isEqualTo("rainy");
    assertThat(results.get(2).asToolResult().toolUseId()).isEqualTo("toolu_3");
    assertThat(results.get(2).asToolResult().content().get().asString()).isEqualTo("windy");
  }

  @Test
  public void generateContent_contentFreeSignature_isNotSent() {
    // Another model (e.g. Gemini) leaves signatures on parts that carry nothing else.
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().text("").thoughtSignature(nonUtf8Signature()).build(),
                Part.builder().thoughtSignature("signature".getBytes(UTF_8)).build(),
                // Metadata is not content, so this part is dropped too.
                Part.builder()
                    .thoughtSignature("signature-2".getBytes(UTF_8))
                    .partMetadata(ImmutableMap.of("source", "test"))
                    .build(),
                Part.fromText("Hello."))
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Bye"));

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Hello.");
  }

  @Test
  public void generateContent_signatureBesideContent_isKept() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder()
                    .functionCall(weatherCall("toolu_1", "Seoul"))
                    .thoughtSignature(nonUtf8Signature())
                    .build(),
                Part.builder()
                    .text("Checking the weather.")
                    .thought(true)
                    .thoughtSignature("signature-1".getBytes(UTF_8))
                    .build(),
                Part.builder().thought(true).thoughtSignature("redacted-1".getBytes(UTF_8)).build())
            .build();
    Content toolResult =
        userParts(Part.builder().functionResponse(weatherResult("toolu_1", "sunny")).build());

    List<MessageParam> messages = sentMessages(userText("Weather?"), modelTurn, toolResult);

    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(3);
    assertThat(blocks.get(0).asToolUse().id()).isEqualTo("toolu_1");
    assertThat(blocks.get(1).asThinking().signature()).isEqualTo("signature-1");
    assertThat(blocks.get(2).asRedactedThinking().data()).isEqualTo("redacted-1");
  }

  @Test
  public void generateContent_turnOfOnlyDroppedThoughts_isNotSent() {
    Content modelTurn =
        Content.builder()
            .role("model")
            .parts(
                Part.builder().thought(true).build(),
                Part.builder().thought(true).thoughtSignature(nonUtf8Signature()).build())
            .build();

    List<MessageParam> messages = sentMessages(userText("Hi"), modelTurn, userText("Bye"));

    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).content().asBlockParams().get(0).asText().text()).isEqualTo("Hi");
    assertThat(messages.get(1).content().asBlockParams().get(0).asText().text()).isEqualTo("Bye");
  }

  @Test
  public void generateContent_turnOfOnlyContentFreeSignature_isNotSent() {
    Part contentFreeSignature = Part.builder().thoughtSignature(nonUtf8Signature()).build();
    Content modelTurn = Content.builder().role("model").parts(contentFreeSignature).build();
    // Only a user turn of empty text keeps a placeholder; this one is left out.
    Content userTurn = Content.builder().role("user").parts(contentFreeSignature).build();

    List<MessageParam> messages =
        sentMessages(userText("Hi"), modelTurn, userText("Bye"), userTurn);

    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).content().asBlockParams().get(0).asText().text()).isEqualTo("Hi");
    assertThat(messages.get(1).content().asBlockParams().get(0).asText().text()).isEqualTo("Bye");
  }

  @Test
  public void generateContent_emptyTextPart_isNotSent() {
    // A session store that keeps only text (e.g. Firestore) restores a Claude thought this way.
    Content modelTurn =
        Content.builder().role("model").parts(Part.fromText(""), Part.fromText("Hello.")).build();
    Content emptyModelTurn = Content.builder().role("model").parts(Part.fromText("")).build();

    List<MessageParam> messages =
        sentMessages(
            userText("Hi"), modelTurn, userText("Weather?"), emptyModelTurn, userText("Bye"));

    assertThat(messages).hasSize(4);
    List<ContentBlockParam> blocks = messages.get(1).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo("Hello.");
    assertThat(messages.get(2).content().asBlockParams().get(0).asText().text())
        .isEqualTo("Weather?");
    assertThat(messages.get(3).content().asBlockParams().get(0).asText().text()).isEqualTo("Bye");
  }

  @Test
  public void generateContent_userTurnOfOnlyEmptyText_isSentAsPlaceholder() {
    Content userTurn =
        Content.builder().role("user").parts(Part.fromText(""), Part.fromText("Run it.")).build();
    Content modelTurn = Content.builder().role("model").parts(Part.fromText("Running.")).build();

    // E.g. an empty user message; dropping it would end the request on an assistant turn.
    List<MessageParam> messages = sentMessages(userTurn, modelTurn, userText(""));

    assertThat(messages).hasSize(3);
    // Beside other content, an empty part is just dropped.
    List<ContentBlockParam> firstBlocks = messages.get(0).content().asBlockParams();
    assertThat(firstBlocks).hasSize(1);
    assertThat(firstBlocks.get(0).asText().text()).isEqualTo("Run it.");
    assertThat(messages.get(2).role()).isEqualTo(MessageParam.Role.USER);
    List<ContentBlockParam> blocks = messages.get(2).content().asBlockParams();
    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).asText().text()).isEqualTo(".");
  }

  @Test
  public void generateContent_extendsTheSdkUserAgentWithAdkTrackingHeaders() throws Exception {
    Headers headers = headersSentThrough(AnthropicOkHttpClient.builder());

    String userAgent = getOnlyElement(headers.get("user-agent"));
    assertThat(userAgent).startsWith("AnthropicClientImpl/Java ");
    assertThat(userAgent).endsWith(" " + TrackingHeaders.VALUE);
    assertThat(headers.get("x-goog-api-client")).containsExactly(TrackingHeaders.VALUE);
  }

  @Test
  public void generateContent_extendsTheCallerUserAgentWithAdkTrackingHeaders() throws Exception {
    Headers headers =
        headersSentThrough(AnthropicOkHttpClient.builder().putHeader("User-Agent", "my-app/1.0"));

    assertThat(headers.get("user-agent")).containsExactly("my-app/1.0 " + TrackingHeaders.VALUE);
    assertThat(headers.get("x-goog-api-client")).containsExactly(TrackingHeaders.VALUE);
  }

  @Test
  public void constructor_nullClient_doesNotThrow() {
    assertThat(new Claude("claude-test", (AnthropicClient) null).model()).isEqualTo("claude-test");
  }

  private static final String MESSAGE_JSON =
      "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\","
          + "\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],\"stop_reason\":\"end_turn\","
          + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}";

  @Test
  public void generateContent_stream_emitsPartialsThenTheWholeMessage() throws Exception {
    // A thought, text and a tool call, each sent in deltas, then a tool call with no input deltas.
    String body =
        sse(
            "message_start",
            MESSAGE_START,
            "content_block_start",
            blockStart(0, "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}"),
            "content_block_delta",
            blockDelta(0, "{\"type\":\"thinking_delta\",\"thinking\":\"Hmm.\"}"),
            "content_block_delta",
            blockDelta(0, "{\"type\":\"signature_delta\",\"signature\":\"sig-1\"}"),
            "content_block_stop",
            blockStop(0),
            "content_block_start",
            blockStart(1, "{\"type\":\"text\",\"text\":\"\"}"),
            "content_block_delta",
            blockDelta(1, "{\"type\":\"text_delta\",\"text\":\"Hel\"}"),
            "content_block_delta",
            blockDelta(1, "{\"type\":\"text_delta\",\"text\":\"lo\"}"),
            "content_block_stop",
            blockStop(1),
            "content_block_start",
            toolUseStart(2, "toolu_1", "getWeather"),
            "content_block_delta",
            inputJsonDelta(2, "{\"city\": "),
            "content_block_delta",
            inputJsonDelta(2, "\"Paris\"}"),
            "content_block_stop",
            blockStop(2),
            "content_block_start",
            toolUseStart(3, "toolu_2", "getTime"),
            "content_block_stop",
            blockStop(3),
            "message_delta",
            messageDelta("tool_use"),
            "message_stop",
            MESSAGE_STOP);

    List<LlmResponse> responses = streamedResponses(body, request(userText("Weather and time?")));

    assertThat(responses).hasSize(4);
    Part thoughtDelta = responses.get(0).content().get().parts().get().get(0);
    assertThat(thoughtDelta.thought()).hasValue(true);
    assertThat(thoughtDelta.text()).hasValue("Hmm.");
    assertThat(responses.get(1).content().get().parts().get().get(0).text()).hasValue("Hel");
    assertThat(responses.get(2).content().get().parts().get().get(0).text()).hasValue("lo");
    assertThat(responses.subList(0, 3).stream().allMatch(r -> r.partial().orElse(false))).isTrue();

    LlmResponse last = responses.get(3);
    assertThat(last.partial().orElse(false)).isFalse();
    List<Part> parts = last.content().get().parts().get();
    assertThat(parts).hasSize(4);
    assertThat(parts.get(0).thought()).hasValue(true);
    assertThat(signatureOf(parts.get(0))).isEqualTo("sig-1");
    assertThat(parts.get(1).text()).hasValue("Hello");
    assertThat(parts.get(2).functionCall().get().args()).hasValue(ImmutableMap.of("city", "Paris"));
    // A tool_use block streamed without input deltas has no arguments.
    assertThat(parts.get(3).functionCall().get().args().orElse(ImmutableMap.of())).isEmpty();
    assertThat(last.finishReason().get().knownEnum()).isEqualTo(FinishReason.Known.STOP);
    assertThat(last.usageMetadata().get().totalTokenCount()).hasValue(23);
    assertThat(last.modelVersion()).hasValue("claude-test");
  }

  /** Joins server-sent events, each a type and its JSON data. */
  private static String sse(String... typesAndData) {
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < typesAndData.length; i += 2) {
      body.append("event: ").append(typesAndData[i]).append("\ndata: ").append(typesAndData[i + 1]);
      body.append("\n\n");
    }
    return body.toString();
  }

  private static final String MESSAGE_START =
      "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
          + "\"role\":\"assistant\",\"model\":\"claude-test\",\"content\":[],"
          + "\"stop_reason\":null,\"stop_sequence\":null,"
          + "\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}";

  private static final String MESSAGE_STOP = "{\"type\":\"message_stop\"}";

  private static String blockStart(int index, String contentBlock) {
    return "{\"type\":\"content_block_start\",\"index\":"
        + index
        + ",\"content_block\":"
        + contentBlock
        + "}";
  }

  private static String blockDelta(int index, String delta) {
    return "{\"type\":\"content_block_delta\",\"index\":" + index + ",\"delta\":" + delta + "}";
  }

  private static String blockStop(int index) {
    return "{\"type\":\"content_block_stop\",\"index\":" + index + "}";
  }

  private static String toolUseStart(int index, String id, String name) {
    return blockStart(
        index,
        "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":{}}");
  }

  private static String inputJsonDelta(int index, String partialJson) throws Exception {
    return blockDelta(
        index,
        "{\"type\":\"input_json_delta\",\"partial_json\":"
            + JsonBaseModel.getMapper().writeValueAsString(partialJson)
            + "}");
  }

  private static String messageDelta(String stopReason) {
    return "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\""
        + stopReason
        + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":20}}";
  }

  private List<LlmResponse> streamedResponses(String sseBody, LlmRequest request) throws Exception {
    HttpServer server = serve("text/event-stream", sseBody, new AtomicReference<>());
    try {
      return claudeServedBy(server, AnthropicOkHttpClient.builder())
          .generateContent(request, /* stream= */ true)
          .toList()
          .blockingGet();
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void generateContent_streamedStructuredOutput_isStreamedAsJsonText() throws Exception {
    GenerateContentConfig config =
        GenerateContentConfig.builder().responseSchema(cityInfoSchema()).build();
    String body =
        sse(
            "message_start", MESSAGE_START,
            "content_block_start", toolUseStart(0, "toolu_1", "structured_output"),
            "content_block_delta", inputJsonDelta(0, "{\"city\": "),
            "content_block_delta", inputJsonDelta(0, "\"Paris\"}"),
            "content_block_stop", blockStop(0),
            "message_delta", messageDelta("tool_use"),
            "message_stop", MESSAGE_STOP);

    List<LlmResponse> responses = streamedResponses(body, request(config, userText("Capital?")));

    assertThat(responses).hasSize(3);
    assertThat(responses.get(0).content().get().parts().get().get(0).text())
        .hasValue("{\"city\": ");
    assertThat(responses.get(1).content().get().parts().get().get(0).text()).hasValue("\"Paris\"}");
    assertThat(getOnlyElement(responses.get(2).content().get().parts().get()).text())
        .hasValue("{\"city\":\"Paris\"}");
  }

  @Test
  public void generateContent_streamedToolUseCutOffByMaxTokens_returnsAnError() throws Exception {
    String body =
        sse(
            "message_start",
            MESSAGE_START,
            "content_block_start",
            toolUseStart(0, "toolu_1", "saveNote"),
            "content_block_delta",
            inputJsonDelta(0, "{\"text\": \"The ocean is"),
            "content_block_stop",
            blockStop(0),
            "message_delta",
            messageDelta("max_tokens"),
            "message_stop",
            MESSAGE_STOP);

    LlmResponse last =
        getOnlyElement(streamedResponses(body, request(userText("Save a long note."))));

    assertThat(last.content()).isEmpty();
    assertThat(last.errorCode().get().knownEnum()).isEqualTo(FinishReason.Known.MAX_TOKENS);
  }

  /**
   * Sends one request through Claude using a client built from {@code clientBuilder} for a local
   * server, and returns the headers the server received.
   */
  private static Headers headersSentThrough(AnthropicOkHttpClient.Builder clientBuilder)
      throws Exception {
    AtomicReference<Headers> requestHeaders = new AtomicReference<>();
    HttpServer server = serve("application/json", MESSAGE_JSON, requestHeaders);
    try {
      LlmResponse response =
          claudeServedBy(server, clientBuilder)
              .generateContent(request(userText("Ping")), /* stream= */ false)
              .blockingFirst();

      assertThat(response.content().get().parts().get().get(0).text()).hasValue("pong");
      return requestHeaders.get();
    } finally {
      server.stop(0);
    }
  }

  /** Starts a local server that answers every request with {@code body}. */
  private static HttpServer serve(
      String contentType, String body, AtomicReference<Headers> requestHeaders) throws Exception {
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestHeaders.set(exchange.getRequestHeaders());
          byte[] bytes = body.getBytes(UTF_8);
          exchange.getResponseHeaders().set("Content-Type", contentType);
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    return server;
  }

  private static Claude claudeServedBy(
      HttpServer server, AnthropicOkHttpClient.Builder clientBuilder) {
    String baseUrl =
        "http://"
            + InetAddresses.toUriString(server.getAddress().getAddress())
            + ":"
            + server.getAddress().getPort();
    Backend backend =
        new Backend() {
          @Override
          public String baseUrl() {
            return baseUrl;
          }

          @Override
          public void close() {}
        };
    return new Claude("claude-test", clientBuilder.backend(backend).build());
  }

  @Test
  public void generateContent_unsupportedPart_fails() {
    Part fileData =
        Part.builder()
            .fileData(
                FileData.builder().fileUri("gs://bucket/file.png").mimeType("image/png").build())
            .build();

    claude
        .generateContent(request(userParts(fileData)), false)
        .test()
        .assertError(UnsupportedOperationException.class);
  }

  @Test
  public void capabilities_reportNoOutputSchemaWithTools() {
    assertThat(claude.capabilities().outputSchemaAndTools()).isFalse();
  }
}
