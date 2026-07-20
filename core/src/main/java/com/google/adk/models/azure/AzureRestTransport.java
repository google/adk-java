package com.google.adk.models.azure;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.GenericLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure transport implementation for the HTTP-based Responses API.
 *
 * <p>Handles both non-streaming and SSE streaming requests to Azure OpenAI.
 */
public final class AzureRestTransport implements AzureTransport {

  private static final Logger logger = LoggerFactory.getLogger(AzureRestTransport.class);

  private static final int CONNECT_TIMEOUT_SECONDS = 60;
  private static final int READ_TIMEOUT_SECONDS = 180;

  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  private static final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_2)
          .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
          .build();

  @Override
  public boolean supports(String modelName) {
    if (modelName == null) {
      return false;
    }
    return !AzureModelUtils.isRealtimeModel(modelName)
        && !AzureModelUtils.isTranslateModel(modelName);
  }

  @Override
  public Flowable<LlmResponse> generateContent(
      LlmRequest request, AzureConfig config, boolean stream) {
    return stream ? generateContentStream(request, config) : generateContentSync(request, config);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest request, AzureConfig config) {
    BaseLlm proxy =
        new BaseLlm(config.modelName()) {
          @Override
          public Flowable<LlmResponse> generateContent(LlmRequest req, boolean stream) {
            return AzureRestTransport.this.generateContent(req, config, stream);
          }

          @Override
          public BaseLlmConnection connect(LlmRequest req) {
            throw new UnsupportedOperationException("Nested connect not supported");
          }
        };
    return new GenericLlmConnection(proxy, request);
  }

  // ==================== Non-streaming ====================

  private Flowable<LlmResponse> generateContentSync(LlmRequest llmRequest, AzureConfig config) {
    List<Content> contents = ensureLastContentIsUser(llmRequest.contents());
    String instructions = AzureRequestConverter.extractInstructions(llmRequest);
    JSONArray inputItems = buildInputItems(contents);
    JSONArray tools = AzureRequestConverter.buildTools(llmRequest);

    boolean lastRespToolExecuted =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();

    Optional<Float> temperature = llmRequest.config().flatMap(GenerateContentConfig::temperature);
    Optional<Integer> maxTokens =
        llmRequest.config().flatMap(GenerateContentConfig::maxOutputTokens);

    JSONObject payload = new JSONObject();
    payload.put("model", config.modelName());
    payload.put("input", inputItems);
    if (!instructions.isEmpty()) {
      payload.put("instructions", instructions);
    }
    temperature.ifPresent(t -> payload.put("temperature", t));
    payload.put("stream", false);
    payload.put("store", false);
    payload.put("reasoning", new JSONObject().put("summary", "auto"));
    if (maxTokens.isPresent() && maxTokens.get() > 0) {
      payload.put("max_output_tokens", maxTokens.get());
    }
    if (!lastRespToolExecuted && tools.length() > 0) {
      payload.put("tools", tools);
    }

    logger.debug("Azure Responses API request payload size: {} bytes", payload.toString().length());

    JSONObject response = callApi(payload, config);

    if (response.has("error") && !response.isNull("error")) {
      JSONObject error = response.getJSONObject("error");
      String message = error.optString("message", response.toString());
      logger.error("Azure Responses API error: {}", response);
      return Flowable.error(new IllegalStateException("Azure Responses API error: " + message));
    }

    GenerateContentResponseUsageMetadata usageMetadata = extractUsageMetadata(response);
    LlmResponse llmResponse = parseOutputToLlmResponse(response, usageMetadata);
    return Flowable.just(llmResponse);
  }

  // ==================== Streaming ====================

  private Flowable<LlmResponse> generateContentStream(LlmRequest llmRequest, AzureConfig config) {
    List<Content> contents = ensureLastContentIsUser(llmRequest.contents());
    String instructions = AzureRequestConverter.extractInstructions(llmRequest);
    JSONArray inputItems = buildInputItems(contents);
    JSONArray tools = AzureRequestConverter.buildTools(llmRequest);

    boolean lastRespToolExecuted =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();

    Optional<Float> temperature = llmRequest.config().flatMap(GenerateContentConfig::temperature);
    Optional<Integer> maxTokens =
        llmRequest.config().flatMap(GenerateContentConfig::maxOutputTokens);

    JSONObject payload = new JSONObject();
    payload.put("model", config.modelName());
    payload.put("input", inputItems);
    if (!instructions.isEmpty()) {
      payload.put("instructions", instructions);
    }
    temperature.ifPresent(t -> payload.put("temperature", t));
    payload.put("stream", true);
    payload.put("store", false);
    payload.put("reasoning", new JSONObject().put("summary", "auto"));
    if (maxTokens.isPresent() && maxTokens.get() > 0) {
      payload.put("max_output_tokens", maxTokens.get());
    }
    if (!lastRespToolExecuted && tools.length() > 0) {
      payload.put("tools", tools);
    }

    final StringBuilder accumulatedText = new StringBuilder();
    final StringBuilder reasoningSummary = new StringBuilder();
    final StringBuilder functionCallName = new StringBuilder();
    final StringBuilder functionCallCallId = new StringBuilder();
    final StringBuilder functionCallArgs = new StringBuilder();
    final AtomicBoolean inFunctionCall = new AtomicBoolean(false);
    final AtomicBoolean finalTextEmitted = new AtomicBoolean(false);
    final AtomicInteger inputTokens = new AtomicInteger(0);
    final AtomicInteger outputTokens = new AtomicInteger(0);

    logger.debug("Starting streaming request for model: {}", config.modelName());
    logger.debug("Streaming payload size: {} bytes", payload.toString().length());

    return Flowable.create(
        emitter -> {
          BufferedReader reader = null;
          try {
            logger.debug("Opening SSE connection...");
            reader = callApiStream(payload, config);
            if (reader == null) {
              logger.warn("Azure SSE reader is null — stream failed to open.");
              emitter.onComplete();
              return;
            }
            logger.debug("SSE connection opened successfully.");
            long streamStartMs = System.currentTimeMillis();
            int chunkCount = 0;

            String lastEventName = null;
            String line;
            while ((line = reader.readLine()) != null) {
              if (emitter.isCancelled()) {
                logger.debug("Emitter cancelled, breaking out of read loop.");
                break;
              }

              logger.debug(
                  "SSE raw: {}", line.length() > 200 ? line.substring(0, 200) + "..." : line);

              if (line.isEmpty()) continue;
              if (line.startsWith("event:")) {
                lastEventName = line.substring(6).trim();
                continue;
              }
              if (!line.startsWith("data:")) continue;

              String jsonStr = line.substring(5).trim();
              if (jsonStr.equals("[DONE]")) {
                long elapsed = System.currentTimeMillis() - streamStartMs;
                logger.debug(
                    "[DONE] marker received after {}ms, total chunks: {}", elapsed, chunkCount);
                break;
              }

              chunkCount++;
              JSONObject event;
              try {
                event = new JSONObject(jsonStr);
              } catch (JSONException e) {
                logger.warn("Failed to parse SSE chunk #{}: {}", chunkCount, jsonStr);
                continue;
              }

              String eventType = event.optString("type", "");
              if (eventType.isEmpty() && lastEventName != null) {
                eventType = lastEventName;
              }
              lastEventName = null;

              logger.debug(
                  "SSE chunk #{} eventType='{}' keys={}", chunkCount, eventType, event.keySet());

              switch (eventType) {
                case "response.output_item.added":
                  {
                    JSONObject item = event.optJSONObject("item");
                    if (item == null) break;
                    String itemType = item.optString("type", "");
                    if ("function_call".equals(itemType)) {
                      inFunctionCall.set(true);
                      String name = item.optString("name", "");
                      String callId = item.optString("call_id", "");
                      logger.debug("Function call starting: name='{}' callId='{}'", name, callId);
                      if (!name.isEmpty()) functionCallName.append(name);
                      if (!callId.isEmpty()) functionCallCallId.append(callId);
                    } else if ("reasoning".equals(itemType)) {
                      emitter.onNext(
                          LlmResponse.builder()
                              .content(
                                  Content.builder()
                                      .role("model")
                                      .parts(Part.fromText("\ud83e\udde0 Thinking...\n"))
                                      .build())
                              .partial(true)
                              .build());
                    }
                    break;
                  }

                case "response.reasoning_summary_text.delta":
                  {
                    String delta = event.optString("delta", "");
                    if (!delta.isEmpty()) {
                      reasoningSummary.append(delta);
                      emitter.onNext(
                          LlmResponse.builder()
                              .content(
                                  Content.builder()
                                      .role("model")
                                      .parts(Part.fromText(delta))
                                      .build())
                              .partial(true)
                              .build());
                    }
                    break;
                  }

                case "response.reasoning_summary_text.done":
                  {
                    emitter.onNext(
                        LlmResponse.builder()
                            .content(
                                Content.builder()
                                    .role("model")
                                    .parts(Part.fromText("\n\n"))
                                    .build())
                            .partial(true)
                            .build());
                    break;
                  }

                case "response.output_text.delta":
                  {
                    String delta = extractTextDeltaFromStreamEvent(event);
                    if (!delta.isEmpty()) {
                      accumulatedText.append(delta);
                      emitter.onNext(
                          LlmResponse.builder()
                              .content(
                                  Content.builder()
                                      .role("model")
                                      .parts(Part.fromText(delta))
                                      .build())
                              .partial(true)
                              .build());
                    }
                    break;
                  }

                case "response.output_text.done":
                  {
                    String fullText = event.optString("text", "");
                    if (!fullText.isEmpty()) {
                      accumulatedText.setLength(0);
                      accumulatedText.append(fullText);
                      finalTextEmitted.set(true);
                      String finalContent = fullText;
                      if (reasoningSummary.length() > 0) {
                        finalContent =
                            "\ud83e\udde0 **Thinking:**\n> "
                                + reasoningSummary.toString().replace("\n", "\n> ")
                                + "\n\n"
                                + fullText;
                      }
                      emitter.onNext(
                          LlmResponse.builder()
                              .content(
                                  Content.builder()
                                      .role("model")
                                      .parts(Part.fromText(finalContent))
                                      .build())
                              .partial(false)
                              .build());
                    }
                    break;
                  }

                case "response.output_item.done":
                  {
                    if (finalTextEmitted.get()) break;
                    JSONObject item = event.optJSONObject("item");
                    if (item != null && "message".equals(item.optString("type"))) {
                      String fullText = extractTextFromOutputMessageItem(item);
                      if (!fullText.isEmpty()) {
                        accumulatedText.setLength(0);
                        accumulatedText.append(fullText);
                        finalTextEmitted.set(true);
                        String finalContent = fullText;
                        if (reasoningSummary.length() > 0) {
                          finalContent =
                              "\ud83e\udde0 **Thinking:**\n> "
                                  + reasoningSummary.toString().replace("\n", "\n> ")
                                  + "\n\n"
                                  + fullText;
                        }
                        emitter.onNext(
                            LlmResponse.builder()
                                .content(
                                    Content.builder()
                                        .role("model")
                                        .parts(Part.fromText(finalContent))
                                        .build())
                                .partial(false)
                                .build());
                      }
                    }
                    break;
                  }

                case "response.function_call_arguments.delta":
                  {
                    String delta = extractTextDeltaFromStreamEvent(event);
                    if (!delta.isEmpty()) {
                      functionCallArgs.append(delta);
                    }
                    break;
                  }

                case "response.function_call_arguments.done":
                  {
                    if (functionCallName.length() > 0) {
                      String argsStr =
                          functionCallArgs.length() > 0 ? functionCallArgs.toString() : "{}";
                      Map<String, Object> args;
                      try {
                        args = new JSONObject(argsStr).toMap();
                      } catch (JSONException e) {
                        logger.warn("Failed to parse function args: {}", argsStr);
                        args = Map.of();
                      }
                      FunctionCall fc =
                          FunctionCall.builder()
                              .name(functionCallName.toString())
                              .args(args)
                              .build();
                      emitter.onNext(
                          LlmResponse.builder()
                              .content(
                                  Content.builder()
                                      .role("model")
                                      .parts(
                                          ImmutableList.of(Part.builder().functionCall(fc).build()))
                                      .build())
                              .partial(false)
                              .build());
                    }
                    break;
                  }

                case "response.completed":
                  {
                    JSONObject resp = event.optJSONObject("response");
                    if (resp != null) {
                      JSONObject usage = resp.optJSONObject("usage");
                      if (usage != null) {
                        inputTokens.set(usage.optInt("input_tokens", 0));
                        outputTokens.set(usage.optInt("output_tokens", 0));
                        logger.debug(
                            "Stream token usage — input: {}, output: {}",
                            inputTokens.get(),
                            outputTokens.get());
                      }
                    }
                    break;
                  }

                default:
                  break;
              }
            }

            long totalElapsed = System.currentTimeMillis() - streamStartMs;
            logger.debug(
                "Stream read loop finished — elapsed: {}ms, chunks: {}, accumulatedText: {} chars,"
                    + " finalTextEmitted: {}, inFunctionCall: {}",
                totalElapsed,
                chunkCount,
                accumulatedText.length(),
                finalTextEmitted.get(),
                inFunctionCall.get());

            if (!emitter.isCancelled()) {
              if (!finalTextEmitted.get()) {
                emitFinalStreamResponse(
                    emitter,
                    accumulatedText,
                    inFunctionCall,
                    functionCallName,
                    functionCallArgs,
                    inputTokens.get(),
                    outputTokens.get());
              }
              emitter.onComplete();
            }
          } catch (IOException e) {
            logger.error("IOException in Azure stream", e);
            if (!emitter.isCancelled()) emitter.onError(e);
          } catch (Exception e) {
            logger.error("Error in Azure streaming", e);
            if (!emitter.isCancelled()) emitter.onError(e);
          } finally {
            if (reader != null) {
              try {
                reader.close();
              } catch (IOException e) {
                logger.error("Error closing stream reader", e);
              }
            }
          }
        },
        io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
  }

  // ==================== Helpers ====================

  private static String extractTextDeltaFromStreamEvent(JSONObject event) {
    if (event == null || event.isNull("delta")) {
      return "";
    }
    Object delta = event.opt("delta");
    if (delta instanceof String) {
      return (String) delta;
    }
    if (delta instanceof JSONObject) {
      JSONObject o = (JSONObject) delta;
      return o.optString("text", o.optString("content", ""));
    }
    return "";
  }

  private static String extractTextFromOutputMessageItem(JSONObject messageItem) {
    JSONArray content = messageItem.optJSONArray("content");
    if (content == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < content.length(); i++) {
      JSONObject part = content.optJSONObject(i);
      if (part == null) continue;
      String pType = part.optString("type", "");
      if ("output_text".equals(pType) || "text".equals(pType)) {
        sb.append(part.optString("text", ""));
      }
    }
    return sb.toString();
  }

  private void emitFinalStreamResponse(
      io.reactivex.rxjava3.core.Emitter<LlmResponse> emitter,
      StringBuilder accumulatedText,
      AtomicBoolean inFunctionCall,
      StringBuilder functionCallName,
      StringBuilder functionCallArgs,
      int promptTokens,
      int completionTokens) {

    GenerateContentResponseUsageMetadata usageMetadata =
        buildUsageMetadata(promptTokens, completionTokens);

    if (inFunctionCall.get() && functionCallName.length() > 0) {
      return;
    }

    if (accumulatedText.length() > 0) {
      LlmResponse.Builder builder =
          LlmResponse.builder()
              .content(
                  Content.builder()
                      .role("model")
                      .parts(Part.fromText(accumulatedText.toString()))
                      .build())
              .partial(false);
      if (usageMetadata != null) {
        builder.usageMetadata(usageMetadata);
      }
      emitter.onNext(builder.build());
    }
  }

  private List<Content> ensureLastContentIsUser(List<Content> contents) {
    if (contents.isEmpty() || !Iterables.getLast(contents).role().orElse("").equals("user")) {
      Content userContent = Content.fromParts(Part.fromText(CONTINUE_OUTPUT_MESSAGE));
      return Stream.concat(contents.stream(), Stream.of(userContent)).collect(toImmutableList());
    }
    return contents;
  }

  private JSONArray buildInputItems(List<Content> contents) {
    JSONArray items = new JSONArray();

    for (Content item : contents) {
      String role = item.role().orElse("user");
      List<Part> parts = item.parts().orElse(ImmutableList.of());

      if (parts.isEmpty()) {
        JSONObject msg = new JSONObject();
        msg.put("role", role.equals("model") ? "assistant" : role);
        msg.put("content", item.text());
        items.put(msg);
        continue;
      }

      Part firstPart = parts.get(0);

      if (firstPart.functionResponse().isPresent()) {
        JSONObject output = new JSONObject();
        output.put("type", "function_call_output");
        output.put(
            "call_id", "call_" + firstPart.functionResponse().get().name().orElse("unknown"));
        output.put(
            "output",
            new JSONObject(firstPart.functionResponse().get().response().get()).toString());
        items.put(output);
      } else if (firstPart.functionCall().isPresent()) {
        FunctionCall fc = firstPart.functionCall().get();
        JSONObject fcItem = new JSONObject();
        fcItem.put("type", "function_call");
        fcItem.put("call_id", "call_" + fc.name().orElse("unknown"));
        fcItem.put("name", fc.name().orElse(""));
        fcItem.put("arguments", new JSONObject(fc.args().orElse(Map.of())).toString());
        items.put(fcItem);
      } else {
        JSONObject msg = new JSONObject();
        msg.put("role", role.equals("model") ? "assistant" : role);
        msg.put("content", item.text());
        items.put(msg);
      }
    }
    return items;
  }

  // ==================== HTTP transport ====================

  private JSONObject callApi(JSONObject payload, AzureConfig config) {
    try {
      String jsonString = payload.toString();

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.responseEndpoint()))
              .header("Content-Type", "application/json; charset=UTF-8")
              .header("api-key", config.apiKey())
              .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
              .POST(HttpRequest.BodyPublishers.ofString(jsonString, StandardCharsets.UTF_8))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      int statusCode = response.statusCode();
      logger.info("Azure Responses API status: {} for model: {}", statusCode, config.modelName());

      if (statusCode >= 200 && statusCode < 300) {
        return new JSONObject(response.body());
      } else {
        logger.error("Azure API error: status={} body={}", statusCode, response.body());
        try {
          return new JSONObject(response.body());
        } catch (JSONException e) {
          return new JSONObject().put("error", response.body());
        }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      logger.error("HTTP request interrupted for Azure Responses API", ex);
      return new JSONObject().put("error", ex.getMessage());
    } catch (IOException ex) {
      logger.error("HTTP request failed for Azure Responses API", ex);
      return new JSONObject().put("error", ex.getMessage());
    }
  }

  private BufferedReader callApiStream(JSONObject payload, AzureConfig config) {
    try {
      String jsonString = payload.toString();

      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(config.responseEndpoint()))
              .header("Content-Type", "application/json; charset=UTF-8")
              .header("api-key", config.apiKey())
              .header("Accept", "text/event-stream")
              .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
              .POST(HttpRequest.BodyPublishers.ofString(jsonString, StandardCharsets.UTF_8))
              .build();

      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

      int statusCode = response.statusCode();
      logger.info(
          "Azure Responses API streaming status: {} for model: {}", statusCode, config.modelName());

      if (statusCode >= 200 && statusCode < 300) {
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
      } else {
        try (BufferedReader errorReader =
            new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
          StringBuilder errorBody = new StringBuilder();
          String errorLine;
          while ((errorLine = errorReader.readLine()) != null) {
            errorBody.append(errorLine);
          }
          logger.error("Azure streaming failed: status={} body={}", statusCode, errorBody);
        }
        return null;
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      logger.error("HTTP request interrupted for Azure streaming", ex);
      return null;
    } catch (IOException ex) {
      logger.error("HTTP request failed for Azure streaming", ex);
      return null;
    }
  }

  // ==================== Response parsing ====================

  private LlmResponse parseOutputToLlmResponse(
      JSONObject response, GenerateContentResponseUsageMetadata usageMetadata) {

    JSONArray output = response.optJSONArray("output");
    if (output == null || output.length() == 0) {
      logger.warn("Azure Responses API returned empty output: {}", response);
      return LlmResponse.builder()
          .content(Content.builder().role("model").parts(Part.fromText("")).build())
          .build();
    }

    List<Part> parts = new ArrayList<>();

    for (int i = 0; i < output.length(); i++) {
      JSONObject item = output.getJSONObject(i);
      String type = item.optString("type", "");

      switch (type) {
        case "message":
          {
            JSONArray content = item.optJSONArray("content");
            if (content != null) {
              for (int j = 0; j < content.length(); j++) {
                JSONObject contentItem = content.getJSONObject(j);
                if ("output_text".equals(contentItem.optString("type"))) {
                  parts.add(Part.fromText(contentItem.optString("text", "")));
                }
              }
            }
            break;
          }

        case "function_call":
          {
            String name = item.optString("name", null);
            String argsStr = item.optString("arguments", "{}");
            if (name != null) {
              Map<String, Object> args;
              try {
                args = new JSONObject(argsStr).toMap();
              } catch (JSONException e) {
                logger.warn("Failed to parse function arguments: {}", argsStr);
                args = Map.of();
              }
              FunctionCall fc = FunctionCall.builder().name(name).args(args).build();
              parts.add(Part.builder().functionCall(fc).build());
            }
            break;
          }

        default:
          break;
      }
    }

    if (parts.isEmpty()) {
      parts.add(Part.fromText(""));
    }

    boolean hasFunctionCall = parts.stream().anyMatch(p -> p.functionCall().isPresent());

    LlmResponse.Builder builder = LlmResponse.builder();
    if (hasFunctionCall) {
      Part fcPart = parts.stream().filter(p -> p.functionCall().isPresent()).findFirst().get();
      builder.content(Content.builder().role("model").parts(ImmutableList.of(fcPart)).build());
    } else {
      builder.content(Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build());
    }

    if (usageMetadata != null) {
      builder.usageMetadata(usageMetadata);
    }

    return builder.build();
  }

  private GenerateContentResponseUsageMetadata extractUsageMetadata(JSONObject response) {
    if (response == null || !response.has("usage")) {
      return null;
    }
    try {
      JSONObject usage = response.getJSONObject("usage");
      int inputTok = usage.optInt("input_tokens", 0);
      int outputTok = usage.optInt("output_tokens", 0);
      int totalTok = usage.optInt("total_tokens", inputTok + outputTok);

      if (totalTok > 0 || inputTok > 0 || outputTok > 0) {
        logger.info(
            "Azure token usage: input={}, output={}, total={}", inputTok, outputTok, totalTok);
        return GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(inputTok)
            .candidatesTokenCount(outputTok)
            .totalTokenCount(totalTok)
            .build();
      }
    } catch (Exception e) {
      logger.warn("Failed to parse token usage from Azure response", e);
    }
    return null;
  }

  private GenerateContentResponseUsageMetadata buildUsageMetadata(int inputTok, int outputTok) {
    int totalTok = inputTok + outputTok;
    if (totalTok > 0 || inputTok > 0 || outputTok > 0) {
      return GenerateContentResponseUsageMetadata.builder()
          .promptTokenCount(inputTok)
          .candidatesTokenCount(outputTok)
          .totalTokenCount(totalTok)
          .build();
    }
    return null;
  }
}
