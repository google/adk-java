package com.google.adk.models;

import static com.google.adk.models.RedbusADG.cleanForIdentifierPattern;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.MediaModality;
import com.google.genai.types.ModalityTokenCount;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BaseLlm implementation for Sarvam AI models.
 *
 * <p>Sarvam AI exposes an OpenAI-compatible chat completions API. The base URL is read from the
 * {@code SARVAM_API_BASE} environment variable (default {@code https://api.sarvam.ai/v1}) and the
 * API key from {@code SARVAM_API_KEY}.
 *
 * @author Sandeep Belgavi
 */
public class SarvamBaseLM extends BaseLlm {

  public static final String SARVAM_API_BASE_ENV = "SARVAM_API_BASE";
  public static final String SARVAM_API_KEY_ENV = "SARVAM_API_KEY";
  private static final String DEFAULT_BASE_URL = "https://api.sarvam.ai/v1";
  private static final int CONNECT_TIMEOUT_MS = 30_000;
  private static final int READ_TIMEOUT_MS = 120_000;

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper().registerModule(new Jdk8Module());

  private final String baseUrl;
  private static final Logger logger = LoggerFactory.getLogger(SarvamBaseLM.class);

  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  public SarvamBaseLM(String model) {
    super(model);
    this.baseUrl = null;
    warnIfApiKeyMissing();
  }

  public SarvamBaseLM(String model, String baseUrl) {
    super(model);
    this.baseUrl = baseUrl;
    warnIfApiKeyMissing();
  }

  private void warnIfApiKeyMissing() {
    String apiKey = System.getenv(SARVAM_API_KEY_ENV);
    if (apiKey == null || apiKey.isBlank()) {
      logger.warn(
          "SARVAM_API_KEY environment variable is not set. "
              + "Sarvam API calls for model '{}' will fail with 401 Unauthorized.",
          model());
    }
  }

  private String resolveBaseUrl() {
    if (baseUrl != null) {
      return baseUrl;
    }
    String envUrl = System.getenv(SARVAM_API_BASE_ENV);
    return envUrl != null ? envUrl : DEFAULT_BASE_URL;
  }

  private String resolveApiKey() {
    return System.getenv(SARVAM_API_KEY_ENV);
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      return generateContentStream(llmRequest);
    }

    List<Content> contents = ensureLastContentIsUser(llmRequest.contents());

    String systemText = extractSystemText(llmRequest);
    JSONArray messages = buildMessages(systemText, contents);
    JSONArray functions = buildTools(llmRequest);

    boolean lastRespToolExecuted =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();

    float temperature =
        llmRequest.config().flatMap(GenerateContentConfig::temperature).orElse(0.7f);
    Optional<Integer> maxTokens =
        llmRequest.config().flatMap(GenerateContentConfig::maxOutputTokens);

    JSONObject response =
        callChatCompletions(
            this.model(),
            messages,
            lastRespToolExecuted ? null : (functions.length() > 0 ? functions : null),
            temperature,
            maxTokens.orElse(-1),
            false);

    GenerateContentResponseUsageMetadata usageMetadata = extractUsageMetadata(response);

    JSONArray choices = response.optJSONArray("choices");
    if (choices == null || choices.length() == 0) {
      logger.error("Sarvam API returned no choices: {}", response);
      return Flowable.just(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText("")).build())
              .build());
    }

    JSONObject message = choices.getJSONObject(0).getJSONObject("message");
    List<Part> parts = openAiMessageToParts(message);

    LlmResponse.Builder responseBuilder = LlmResponse.builder();

    boolean hasFunctionCall = parts.stream().anyMatch(p -> p.functionCall().isPresent());
    if (hasFunctionCall) {
      Part fcPart = parts.stream().filter(p -> p.functionCall().isPresent()).findFirst().get();
      responseBuilder.content(
          Content.builder().role("model").parts(ImmutableList.of(fcPart)).build());
    } else {
      responseBuilder.content(
          Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build());
    }

    if (usageMetadata != null) {
      responseBuilder.usageMetadata(usageMetadata);
    }

    return Flowable.just(responseBuilder.build());
  }

  private Flowable<LlmResponse> generateContentStream(LlmRequest llmRequest) {
    List<Content> contents = ensureLastContentIsUser(llmRequest.contents());

    String systemText = extractSystemText(llmRequest);
    JSONArray messages = buildMessages(systemText, contents);
    JSONArray functions = buildTools(llmRequest);

    boolean lastRespToolExecuted =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();

    float temperature =
        llmRequest.config().flatMap(GenerateContentConfig::temperature).orElse(0.7f);
    Optional<Integer> maxTokens =
        llmRequest.config().flatMap(GenerateContentConfig::maxOutputTokens);

    final StringBuilder accumulatedText = new StringBuilder();
    final StringBuilder functionCallName = new StringBuilder();
    final StringBuilder functionCallArgs = new StringBuilder();
    final AtomicBoolean inFunctionCall = new AtomicBoolean(false);
    final AtomicBoolean streamCompleted = new AtomicBoolean(false);
    final AtomicInteger inputTokens = new AtomicInteger(0);
    final AtomicInteger outputTokens = new AtomicInteger(0);
    final AtomicInteger promptAudioTokens = new AtomicInteger(0);
    final AtomicInteger completionAudioTokens = new AtomicInteger(0);

    return Flowable.generate(
        () ->
            callChatCompletionsStream(
                this.model(),
                messages,
                lastRespToolExecuted ? null : (functions.length() > 0 ? functions : null),
                temperature,
                maxTokens.orElse(-1)),
        (reader, emitter) -> {
          try {
            if (reader == null || streamCompleted.get()) {
              emitter.onComplete();
              return;
            }

            String line = reader.readLine();
            if (line == null) {
              emitFinalStreamResponse(
                  emitter,
                  accumulatedText,
                  inFunctionCall,
                  functionCallName,
                  functionCallArgs,
                  inputTokens.get(),
                  outputTokens.get(),
                  promptAudioTokens.get(),
                  completionAudioTokens.get());
              emitter.onComplete();
              return;
            }

            if (line.isEmpty()) {
              return;
            }

            if (line.equals("data: [DONE]")) {
              streamCompleted.set(true);
              emitFinalStreamResponse(
                  emitter,
                  accumulatedText,
                  inFunctionCall,
                  functionCallName,
                  functionCallArgs,
                  inputTokens.get(),
                  outputTokens.get(),
                  promptAudioTokens.get(),
                  completionAudioTokens.get());
              emitter.onComplete();
              return;
            }

            if (!line.startsWith("data: ")) {
              return;
            }

            String jsonStr = line.substring(6);
            JSONObject chunk;
            try {
              chunk = new JSONObject(jsonStr);
            } catch (Exception parseEx) {
              logger.warn("Failed to parse Sarvam SSE chunk: {}", jsonStr, parseEx);
              return;
            }

            if (chunk.has("usage") && !chunk.isNull("usage")) {
              JSONObject usage = chunk.getJSONObject("usage");
              inputTokens.set(usage.optInt("prompt_tokens", 0));
              outputTokens.set(usage.optInt("completion_tokens", 0));
              if (usage.has("prompt_tokens_details")) {
                JSONObject pDetails = usage.optJSONObject("prompt_tokens_details");
                if (pDetails != null && pDetails.has("audio_tokens")) {
                  promptAudioTokens.set(pDetails.getInt("audio_tokens"));
                }
              }
              if (usage.has("completion_tokens_details")) {
                JSONObject cDetails = usage.optJSONObject("completion_tokens_details");
                if (cDetails != null && cDetails.has("audio_tokens")) {
                  completionAudioTokens.set(cDetails.getInt("audio_tokens"));
                }
              }
            }

            JSONArray choices = chunk.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
              return;
            }

            JSONObject choice = choices.getJSONObject(0);
            JSONObject delta = choice.optJSONObject("delta");
            if (delta == null) {
              return;
            }

            if (delta.has("content") && !delta.isNull("content")) {
              String text = delta.getString("content");
              if (!text.isEmpty()) {
                accumulatedText.append(text);
                emitter.onNext(createTextResponse(text, true));
              }
            }

            if (delta.has("tool_calls")) {
              inFunctionCall.set(true);
              JSONArray toolCalls = delta.getJSONArray("tool_calls");
              if (toolCalls.length() > 0) {
                JSONObject toolCall = toolCalls.getJSONObject(0);
                JSONObject function = toolCall.optJSONObject("function");
                if (function != null) {
                  if (function.has("name") && !function.isNull("name")) {
                    functionCallName.append(function.getString("name"));
                  }
                  if (function.has("arguments") && !function.isNull("arguments")) {
                    functionCallArgs.append(function.getString("arguments"));
                  }
                }
              }
            }
          } catch (Exception e) {
            logger.error("Error in Sarvam streaming", e);
            emitter.onError(e);
          }
        },
        reader -> {
          try {
            if (reader != null) {
              reader.close();
            }
          } catch (IOException e) {
            logger.error("Error closing stream reader", e);
          }
        });
  }

  private void emitFinalStreamResponse(
      io.reactivex.rxjava3.core.Emitter<LlmResponse> emitter,
      StringBuilder accumulatedText,
      AtomicBoolean inFunctionCall,
      StringBuilder functionCallName,
      StringBuilder functionCallArgs,
      int promptTokens,
      int completionTokens,
      int promptAudioTokens,
      int completionAudioTokens) {

    GenerateContentResponseUsageMetadata usageMetadata =
        buildUsageMetadata(
            promptTokens, completionTokens, promptAudioTokens, completionAudioTokens);

    if (inFunctionCall.get() && functionCallName.length() > 0) {
      try {
        String argsString = functionCallArgs.length() > 0 ? functionCallArgs.toString() : "{}";
        Map<String, Object> args = new JSONObject(argsString).toMap();
        FunctionCall fc =
            FunctionCall.builder().name(functionCallName.toString()).args(args).build();
        Part part = Part.builder().functionCall(fc).build();

        LlmResponse.Builder builder =
            LlmResponse.builder()
                .content(Content.builder().role("model").parts(ImmutableList.of(part)).build());
        if (usageMetadata != null) {
          builder.usageMetadata(usageMetadata);
        }
        emitter.onNext(builder.build());
      } catch (Exception funcEx) {
        logger.error("Error creating function call response from stream", funcEx);
      }
    } else if (accumulatedText.length() > 0) {
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

  // ========== Request Building ==========

  private List<Content> ensureLastContentIsUser(List<Content> contents) {
    if (contents.isEmpty() || !Iterables.getLast(contents).role().orElse("").equals("user")) {
      Content userContent = Content.fromParts(Part.fromText(CONTINUE_OUTPUT_MESSAGE));
      return Stream.concat(contents.stream(), Stream.of(userContent)).collect(toImmutableList());
    }
    return contents;
  }

  private String extractSystemText(LlmRequest llmRequest) {
    return llmRequest
        .config()
        .flatMap(GenerateContentConfig::systemInstruction)
        .flatMap(Content::parts)
        .map(
            parts ->
                parts.stream()
                    .filter(p -> p.text().isPresent())
                    .map(p -> p.text().get())
                    .collect(Collectors.joining("\n")))
        .filter(text -> !text.isEmpty())
        .orElse("");
  }

  private JSONArray buildMessages(String systemText, List<Content> contents) {
    JSONArray messages = new JSONArray();

    if (!systemText.isEmpty()) {
      JSONObject systemMsg = new JSONObject();
      systemMsg.put("role", "system");
      systemMsg.put("content", systemText);
      messages.put(systemMsg);
    }

    for (Content item : contents) {
      String role = item.role().orElse("user");
      List<Part> parts = item.parts().orElse(ImmutableList.of());

      if (parts.isEmpty()) {
        JSONObject msg = new JSONObject();
        msg.put("role", role.equals("model") ? "assistant" : role);
        msg.put("content", item.text());
        messages.put(msg);
        continue;
      }

      Part firstPart = parts.get(0);

      if (firstPart.functionResponse().isPresent()) {
        JSONObject msg = new JSONObject();
        msg.put("role", "tool");
        msg.put("tool_call_id", firstPart.functionResponse().get().name().orElse("call_unknown"));
        msg.put(
            "content",
            new JSONObject(firstPart.functionResponse().get().response().get()).toString());
        messages.put(msg);
      } else if (firstPart.functionCall().isPresent()) {
        // Assistant message that previously requested a tool call
        FunctionCall fc = firstPart.functionCall().get();
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", JSONObject.NULL);

        JSONArray toolCalls = new JSONArray();
        JSONObject toolCall = new JSONObject();
        toolCall.put("id", "call_" + fc.name().orElse("unknown"));
        toolCall.put("type", "function");
        JSONObject function = new JSONObject();
        function.put("name", fc.name().orElse(""));
        function.put("arguments", new JSONObject(fc.args().orElse(Map.of())).toString());
        toolCall.put("function", function);
        toolCalls.put(toolCall);
        msg.put("tool_calls", toolCalls);

        messages.put(msg);
      } else {
        JSONObject msg = new JSONObject();
        msg.put("role", role.equals("model") ? "assistant" : role);
        msg.put("content", item.text());
        messages.put(msg);
      }
    }
    return messages;
  }

  private JSONArray buildTools(LlmRequest llmRequest) {
    JSONArray functions = new JSONArray();
    llmRequest
        .tools()
        .forEach(
            (name, baseTool) -> {
              Optional<FunctionDeclaration> declOpt = baseTool.declaration();
              if (declOpt.isEmpty()) {
                logger.warn("Skipping tool '{}' with missing declaration.", baseTool.name());
                return;
              }

              FunctionDeclaration decl = declOpt.get();
              Map<String, Object> funcMap = new HashMap<>();
              funcMap.put("name", cleanForIdentifierPattern(decl.name().get()));
              funcMap.put("description", cleanForIdentifierPattern(decl.description().orElse("")));

              Optional<Schema> paramsOpt = decl.parameters();
              if (paramsOpt.isPresent()) {
                Schema paramsSchema = paramsOpt.get();
                Map<String, Object> paramsMap = new HashMap<>();
                paramsMap.put("type", "object");

                Optional<Map<String, Schema>> propsOpt = paramsSchema.properties();
                if (propsOpt.isPresent()) {
                  Map<String, Object> propsMap = new HashMap<>();
                  propsOpt
                      .get()
                      .forEach(
                          (key, schema) -> {
                            Map<String, Object> schemaMap =
                                OBJECT_MAPPER.convertValue(
                                    schema, new TypeReference<Map<String, Object>>() {});
                            normalizeTypeStrings(schemaMap);
                            propsMap.put(key, schemaMap);
                          });
                  paramsMap.put("properties", propsMap);
                }

                paramsSchema
                    .required()
                    .ifPresent(requiredList -> paramsMap.put("required", requiredList));
                funcMap.put("parameters", paramsMap);
              }

              JSONObject toolWrapper = new JSONObject();
              toolWrapper.put("type", "function");
              toolWrapper.put("function", new JSONObject(funcMap));
              functions.put(toolWrapper);
            });
    return functions;
  }

  // ========== HTTP Transport ==========

  private JSONObject callChatCompletions(
      String model,
      JSONArray messages,
      JSONArray tools,
      float temperature,
      int maxTokens,
      boolean stream) {
    try {
      String apiUrl = resolveBaseUrl() + "/chat/completions";
      String apiKey = resolveApiKey();

      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put("messages", messages);
      payload.put("temperature", temperature);
      payload.put("stream", stream);

      if (maxTokens > 0) {
        payload.put("max_tokens", maxTokens);
      }

      if (tools != null && tools.length() > 0) {
        payload.put("tools", tools);
        payload.put("tool_choice", "auto");
      }

      String jsonString = payload.toString();
      logger.debug("Sarvam request payload size: {} bytes", jsonString.length());

      HttpURLConnection conn = openConnection(apiUrl, apiKey);
      conn.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);

      try (OutputStream os = conn.getOutputStream();
          OutputStreamWriter writer = new OutputStreamWriter(os, "UTF-8")) {
        writer.write(jsonString);
        writer.flush();
      }

      int responseCode = conn.getResponseCode();
      logger.info("Sarvam response code: {} for model: {}", responseCode, model);

      InputStream inputStream =
          (responseCode < 400) ? conn.getInputStream() : conn.getErrorStream();

      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          sb.append(line);
        }

        if (responseCode >= 400) {
          logger.error("Sarvam API error: status={} body={}", responseCode, sb);
          return new JSONObject().put("error", sb.toString());
        }

        JSONObject responseJson = new JSONObject(sb.toString());
        conn.disconnect();
        return responseJson;
      }
    } catch (Exception ex) {
      logger.error("Error calling Sarvam chat completions API", ex);
      return new JSONObject();
    }
  }

  private BufferedReader callChatCompletionsStream(
      String model, JSONArray messages, JSONArray tools, float temperature, int maxTokens) {
    try {
      String apiUrl = resolveBaseUrl() + "/chat/completions";
      String apiKey = resolveApiKey();

      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put("messages", messages);
      payload.put("temperature", temperature);
      payload.put("stream", true);

      // Request token usage in streaming responses
      JSONObject streamOptions = new JSONObject();
      streamOptions.put("include_usage", true);
      payload.put("stream_options", streamOptions);

      if (maxTokens > 0) {
        payload.put("max_tokens", maxTokens);
      }

      if (tools != null && tools.length() > 0) {
        payload.put("tools", tools);
        payload.put("tool_choice", "auto");
      }

      String jsonString = payload.toString();

      HttpURLConnection conn = openConnection(apiUrl, apiKey);
      conn.setRequestProperty("Accept", "text/event-stream");
      conn.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);

      try (OutputStream os = conn.getOutputStream();
          OutputStreamWriter writer = new OutputStreamWriter(os, "UTF-8")) {
        writer.write(jsonString);
        writer.flush();
      }

      int responseCode = conn.getResponseCode();
      logger.info("Sarvam streaming response code: {} for model: {}", responseCode, model);

      if (responseCode >= 200 && responseCode < 300) {
        return new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
      } else {
        try (InputStream errorStream = conn.getErrorStream();
            BufferedReader errorReader =
                new BufferedReader(new InputStreamReader(errorStream, "UTF-8"))) {
          StringBuilder errorResponse = new StringBuilder();
          String errorLine;
          while ((errorLine = errorReader.readLine()) != null) {
            errorResponse.append(errorLine);
          }
          logger.error("Sarvam streaming failed: status={} body={}", responseCode, errorResponse);
        }
        conn.disconnect();
        return null;
      }
    } catch (IOException ex) {
      logger.error("Error in Sarvam streaming request", ex);
      return null;
    }
  }

  private HttpURLConnection openConnection(String apiUrl, String apiKey) throws IOException {
    URL url = new URL(apiUrl);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
    conn.setReadTimeout(READ_TIMEOUT_MS);
    conn.setDoOutput(true);
    if (apiKey != null && !apiKey.isEmpty()) {
      conn.setRequestProperty("Authorization", "Bearer " + apiKey);
    }
    return conn;
  }

  // ========== Response Parsing ==========

  private LlmResponse createTextResponse(String text, boolean partial) {
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .partial(partial)
        .build();
  }

  private GenerateContentResponseUsageMetadata extractUsageMetadata(JSONObject response) {
    if (response == null || !response.has("usage")) {
      return null;
    }
    try {
      JSONObject usage = response.getJSONObject("usage");
      int promptTokens = usage.optInt("prompt_tokens", 0);
      int completionTokens = usage.optInt("completion_tokens", 0);
      int totalTokens = usage.optInt("total_tokens", promptTokens + completionTokens);

      if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
        logger.info(
            "Sarvam token usage: prompt={}, completion={}, total={}",
            promptTokens,
            completionTokens,
            totalTokens);
        GenerateContentResponseUsageMetadata.Builder builder =
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(promptTokens)
                .candidatesTokenCount(completionTokens)
                .totalTokenCount(totalTokens);

        if (usage.has("prompt_tokens_details")) {
          JSONObject pDetails = usage.optJSONObject("prompt_tokens_details");
          if (pDetails != null && pDetails.has("audio_tokens")) {
            builder.promptTokensDetails(
                ImmutableList.of(
                    ModalityTokenCount.builder()
                        .modality(MediaModality.Known.AUDIO)
                        .tokenCount(pDetails.getInt("audio_tokens"))
                        .build()));
          }
        }
        if (usage.has("completion_tokens_details")) {
          JSONObject cDetails = usage.optJSONObject("completion_tokens_details");
          if (cDetails != null && cDetails.has("audio_tokens")) {
            builder.candidatesTokensDetails(
                ImmutableList.of(
                    ModalityTokenCount.builder()
                        .modality(MediaModality.Known.AUDIO)
                        .tokenCount(cDetails.getInt("audio_tokens"))
                        .build()));
          }
        }
        return builder.build();
      }
    } catch (Exception e) {
      logger.warn("Failed to parse token usage from Sarvam response", e);
    }
    return null;
  }

  private GenerateContentResponseUsageMetadata buildUsageMetadata(
      int promptTokens, int completionTokens, int promptAudioTokens, int completionAudioTokens) {
    int totalTokens = promptTokens + completionTokens;
    if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
      GenerateContentResponseUsageMetadata.Builder builder =
          GenerateContentResponseUsageMetadata.builder()
              .promptTokenCount(promptTokens)
              .candidatesTokenCount(completionTokens)
              .totalTokenCount(totalTokens);

      if (promptAudioTokens > 0) {
        builder.promptTokensDetails(
            ImmutableList.of(
                ModalityTokenCount.builder()
                    .modality(MediaModality.Known.AUDIO)
                    .tokenCount(promptAudioTokens)
                    .build()));
      }
      if (completionAudioTokens > 0) {
        builder.candidatesTokensDetails(
            ImmutableList.of(
                ModalityTokenCount.builder()
                    .modality(MediaModality.Known.AUDIO)
                    .tokenCount(completionAudioTokens)
                    .build()));
      }
      return builder.build();
    }
    return null;
  }

  /**
   * Converts an OpenAI-format message JSON to ADK Part(s). Handles both text content and tool_calls
   * in a single message.
   */
  static List<Part> openAiMessageToParts(JSONObject message) {
    List<Part> parts = new ArrayList<>();

    if (message.has("tool_calls")) {
      JSONArray toolCalls = message.optJSONArray("tool_calls");
      if (toolCalls != null && toolCalls.length() > 0) {
        JSONObject toolCall = toolCalls.getJSONObject(0);
        JSONObject function = toolCall.optJSONObject("function");
        if (function != null) {
          String name = function.optString("name", null);
          String argsStr = function.optString("arguments", "{}");
          if (name != null) {
            Map<String, Object> args = new JSONObject(argsStr).toMap();
            FunctionCall fc = FunctionCall.builder().name(name).args(args).build();
            parts.add(Part.builder().functionCall(fc).build());
            return parts;
          }
        }
      }
    }

    if (message.has("content") && !message.isNull("content")) {
      parts.add(Part.builder().text(message.getString("content")).build());
    } else {
      parts.add(Part.builder().text("").build());
    }

    return parts;
  }

  @SuppressWarnings("unchecked")
  private void normalizeTypeStrings(Map<String, Object> valueDict) {
    if (valueDict == null) {
      return;
    }
    if (valueDict.containsKey("type") && valueDict.get("type") instanceof String) {
      valueDict.put("type", ((String) valueDict.get("type")).toLowerCase());
    }
    if (valueDict.containsKey("items") && valueDict.get("items") instanceof Map) {
      Map<String, Object> itemsMap = (Map<String, Object>) valueDict.get("items");
      normalizeTypeStrings(itemsMap);
      if (itemsMap.containsKey("properties") && itemsMap.get("properties") instanceof Map) {
        Map<String, Object> properties = (Map<String, Object>) itemsMap.get("properties");
        for (Object value : properties.values()) {
          if (value instanceof Map) {
            normalizeTypeStrings((Map<String, Object>) value);
          }
        }
      }
    }
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return new GenericLlmConnection(this, llmRequest);
  }
}
