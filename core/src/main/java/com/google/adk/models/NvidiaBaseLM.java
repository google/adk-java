package com.google.adk.models;

import static com.google.adk.models.RedbusADG.cleanForIdentifierPattern;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NVIDIA NIM API integration for ADK.
 *
 * <p>Connects to NVIDIA's OpenAI-compatible chat completions endpoint
 * (https://integrate.api.nvidia.com/v1/chat/completions) supporting both streaming (SSE) and
 * non-streaming modes.
 *
 * <p>Configuration via environment variables:
 *
 * <ul>
 *   <li>{@code NVIDIA_API_KEY} - Bearer token for authentication
 *   <li>{@code NVIDIA_BASE_URL} - (optional) Override the base URL
 * </ul>
 *
 * @author Manoj Kumar
 */
public class NvidiaBaseLM extends BaseLlm {

  private static final Logger logger = LoggerFactory.getLogger(NvidiaBaseLM.class);

  private static final String API_KEY_ENV = "NVIDIA_API_KEY";
  private static final String BASE_URL_ENV = "NVIDIA_BASE_URL";
  private static final String DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1";

  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  private static final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_2)
          .connectTimeout(Duration.ofSeconds(60))
          .build();

  private String baseUrl;
  private int maxTokens = 4096;
  private float temperature = 0.7f;
  private float topP = 0.95f;

  public NvidiaBaseLM(String model) {
    super(model);
  }

  public NvidiaBaseLM(String model, String baseUrl) {
    super(model);
    this.baseUrl = baseUrl;
  }

  public int getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(int maxTokens) {
    this.maxTokens = maxTokens;
  }

  public float getTemperature() {
    return temperature;
  }

  public void setTemperature(float temperature) {
    this.temperature = temperature;
  }

  public float getTopP() {
    return topP;
  }

  public void setTopP(float topP) {
    this.topP = topP;
  }

  private String resolveBaseUrl() {
    if (this.baseUrl != null) {
      return this.baseUrl;
    }
    String envUrl = System.getenv(BASE_URL_ENV);
    return (envUrl != null && !envUrl.isEmpty()) ? envUrl : DEFAULT_BASE_URL;
  }

  private String resolveApiKey() {
    String apiKey = System.getenv(API_KEY_ENV);
    if (apiKey == null || apiKey.isEmpty()) {
      throw new RuntimeException(
          "Environment variable '"
              + API_KEY_ENV
              + "' is not set. "
              + "Please set it to your NVIDIA API key.");
    }
    return apiKey;
  }

  // --- Continued in the generateContent methods below ---

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      return generateContentStream(llmRequest);
    }
    return generateContentNonStream(llmRequest);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return new GenericLlmConnection(this, llmRequest);
  }

  // =========================================================================
  // NON-STREAMING
  // =========================================================================

  private Flowable<LlmResponse> generateContentNonStream(LlmRequest llmRequest) {
    List<Content> contents = ensureLastMessageIsUser(llmRequest.contents());
    String systemText = extractSystemText(llmRequest);
    JSONArray messages = buildMessages(systemText, contents);
    JSONArray tools = buildTools(llmRequest, contents);

    float temp =
        llmRequest.config().flatMap(GenerateContentConfig::temperature).orElse(this.temperature);

    JSONObject payload = new JSONObject();
    payload.put("model", this.model());
    payload.put("messages", messages);
    payload.put("max_tokens", this.maxTokens);
    payload.put("temperature", temp);
    payload.put("top_p", this.topP);
    payload.put("stream", false);
    if (tools != null) {
      payload.put("tools", tools);
    }

    logger.debug("NVIDIA non-stream payload: {}", payload.toString(2));

    JSONObject responseJson = callNvidia(payload, false);
    GenerateContentResponseUsageMetadata usageMetadata = parseUsageMetadata(responseJson);

    // Parse choices[0].message
    JSONObject choice0 =
        responseJson.optJSONArray("choices") != null
            ? responseJson.getJSONArray("choices").optJSONObject(0)
            : null;

    if (choice0 == null) {
      logger.error("NVIDIA response missing choices: {}", responseJson);
      return Flowable.just(
          LlmResponse.builder()
              .content(Content.builder().role("model").parts(Part.fromText("")).build())
              .build());
    }

    LlmResponse.Builder responseBuilder = LlmResponse.builder();
    Part part = nvidiaContentBlockToPart(choice0);

    String finishReason = choice0.optString("finish_reason", "");
    if ("tool_calls".equals(finishReason) && part.functionCall().isPresent()) {
      responseBuilder.content(
          Content.builder()
              .role("model")
              .parts(
                  ImmutableList.of(Part.builder().functionCall(part.functionCall().get()).build()))
              .build());
    } else {
      responseBuilder.content(
          Content.builder().role("model").parts(ImmutableList.of(part)).build());
    }

    if (usageMetadata != null) {
      responseBuilder.usageMetadata(usageMetadata);
    }

    return Flowable.just(responseBuilder.build());
  }

  // =========================================================================
  // STREAMING (SSE)
  // =========================================================================

  private Flowable<LlmResponse> generateContentStream(LlmRequest llmRequest) {
    List<Content> contents = ensureLastMessageIsUser(llmRequest.contents());
    String systemText = extractSystemText(llmRequest);
    JSONArray messages = buildMessages(systemText, contents);
    JSONArray tools = buildTools(llmRequest, contents);

    float temp =
        llmRequest.config().flatMap(GenerateContentConfig::temperature).orElse(this.temperature);

    JSONObject payload = new JSONObject();
    payload.put("model", this.model());
    payload.put("messages", messages);
    payload.put("max_tokens", this.maxTokens);
    payload.put("temperature", temp);
    payload.put("top_p", this.topP);
    payload.put("stream", true);
    if (tools != null) {
      payload.put("tools", tools);
    }

    logger.debug("NVIDIA stream payload: {}", payload.toString(2));

    BufferedReader reader = callNvidiaStream(payload);

    return Flowable.create(
        emitter -> {
          final StringBuilder accumulatedText = new StringBuilder();
          final Map<Integer, String> functionCallNameBuffer = new HashMap<>();
          final Map<Integer, StringBuilder> functionCallArgsBuffer = new HashMap<>();
          final AtomicBoolean functionCallDetected = new AtomicBoolean(false);
          int totalPromptTokens = 0;
          int totalCompletionTokens = 0;
          int totalTokens = 0;

          try {
            if (reader == null) {
              emitter.onComplete();
              return;
            }
            String line;
            while ((line = reader.readLine()) != null) {
              line = line.trim();
              if (line.startsWith("data:")) {
                line = line.substring(5).trim();
              }
              if (line.equals("[DONE]")) {
                logger.debug("[DONE] marker received");
                if (accumulatedText.length() > 0 && !functionCallDetected.get()) {
                  GenerateContentResponseUsageMetadata usage =
                      buildUsageMetadata(totalPromptTokens, totalCompletionTokens, totalTokens);
                  LlmResponse.Builder finalBuilder =
                      LlmResponse.builder()
                          .content(
                              Content.builder()
                                  .role("model")
                                  .parts(Part.fromText(accumulatedText.toString()))
                                  .build())
                          .partial(false);
                  if (usage != null) {
                    finalBuilder.usageMetadata(usage);
                  }
                  emitter.onNext(finalBuilder.build());
                }
                break;
              }
              if (line.isEmpty()) {
                continue;
              }
              JSONObject chunk;
              try {
                chunk = new JSONObject(line);
              } catch (JSONException e) {
                logger.warn("Failed to parse SSE line: {}", line);
                continue;
              }

              // Parse usage
              if (chunk.has("usage")) {
                JSONObject usage = chunk.optJSONObject("usage");
                if (usage != null) {
                  totalPromptTokens = Math.max(totalPromptTokens, usage.optInt("prompt_tokens", 0));
                  totalCompletionTokens =
                      Math.max(totalCompletionTokens, usage.optInt("completion_tokens", 0));
                  totalTokens = Math.max(totalTokens, usage.optInt("total_tokens", 0));
                }
              }

              JSONArray choices = chunk.optJSONArray("choices");
              if (choices == null || choices.length() == 0) {
                continue;
              }

              for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.optJSONObject(i);
                if (choice == null) continue;

                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) continue;

                // Handle tool_calls in delta
                if (delta.has("tool_calls")) {
                  JSONArray toolCalls = delta.optJSONArray("tool_calls");
                  if (toolCalls != null && toolCalls.length() > 0) {
                    JSONObject tc = toolCalls.getJSONObject(0);
                    JSONObject function = tc.optJSONObject("function");
                    if (function != null) {
                      String name = function.optString("name", null);
                      String argsFrag = function.optString("arguments", null);
                      int idx = tc.optInt("index", 0);
                      if (name != null && !name.isEmpty()) {
                        functionCallNameBuffer.put(idx, name);
                      }
                      if (argsFrag != null) {
                        functionCallArgsBuffer
                            .computeIfAbsent(idx, k -> new StringBuilder())
                            .append(argsFrag);
                      }
                      functionCallDetected.set(true);
                    }
                  }
                }

                // Handle function_call in delta (legacy format)
                if (delta.has("function_call")) {
                  JSONObject fc = delta.getJSONObject("function_call");
                  String name = fc.optString("name", null);
                  String argsFrag = fc.optString("arguments", null);
                  if (name != null && !name.isEmpty()) {
                    functionCallNameBuffer.put(i, name);
                  }
                  if (argsFrag != null) {
                    functionCallArgsBuffer
                        .computeIfAbsent(i, k -> new StringBuilder())
                        .append(argsFrag);
                  }
                  functionCallDetected.set(true);
                }

                // Handle text content
                String text = delta.optString("content", "");
                if (text != null && !text.isEmpty()) {
                  accumulatedText.append(text);
                  emitter.onNext(
                      LlmResponse.builder()
                          .content(
                              Content.builder().role("model").parts(Part.fromText(text)).build())
                          .partial(true)
                          .build());
                }

                // Check finish_reason for tool_calls or stop
                String finishReason = choice.optString("finish_reason", "");
                if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) {
                  // Emit function call
                  for (Map.Entry<Integer, String> entry : functionCallNameBuffer.entrySet()) {
                    int idx = entry.getKey();
                    String fcName = entry.getValue();
                    Map<String, Object> args = new HashMap<>();
                    StringBuilder argsBuilder = functionCallArgsBuffer.get(idx);
                    if (argsBuilder != null) {
                      try {
                        JSONObject argsJson = new JSONObject(argsBuilder.toString());
                        args = argsJson.toMap();
                      } catch (JSONException e) {
                        logger.warn("Failed to parse function args: {}", argsBuilder, e);
                      }
                    }
                    FunctionCall functionCall =
                        FunctionCall.builder().name(fcName).args(args).build();

                    GenerateContentResponseUsageMetadata usage =
                        buildUsageMetadata(totalPromptTokens, totalCompletionTokens, totalTokens);
                    LlmResponse.Builder fcBuilder =
                        LlmResponse.builder()
                            .content(
                                Content.builder()
                                    .role("model")
                                    .parts(
                                        ImmutableList.of(
                                            Part.builder().functionCall(functionCall).build()))
                                    .build())
                            .partial(false);
                    if (usage != null) {
                      fcBuilder.usageMetadata(usage);
                    }
                    emitter.onNext(fcBuilder.build());
                  }
                  functionCallNameBuffer.clear();
                  functionCallArgsBuffer.clear();
                }
              }
            }
            emitter.onComplete();
          } catch (IOException e) {
            logger.error("Error reading NVIDIA stream", e);
            emitter.onError(e);
          } finally {
            try {
              if (reader != null) reader.close();
            } catch (IOException e) {
              logger.error("Error closing stream reader", e);
            }
          }
        },
        io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
  }

  // =========================================================================
  // HTTP HELPERS
  // =========================================================================

  private JSONObject callNvidia(JSONObject payload, boolean stream) {
    String url = resolveBaseUrl() + "/chat/completions";
    String apiKey = resolveApiKey();
    String accept = stream ? "text/event-stream" : "application/json";

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + apiKey)
              .header("Accept", accept)
              .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
              .build();

      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      int statusCode = response.statusCode();
      logger.debug("NVIDIA response status: {}", statusCode);

      if (statusCode >= 200 && statusCode < 300) {
        return new JSONObject(response.body());
      } else {
        logger.error("NVIDIA API error ({}): {}", statusCode, response.body());
        return new JSONObject();
      }
    } catch (IOException | InterruptedException e) {
      logger.error("NVIDIA HTTP request failed", e);
      return new JSONObject();
    }
  }

  private BufferedReader callNvidiaStream(JSONObject payload) {
    String url = resolveBaseUrl() + "/chat/completions";
    String apiKey = resolveApiKey();

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + apiKey)
              .header("Accept", "text/event-stream")
              .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
              .build();

      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

      int statusCode = response.statusCode();
      logger.debug("NVIDIA stream response status: {}", statusCode);

      if (statusCode >= 200 && statusCode < 300) {
        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
      } else {
        try (BufferedReader errorReader =
            new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
          String errorBody = errorReader.lines().collect(Collectors.joining("\n"));
          logger.error("NVIDIA stream error ({}): {}", statusCode, errorBody);
        }
        return null;
      }
    } catch (IOException | InterruptedException e) {
      logger.error("NVIDIA streaming HTTP request failed", e);
      return null;
    }
  }

  // =========================================================================
  // MESSAGE & TOOL BUILDING HELPERS
  // =========================================================================

  private List<Content> ensureLastMessageIsUser(List<Content> contents) {
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
        .map(
            si ->
                si.parts().orElse(ImmutableList.of()).stream()
                    .filter(p -> p.text().isPresent())
                    .map(p -> p.text().get())
                    .collect(Collectors.joining("\n")))
        .orElse("");
  }

  private JSONArray buildMessages(String systemText, List<Content> contents) {
    JSONArray messages = new JSONArray();
    if (!systemText.isEmpty()) {
      JSONObject sysMsg = new JSONObject();
      sysMsg.put("role", "system");
      sysMsg.put("content", systemText);
      messages.put(sysMsg);
    }
    for (Content item : contents) {
      JSONObject msg = new JSONObject();
      String role = item.role().orElse("user");
      msg.put("role", role.equals("model") || role.equals("assistant") ? "assistant" : "user");
      if (item.parts().isPresent()
          && !item.parts().get().isEmpty()
          && item.parts().get().get(0).functionResponse().isPresent()) {
        msg.put(
            "content",
            new JSONObject(item.parts().get().get(0).functionResponse().get().response().get())
                .toString());
      } else {
        msg.put("content", item.text());
      }
      messages.put(msg);
    }
    return messages;
  }

  private JSONArray buildTools(LlmRequest llmRequest, List<Content> contents) {
    boolean lastRespToolExecuted =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();
    if (lastRespToolExecuted) {
      return null;
    }

    JSONArray functions = new JSONArray();
    llmRequest
        .tools()
        .entrySet()
        .forEach(
            entry -> {
              BaseTool baseTool = entry.getValue();
              Optional<FunctionDeclaration> declOpt = baseTool.declaration();
              if (!declOpt.isPresent()) {
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
                  ObjectMapper mapper = new ObjectMapper();
                  mapper.registerModule(new Jdk8Module());
                  propsOpt
                      .get()
                      .forEach(
                          (key, schema) -> {
                            Map<String, Object> schemaMap =
                                mapper.convertValue(
                                    schema, new TypeReference<Map<String, Object>>() {});
                            updateTypeString(schemaMap);
                            propsMap.put(key, schemaMap);
                          });
                  paramsMap.put("properties", propsMap);
                }
                paramsSchema.required().ifPresent(req -> paramsMap.put("required", req));
                funcMap.put("parameters", paramsMap);
              }

              JSONObject toolWrapper = new JSONObject();
              toolWrapper.put("type", "function");
              toolWrapper.put("function", new JSONObject(funcMap));
              functions.put(toolWrapper);
            });

    return functions.length() > 0 ? functions : null;
  }

  // =========================================================================
  // RESPONSE PARSING
  // =========================================================================

  /**
   * Parses an NVIDIA/OpenAI-compatible choice block into an ADK Part. Handles both tool_calls (new
   * format) and function_call (legacy).
   */
  private static Part nvidiaContentBlockToPart(JSONObject choice) {
    JSONObject message = choice.optJSONObject("message");
    if (message == null) {
      return Part.builder().text("").build();
    }

    // New format: tool_calls array
    if (message.has("tool_calls") && !message.isNull("tool_calls")) {
      JSONArray toolCalls = message.optJSONArray("tool_calls");
      if (toolCalls != null && toolCalls.length() > 0) {
        JSONObject tc = toolCalls.getJSONObject(0);
        JSONObject function = tc.optJSONObject("function");
        if (function != null) {
          String name = function.optString("name", null);
          Map<String, Object> args = new HashMap<>();
          Object argsRaw = function.opt("arguments");
          if (argsRaw instanceof JSONObject) {
            args = ((JSONObject) argsRaw).toMap();
          } else if (argsRaw instanceof String && !((String) argsRaw).isEmpty()) {
            try {
              args = new JSONObject((String) argsRaw).toMap();
            } catch (JSONException e) {
              logger.warn("Failed to parse tool_calls arguments: {}", argsRaw, e);
            }
          }
          if (name != null) {
            return Part.builder()
                .functionCall(FunctionCall.builder().name(name).args(args).build())
                .build();
          }
        }
      }
    }

    // Legacy format: function_call
    if (message.has("function_call") && !message.isNull("function_call")) {
      JSONObject fc = message.optJSONObject("function_call");
      if (fc == null) {
        // function_call exists but is not a valid object — skip
        return Part.builder().text(message.optString("content", "")).build();
      }
      String name = fc.optString("name", null);
      Map<String, Object> args = new HashMap<>();
      Object argsRaw = fc.opt("arguments");
      if (argsRaw instanceof JSONObject) {
        args = ((JSONObject) argsRaw).toMap();
      } else if (argsRaw instanceof String && !((String) argsRaw).isEmpty()) {
        try {
          args = new JSONObject((String) argsRaw).toMap();
        } catch (JSONException e) {
          logger.warn("Failed to parse function_call arguments: {}", argsRaw, e);
        }
      }
      if (name != null) {
        return Part.builder()
            .functionCall(FunctionCall.builder().name(name).args(args).build())
            .build();
      }
    }

    // Text content
    String content = message.optString("content", "");
    return Part.builder().text(content).build();
  }

  // =========================================================================
  // USAGE METADATA
  // =========================================================================

  private GenerateContentResponseUsageMetadata parseUsageMetadata(JSONObject responseJson) {
    JSONObject usage = responseJson.optJSONObject("usage");
    if (usage == null) {
      return null;
    }
    int prompt = usage.optInt("prompt_tokens", 0);
    int completion = usage.optInt("completion_tokens", 0);
    int total = usage.optInt("total_tokens", 0);
    return buildUsageMetadata(prompt, completion, total);
  }

  private GenerateContentResponseUsageMetadata buildUsageMetadata(
      int promptTokens, int completionTokens, int totalTokens) {
    if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
      return GenerateContentResponseUsageMetadata.builder()
          .promptTokenCount(promptTokens)
          .candidatesTokenCount(completionTokens)
          .totalTokenCount(totalTokens > 0 ? totalTokens : promptTokens + completionTokens)
          .build();
    }
    return null;
  }

  // =========================================================================
  // SCHEMA UTILITY
  // =========================================================================

  @SuppressWarnings("unchecked")
  private void updateTypeString(Map<String, Object> valueDict) {
    if (valueDict == null) return;
    if (valueDict.containsKey("type")) {
      valueDict.put("type", ((String) valueDict.get("type")).toLowerCase());
    }
    if (valueDict.containsKey("items")) {
      updateTypeString((Map<String, Object>) valueDict.get("items"));
      if (valueDict.get("items") instanceof Map
          && ((Map<String, Object>) valueDict.get("items")).containsKey("properties")) {
        Map<String, Object> properties =
            (Map<String, Object>) ((Map<String, Object>) valueDict.get("items")).get("properties");
        if (properties != null) {
          for (Object value : properties.values()) {
            if (value instanceof Map) {
              updateTypeString((Map<String, Object>) value);
            }
          }
        }
      }
    }
  }

  // =========================================================================
  // MAIN - standalone testing
  // =========================================================================

  public static void main(String[] args) {
    String modelId = args.length > 0 ? args[0] : "poolside/laguna-xs-2.1";
    String userPrompt = args.length > 1 ? args[1] : "Why is the sky blue?";

    System.out.println("=== NvidiaBaseLM Standalone Test ===");
    System.out.println("Model: " + modelId);
    System.out.println("Prompt: " + userPrompt);
    System.out.println("API Key env: " + API_KEY_ENV);
    System.out.println();

    NvidiaBaseLM llm = new NvidiaBaseLM(modelId);
    llm.setMaxTokens(2048);
    llm.setTemperature(0.15f);
    llm.setTopP(1.0f);

    LlmRequest request =
        LlmRequest.builder()
            .contents(ImmutableList.of(Content.fromParts(Part.fromText(userPrompt))))
            .build();

    // --- Non-streaming test ---
    System.out.println("--- Non-Streaming Test ---");
    try {
      llm.generateContent(request, false)
          .blockingSubscribe(
              response ->
                  response
                      .content()
                      .ifPresent(
                          content ->
                              content
                                  .parts()
                                  .ifPresent(
                                      parts ->
                                          parts.forEach(
                                              part -> {
                                                part.text()
                                                    .ifPresent(
                                                        text ->
                                                            System.out.println(
                                                                "Response: " + text));
                                                part.functionCall()
                                                    .ifPresent(
                                                        fc ->
                                                            System.out.println(
                                                                "Function Call: " + fc));
                                              }))),
              error -> {
                System.err.println("Error: " + error.getMessage());
                error.printStackTrace();
              },
              () -> System.out.println("Non-streaming complete."));
    } catch (RuntimeException e) {
      System.err.println("Non-streaming failed: " + e.getMessage());
    }

    System.out.println();

    // --- Streaming test ---
    System.out.println("--- Streaming Test ---");
    try {
      System.out.print("Response: ");
      llm.generateContent(request, true)
          .blockingSubscribe(
              response ->
                  response
                      .content()
                      .ifPresent(
                          content ->
                              content
                                  .parts()
                                  .ifPresent(
                                      parts ->
                                          parts.forEach(
                                              part -> {
                                                part.text()
                                                    .ifPresent(text -> System.out.print(text));
                                                part.functionCall()
                                                    .ifPresent(
                                                        fc ->
                                                            System.out.println(
                                                                "\nFunction Call: " + fc));
                                              }))),
              error -> {
                System.err.println("\nError: " + error.getMessage());
                error.printStackTrace();
              },
              () -> System.out.println("\nStreaming complete."));
    } catch (RuntimeException e) {
      System.err.println("Streaming failed: " + e.getMessage());
    }
  }
}
