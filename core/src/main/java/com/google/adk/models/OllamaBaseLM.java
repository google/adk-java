/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.models;

import static com.google.adk.models.RedbusADG.callLLMChat;
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
import com.google.genai.types.MediaModality;
import com.google.genai.types.ModalityTokenCount;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author ryzen
 * @author Manoj Kumar, Sandeep Belgavi
 * @date 2025-06-27
 */
public class OllamaBaseLM extends BaseLlm {

  // The Ollama endpoint is already correctly set as requested.
  public static String OLLAMA_EP = "OLLAMA_API_BASE";
  public String D_URL = null;

  private int numCtx = 32768;
  private boolean think = false;

  // Corrected the logger name to use OllamaBaseLM.class
  private static final Logger logger = LoggerFactory.getLogger(OllamaBaseLM.class);

  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  public OllamaBaseLM(String model) {

    super(model);
  }

  public OllamaBaseLM(String model, String OLLAMA_EP) {

    super(model);
    this.D_URL = OLLAMA_EP;
  }

  public int getNumCtx() {
    return numCtx;
  }

  public void setNumCtx(int numCtx) {
    this.numCtx = numCtx;
  }

  public boolean isThink() {
    return think;
  }

  public void setThink(boolean think) {
    this.think = think;
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      return generateContentStream(llmRequest);
    }

    List<Content> contents = llmRequest.contents();
    // Last content must be from the user, otherwise the model won't respond.
    if (contents.isEmpty() || !Iterables.getLast(contents).role().orElse("").equals("user")) {
      Content userContent = Content.fromParts(Part.fromText(CONTINUE_OUTPUT_MESSAGE));
      contents =
          Stream.concat(contents.stream(), Stream.of(userContent)).collect(toImmutableList());
    }

    String systemText = "";
    Optional<GenerateContentConfig> configOpt = llmRequest.config();
    if (configOpt.isPresent()) {
      Optional<Content> systemInstructionOpt = configOpt.get().systemInstruction();
      if (systemInstructionOpt.isPresent()) {
        String extractedSystemText =
            systemInstructionOpt.get().parts().orElse(ImmutableList.of()).stream()
                .filter(p -> p.text().isPresent())
                .map(p -> p.text().get())
                .collect(Collectors.joining("\n"));
        if (!extractedSystemText.isEmpty()) {
          systemText = extractedSystemText;
        }
      }
    }

    // Messages
    JSONArray messages = new JSONArray();

    JSONObject llmMessageJson1 = new JSONObject();
    llmMessageJson1.put("role", "system");
    llmMessageJson1.put("content", systemText);
    messages.put(llmMessageJson1); // Agent system prompt is always added

    llmRequest.contents().stream()
        .forEach(
            item -> {
              //   return new MessageParam(content.role().get().equals("model") ||
              // content.role().get().equals("assistant") ? "" : "",content.text());
              JSONObject messageQuantum = new JSONObject();
              messageQuantum.put(
                  "role",
                  item.role().get().equals("model") || item.role().get().equals("assistant")
                      ? "assistant"
                      : "user");

              // Additional override work to add function response
              if (item.parts().isPresent()) {
                var parts = item.parts().get();

                // Ensure index 1 exists
                if (parts.size() > 1) {
                  var part = parts.get(1);

                  if (part.inlineData().isPresent() && part.inlineData().get().data().isPresent()) {

                    byte[] inlineImageData = part.inlineData().get().data().get();

                    String imgBase64 = toBase64String(inlineImageData);

                    JSONArray images = new JSONArray();
                    images.put(imgBase64);
                    messageQuantum.put("images", images);
                  }
                }
              }

              // Additinal override work to add function response
              if (item.parts().get().get(0).functionResponse().isPresent()) {
                messageQuantum.put(
                    "content",
                    new JSONObject(
                            item.parts().get().get(0).functionResponse().get().response().get())
                        .toString(1));
              } else {
                messageQuantum.put("content", item.text());
              }
              messages.put(messageQuantum);
            });

    // Tools
    // Define the required pattern for the name
    JSONArray functions = new JSONArray();
    llmRequest
        .tools()
        .entrySet()
        .forEach(
            tooldetail -> {
              BaseTool baseTool = tooldetail.getValue();

              // Get the function declaration from the base tool
              Optional<FunctionDeclaration> declarationOptional = baseTool.declaration();

              // Skip this tool if there is no function declaration
              if (!declarationOptional.isPresent()) {
                logger.warn("Skipping tool '{}' with missing declaration.", baseTool.name());
                return; // If processing a single tool outside a loop
              }

              FunctionDeclaration functionDeclaration = declarationOptional.get();

              // Build the top-level map representing the tool JSON structure
              Map<String, Object> toolMap = new HashMap<>();

              // Add the tool's name and description from the function declaration
              toolMap.put("name", cleanForIdentifierPattern(functionDeclaration.name().get()));
              toolMap.put(
                  "description",
                  cleanForIdentifierPattern(
                      functionDeclaration
                          .description()
                          .orElse(""))); // Use description from declaration, handle Optional

              // Build the 'parameters' object if parameters are defined
              Optional<Schema> parametersOptional = functionDeclaration.parameters();
              if (parametersOptional.isPresent()) {
                Schema parametersSchema = parametersOptional.get();

                Map<String, Object> parametersMap = new HashMap<>();
                parametersMap.put(
                    "type", "object"); // Function parameters schema type is typically "object"

                // Build the 'properties' map within 'parameters'
                Optional<Map<String, Schema>> propertiesOptional = parametersSchema.properties();
                if (propertiesOptional.isPresent()) {
                  Map<String, Object> propertiesMap = new HashMap<>();
                  // Create ObjectMapper instance once for the loop
                  ObjectMapper objectMapper = new ObjectMapper();
                  objectMapper.registerModule(
                      new Jdk8Module()); // Register module for Java 8 Optionals, etc.

                  propertiesOptional
                      .get()
                      .forEach(
                          (key, schema) -> {
                            // Convert the library's Schema object for a parameter to a generic Map
                            Map<String, Object> schemaMap =
                                objectMapper.convertValue(
                                    schema, new TypeReference<Map<String, Object>>() {});

                            // Apply your custom logic to update the type string
                            // !!! This function updateTypeString(schemaMap) is required and not
                            // provided !!!
                            updateTypeString(
                                schemaMap); // Ensure this modifies schemaMap in place or returns
                            // the modified map

                            propertiesMap.put(key, schemaMap);
                          });
                  parametersMap.put("properties", propertiesMap);
                }

                // Add the 'required' list within 'parameters' if present
                parametersSchema
                    .required()
                    .ifPresent(
                        requiredList ->
                            parametersMap.put(
                                "required", requiredList)); // Assuming required() returns
                // Optional<List<String>>

                // Add the completed 'parameters' map to the main tool map
                toolMap.put("parameters", parametersMap);
              }

              // Convert the complete tool map into an org.json.JSONObject
              JSONObject jsonToolW = new JSONObject();
              jsonToolW.put("type", "function");

              JSONObject jsonTool = new JSONObject(toolMap);
              jsonToolW.put("function", jsonTool);

              // Add the generated tool JSON object to your functions list/array
              functions.put(jsonToolW);
            });

    // Check if the tool is executed, then parse and response.
    logger.debug("functions: {}", functions.toString(1));

    String modelId =
        this.model(); // "devstral";//"llama3.2:3b-instruct-q2_K";//"llama3.2"; // The 1b doesn't
    // support tool

    // If last user response has the function reponse, then function calla is not needed.
    boolean LAST_RESP_TOOl_EXECUTED =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();

    GenerateContentConfig config = llmRequest.config().get();
    JSONObject agentresponse =
        callLLMChat(
            config,
            modelId,
            messages,
            LAST_RESP_TOOl_EXECUTED
                ? null
                : (functions.length() > 0
                    ? functions
                    : null)); // Tools/functions can not be of 0 length

    GenerateContentResponseUsageMetadata usageMetadata = getUsageMetadata(agentresponse);

    JSONObject responseQuantum = agentresponse.getJSONObject("message");

    // Check if tool call is required
    // Tools call
    LlmResponse.Builder responseBuilder = LlmResponse.builder();
    List<Part> parts = new ArrayList<>();
    Part part = ollamaContentBlockToPart(responseQuantum);
    parts.add(part);

    // Call tool
    if (responseQuantum.has("tool_calls")
        && "stop".contentEquals(agentresponse.getString("done_reason"))) {

      responseBuilder.content(
          Content.builder()
              .role("model")
              .parts(
                  ImmutableList.of(Part.builder().functionCall(part.functionCall().get()).build()))
              .build());

      //  responseBuilder.partial(false).turnComplete(false);
    } else {
      responseBuilder.content(
          Content.builder().role("model").parts(ImmutableList.copyOf(parts)).build());
    }

    if (usageMetadata != null) {
      responseBuilder.usageMetadata(usageMetadata);
    }

    return Flowable.just(responseBuilder.build());
  }

  public Flowable<LlmResponse> generateContentStream(LlmRequest llmRequest) {
    List<Content> contents = llmRequest.contents();
    // Last content must be from the user, otherwise the model won't respond.
    if (contents.isEmpty() || !Iterables.getLast(contents).role().orElse("").equals("user")) {
      Content userContent = Content.fromParts(Part.fromText(CONTINUE_OUTPUT_MESSAGE));
      contents =
          Stream.concat(contents.stream(), Stream.of(userContent)).collect(toImmutableList());
    }

    String systemText = "";
    Optional<GenerateContentConfig> configOpt = llmRequest.config();
    if (configOpt.isPresent()) {
      Optional<Content> systemInstructionOpt = configOpt.get().systemInstruction();
      if (systemInstructionOpt.isPresent()) {
        String extractedSystemText =
            systemInstructionOpt.get().parts().orElse(ImmutableList.of()).stream()
                .filter(p -> p.text().isPresent())
                .map(p -> p.text().get())
                .collect(Collectors.joining("\n"));
        if (!extractedSystemText.isEmpty()) {
          systemText = extractedSystemText;
        }
      }
    }

    // Messages
    JSONArray messages = new JSONArray();

    JSONObject llmMessageJson1 = new JSONObject();
    llmMessageJson1.put("role", "system");
    llmMessageJson1.put("content", systemText);
    messages.put(llmMessageJson1); // Agent system prompt is always added

    final List<Content> finalContents = contents;
    finalContents.stream()
        .forEach(
            item -> {
              JSONObject messageQuantum = new JSONObject();
              messageQuantum.put(
                  "role",
                  item.role().get().equals("model") || item.role().get().equals("assistant")
                      ? "assistant"
                      : "user");

              if (item.parts().get().get(0).functionResponse().isPresent()) {
                messageQuantum.put(
                    "content",
                    new JSONObject(
                            item.parts().get().get(0).functionResponse().get().response().get())
                        .toString(1));
              } else {
                messageQuantum.put("content", item.text());
              }
              messages.put(messageQuantum);
            });

    // Tools
    JSONArray functions = new JSONArray();
    llmRequest
        .tools()
        .entrySet()
        .forEach(
            tooldetail -> {
              BaseTool baseTool = tooldetail.getValue();
              Optional<FunctionDeclaration> declarationOptional = baseTool.declaration();
              if (!declarationOptional.isPresent()) {
                logger.warn("Skipping tool '{}' with missing declaration.", baseTool.name());
                return;
              }
              FunctionDeclaration functionDeclaration = declarationOptional.get();
              Map<String, Object> toolMap = new HashMap<>();
              toolMap.put("name", cleanForIdentifierPattern(functionDeclaration.name().get()));
              toolMap.put(
                  "description",
                  cleanForIdentifierPattern(functionDeclaration.description().orElse("")));
              Optional<Schema> parametersOptional = functionDeclaration.parameters();
              if (parametersOptional.isPresent()) {
                Schema parametersSchema = parametersOptional.get();
                Map<String, Object> parametersMap = new HashMap<>();
                parametersMap.put("type", "object");
                Optional<Map<String, Schema>> propertiesOptional = parametersSchema.properties();
                if (propertiesOptional.isPresent()) {
                  Map<String, Object> propertiesMap = new HashMap<>();
                  ObjectMapper objectMapper = new ObjectMapper();
                  objectMapper.registerModule(new Jdk8Module());
                  propertiesOptional
                      .get()
                      .forEach(
                          (key, schema) -> {
                            Map<String, Object> schemaMap =
                                objectMapper.convertValue(
                                    schema, new TypeReference<Map<String, Object>>() {});
                            updateTypeString(schemaMap);
                            propertiesMap.put(key, schemaMap);
                          });
                  parametersMap.put("properties", propertiesMap);
                }
                parametersSchema
                    .required()
                    .ifPresent(requiredList -> parametersMap.put("required", requiredList));
                toolMap.put("parameters", parametersMap);
              }
              // Convert the complete tool map into an org.json.JSONObject
              JSONObject jsonToolW = new JSONObject();
              jsonToolW.put("type", "function");

              JSONObject jsonTool = new JSONObject(toolMap);
              jsonToolW.put("function", jsonTool);

              // Add the generated tool JSON object to your functions list/array
              functions.put(jsonToolW);
            });

    String modelId = this.model();

    boolean LAST_RESP_TOOl_EXECUTED =
        Iterables.getLast(Iterables.getLast(finalContents).parts().get())
            .functionResponse()
            .isPresent();

    return createRobustStreamingResponse(
        modelId,
        messages,
        LAST_RESP_TOOl_EXECUTED ? null : (functions.length() > 0 ? functions : null));
  }

  private Flowable<LlmResponse> createRobustStreamingResponse(
      String modelId, JSONArray messages, JSONArray functions) {
    final StringBuilder accumulatedText = new StringBuilder();
    final StringBuilder functionCallName = new StringBuilder();
    final StringBuilder functionCallArgs = new StringBuilder();
    final AtomicBoolean inFunctionCall = new AtomicBoolean(false);
    final AtomicBoolean streamCompleted = new AtomicBoolean(false);

    final AtomicInteger inputTokens = new AtomicInteger(0);
    final AtomicInteger outputTokens = new AtomicInteger(0);
    final AtomicLong promptEvalDuration = new AtomicLong(0);
    final AtomicLong evalDuration = new AtomicLong(0);
    final AtomicInteger promptAudioTokens = new AtomicInteger(0);
    final AtomicInteger completionAudioTokens = new AtomicInteger(0);

    return Flowable.generate(
        () -> callLLMChatStream(modelId, messages, functions),
        (reader, emitter) -> {
          try {
            if (reader == null) {
              emitter.onComplete();
              return;
            }

            String line = reader.readLine();
            if (line == null) {
              if (accumulatedText.length() > 0) {
                LlmResponse finalResponse = createTextResponse(accumulatedText.toString(), false);
                emitter.onNext(finalResponse);
              }
              emitter.onComplete();
              return;
            }

            if (line.isEmpty()) {
              return;
            }

            JSONObject responseJson;
            try {
              responseJson = new JSONObject(line);
            } catch (Exception parseEx) {
              logger.warn("Failed to parse Ollama response line: {}", line, parseEx);
              return;
            }

            JSONObject message = responseJson.optJSONObject("message");
            List<LlmResponse> responsesToEmit = new ArrayList<>();

            if (message != null) {
              if (message.has("content") && message.get("content") instanceof String) {
                String text = message.getString("content");
                if (!text.isEmpty()) {
                  accumulatedText.append(text);

                  LlmResponse partialResponse = createTextResponse(text, true);
                  responsesToEmit.add(partialResponse);
                }
              }

              if (message.has("tool_calls")) {
                inFunctionCall.set(true);
                JSONArray toolCalls = message.getJSONArray("tool_calls");
                if (toolCalls.length() > 0) {
                  JSONObject toolCall = toolCalls.getJSONObject(0);
                  JSONObject function = toolCall.getJSONObject("function");
                  if (function.has("name")) {
                    functionCallName.append(function.getString("name"));
                  }
                  if (function.has("arguments")) {
                    JSONObject argsJson = function.optJSONObject("arguments");
                    if (argsJson != null) {
                      String args = argsJson.toString();
                      functionCallArgs.append(args);
                    }
                  }
                }
              }
            }

            if (responseJson.optBoolean("done", false)) {
              streamCompleted.set(true);

              if (responseJson.has("prompt_eval_count")) {
                inputTokens.set(responseJson.getInt("prompt_eval_count"));
              }
              if (responseJson.has("eval_count")) {
                outputTokens.set(responseJson.getInt("eval_count"));
              }
              // Check for audio tokens if Ollama adds them in the future
              if (responseJson.has("prompt_tokens_details")) {
                JSONObject pDetails = responseJson.optJSONObject("prompt_tokens_details");
                if (pDetails != null && pDetails.has("audio_tokens")) {
                  promptAudioTokens.set(pDetails.getInt("audio_tokens"));
                }
              }
              if (responseJson.has("completion_tokens_details")) {
                JSONObject cDetails = responseJson.optJSONObject("completion_tokens_details");
                if (cDetails != null && cDetails.has("audio_tokens")) {
                  completionAudioTokens.set(cDetails.getInt("audio_tokens"));
                }
              }

              GenerateContentResponseUsageMetadata usageMetadata =
                  getUsageMetadata(
                      inputTokens.get(),
                      outputTokens.get(),
                      inputTokens.get() + outputTokens.get(),
                      promptAudioTokens.get(),
                      completionAudioTokens.get());

              if (accumulatedText.length() > 0 && !inFunctionCall.get()) {
                LlmResponse.Builder aggregatedResponseBuilder =
                    LlmResponse.builder()
                        .content(
                            Content.builder()
                                .role("model")
                                .parts(Part.fromText(accumulatedText.toString()))
                                .build())
                        .partial(false);

                if (usageMetadata != null) {
                  aggregatedResponseBuilder.usageMetadata(usageMetadata);
                }

                responsesToEmit.add(aggregatedResponseBuilder.build());
              }

              if (inFunctionCall.get() && functionCallName.length() > 0) {
                try {
                  Map<String, Object> args = new JSONObject(functionCallArgs.toString()).toMap();
                  FunctionCall fc =
                      FunctionCall.builder().name(functionCallName.toString()).args(args).build();
                  Part part = Part.builder().functionCall(fc).build();

                  LlmResponse.Builder functionResponseBuilder =
                      LlmResponse.builder()
                          .content(
                              Content.builder()
                                  .role("model")
                                  .parts(ImmutableList.of(part))
                                  .build());

                  if (usageMetadata != null) {
                    functionResponseBuilder.usageMetadata(usageMetadata);
                  }

                  responsesToEmit.add(functionResponseBuilder.build());
                } catch (Exception funcEx) {
                  logger.error("Error creating function call response", funcEx);
                }
              }

              for (LlmResponse response : responsesToEmit) {
                emitter.onNext(response);
              }
              emitter.onComplete();
              return;
            }

            if (!responsesToEmit.isEmpty()) {
              for (LlmResponse response : responsesToEmit) {
                emitter.onNext(response);
              }
            }

          } catch (Exception e) {
            logger.error("Error in Ollama streaming", e);
            e.printStackTrace();
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

  private LlmResponse createTextResponse(String text, boolean partial) {
    return LlmResponse.builder()
        .content(Content.builder().role("model").parts(Part.fromText(text)).build())
        .partial(partial)
        .build();
  }

  private GenerateContentResponseUsageMetadata getUsageMetadata(
      int promptTokens,
      int completionTokens,
      int totalTokens,
      int promptAudioTokens,
      int completionAudioTokens) {
    if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
      GenerateContentResponseUsageMetadata.Builder builder =
          GenerateContentResponseUsageMetadata.builder()
              .promptTokenCount(promptTokens)
              .candidatesTokenCount(completionTokens)
              .totalTokenCount(totalTokens > 0 ? totalTokens : promptTokens + completionTokens);

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

  private GenerateContentResponseUsageMetadata getUsageMetadata(JSONObject agentResponse) {
    if (agentResponse == null) {
      return null;
    }

    try {
      int promptTokens = 0;
      int completionTokens = 0;
      int totalTokens = 0;
      int promptAudioTokens = 0;
      int completionAudioTokens = 0;

      if (agentResponse.has("prompt_eval_count")) {
        promptTokens = agentResponse.getInt("prompt_eval_count");
      }

      if (agentResponse.has("eval_count")) {
        completionTokens = agentResponse.getInt("eval_count");
      }
      totalTokens = promptTokens + completionTokens;

      if (agentResponse.has("prompt_tokens_details")) {
        JSONObject pDetails = agentResponse.optJSONObject("prompt_tokens_details");
        if (pDetails != null && pDetails.has("audio_tokens")) {
          promptAudioTokens = pDetails.getInt("audio_tokens");
        }
      }
      if (agentResponse.has("completion_tokens_details")) {
        JSONObject cDetails = agentResponse.optJSONObject("completion_tokens_details");
        if (cDetails != null && cDetails.has("audio_tokens")) {
          completionAudioTokens = cDetails.getInt("audio_tokens");
        }
      }

      if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
        logger.info(
            "Ollama token counts: prompt={}, completion={}, total={}",
            promptTokens,
            completionTokens,
            totalTokens);
        GenerateContentResponseUsageMetadata.Builder builder =
            GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(promptTokens)
                .candidatesTokenCount(completionTokens)
                .totalTokenCount(totalTokens > 0 ? totalTokens : promptTokens + completionTokens);

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
    } catch (Exception e) {
      logger.warn("Failed to parse token usage from Ollama response", e);
    }

    return null;
  }

  public BufferedReader callLLMChatStream(String model, JSONArray messages, JSONArray tools) {
    try {
      String apiUrl = D_URL != null ? D_URL : System.getenv(OLLAMA_EP);
      apiUrl = apiUrl + "/api/chat";

      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put("stream", true);
      payload.put("think", this.think);

      JSONObject options = new JSONObject();
      options.put("num_ctx", this.numCtx);
      payload.put("options", options);

      payload.put("messages", messages);

      if (tools != null) {
        payload.put("tools", tools);
      }

      String jsonString = payload.toString();

      URL url = new URL(apiUrl);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("POST");
      connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);

      try (OutputStream outputStream = connection.getOutputStream();
          OutputStreamWriter writer = new OutputStreamWriter(outputStream, "UTF-8")) {
        writer.write(jsonString);
        writer.flush();
      }

      int responseCode = connection.getResponseCode();
      logger.debug("Response Code from Ollama for model {}: {}", model, responseCode);

      if (responseCode >= 200 && responseCode < 300) {
        return new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
      } else {
        try (InputStream errorStream = connection.getErrorStream();
            BufferedReader errorReader =
                new BufferedReader(new InputStreamReader(errorStream, "UTF-8"))) {
          StringBuilder errorResponse = new StringBuilder();
          String errorLine;
          while ((errorLine = errorReader.readLine()) != null) {
            errorResponse.append(errorLine);
          }
          logger.error("Error Response Body: {}", errorResponse.toString());
        } catch (IOException errorEx) {
          logger.error("Error reading error stream", errorEx);
        }
        connection.disconnect();
        return null;
      }
    } catch (IOException ex) {
      logger.error("Error in callLLMChatStream", ex);
      return null;
    }
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    return new GenericLlmConnection(this, llmRequest);
  }

  /**
   * This method appears to be unused in the current context. It's typically used for modifying JSON
   * schemas, which is not directly related to sending chat messages to Ollama. You might consider
   * removing it if it's no longer needed.
   */
  private void updateTypeString(Map<String, Object> valueDict) {
    if (valueDict == null) {
      return;
    }
    if (valueDict.containsKey("type")) {
      valueDict.put("type", ((String) valueDict.get("type")).toLowerCase());
    }

    if (valueDict.containsKey("items")) {
      updateTypeString((Map<String, Object>) valueDict.get("items"));

      if (valueDict.get("items") instanceof Map
          && ((Map) valueDict.get("items")).containsKey("properties")) {
        Map<String, Object> properties =
            (Map<String, Object>) ((Map) valueDict.get("items")).get("properties");
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

  public static Part ollamaContentBlockToPart(JSONObject blockJson) {
    // Check for tool_calls first, as the example with tool_calls had empty content
    if (blockJson.has("tool_calls")) {
      JSONArray toolCalls =
          blockJson.optJSONArray("tool_calls"); // Use optJSONArray for null safety
      if (toolCalls != null && toolCalls.length() > 0) {
        // Based on the provided structure and LangChain4j Part,
        // we typically handle one function call per Part.
        // We will process the first tool call in the array.
        JSONObject toolCall = toolCalls.optJSONObject(0); // Use optJSONObject for null safety

        if (toolCall != null && toolCall.has("function")) {
          JSONObject function =
              toolCall.optJSONObject("function"); // Use optJSONObject for null safety

          if (function != null && function.has("name") && function.has("arguments")) {
            String name = function.optString("name", null); // Use optString for null safety
            JSONObject argsJson =
                function.optJSONObject("arguments"); // Use optJSONObject for null safety

            if (name != null && argsJson != null) {
              // Convert JSONObject arguments to Map<String, Object>
              // Assuming org.json.JSONObject.toMap() is available
              Map<String, Object> args = argsJson.toMap();

              // Build the FunctionCall Part
              // The provided JSON does not include an 'id' for the tool call, so omitting it.
              FunctionCall functionCall = FunctionCall.builder().name(name).args(args).build();

              return Part.builder().functionCall(functionCall).build();
            }
          }
        }
      }
    }

    // If no valid tool_calls were processed, check for text content
    if (blockJson.has("content")) {
      Object content = blockJson.opt("content"); // Use opt for null safety
      if (content instanceof String) {
        String text = (String) content;
        // Return a text Part, even if the string is empty (matches empty content example)
        return Part.builder().text(text).build();
      }
      // If 'content' key exists but value is not a String, might be unsupported.
    }

    // If neither usable tool_calls nor String content was found
    // This covers cases like malformed JSON matching the structure,
    // or structures not covered (e.g., image parts, other types).
    throw new UnsupportedOperationException(
        "Unsupported content block format or missing required fields: " + blockJson.toString());
  }

  /**
   * Makes a POST request to a specified URL with a dynamic JSON body. Fetches username and password
   * from environment variables.
   *
   * @param model
   * @param messages The list of messages for the "request.messages" field.
   * @param tools
   * @return The response body as a String.
   * @throws RuntimeException If environment variables are not set or JSON creation fails.
   */
  public JSONObject callLLMChat(
      GenerateContentConfig config, String model, JSONArray messages, JSONArray tools) {
    try {

      float temperature = config.temperature().isPresent() ? config.temperature().get() : 0.7f;

      JSONObject responseJ = new JSONObject();
      // API endpoint URL //OLLAMA_API_BASE
      String apiUrl = D_URL != null ? D_URL : System.getenv(OLLAMA_EP);
      apiUrl = apiUrl + "/api/chat";

      // Constructing the JSON payload
      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put(
          "stream", false); // Assuming non-streaming as per current generateContent implementation
      payload.put("think", this.think);

      JSONObject options = new JSONObject();
      options.put("num_ctx", this.numCtx);
      payload.put("options", options);
      payload.put("temperature", temperature);

      // Add messages to the payload
      payload.put("messages", messages);

      // Add tools if provided
      if (tools != null) {
        payload.put("tools", tools);
      }

      // Convert payload to string
      String jsonString = payload.toString();

      // Create URL object
      URL url = new URL(apiUrl);

      // Open connection
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();

      // Set request method
      connection.setRequestMethod("POST");

      // Set headers
      connection.setRequestProperty(
          "Content-Type",
          "application/json; charset=UTF-8"); // <-- Also good practice to specify charset here
      // connection.setRequestProperty("charset", "UTF-8"); // This header is less standard than
      // adding to Content-Type

      // Enable output
      connection.setDoOutput(true);
      // Optional: Set content length based on UTF-8 bytes
      connection.setFixedLengthStreamingMode(jsonString.getBytes("UTF-8").length);

      // Write JSON data to output stream using UTF-8
      try (OutputStream outputStream = connection.getOutputStream();
          OutputStreamWriter writer =
              new OutputStreamWriter(outputStream, "UTF-8")) { // <-- MODIFIED
        writer.write(jsonString); // <-- MODIFIED
        writer.flush();
      } catch (IOException ex) {
        java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
      }

      // Read response
      int responseCode = connection.getResponseCode();
      logger.debug("Response Code: {}", responseCode);

      // Read response body using UTF-8
      try (InputStream inputStream = connection.getInputStream();
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) { // <-- MODIFIED
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
          response.append(line);
        }
        logger.debug("Response Body: {}", response.toString());

        responseJ = new JSONObject(response.toString());

      } catch (IOException ex) {
        java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
        // Handle error stream if responseCode is not 2xx
        if (responseCode >= 400) {
          try (InputStream errorStream = connection.getErrorStream();
              BufferedReader errorReader =
                  new BufferedReader(new InputStreamReader(errorStream, "UTF-8"))) {
            StringBuilder errorResponse = new StringBuilder();
            String errorLine;
            while ((errorLine = errorReader.readLine()) != null) {
              errorResponse.append(errorLine);
            }
            logger.error("Error Response Body: {}", errorResponse.toString());
            // You might want to parse the errorResponse as a JSON object too if the API returns
            // JSON errors
          } catch (IOException errorEx) {
            java.util.logging.Logger.getLogger(RedbusADG.class.getName())
                .log(Level.SEVERE, null, errorEx);
          }
        }
      }

      // Close connection
      connection.disconnect();

      return responseJ;

    } catch (MalformedURLException ex) {
      java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
    } catch (ProtocolException ex) {
      java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
    } catch (IOException ex) {
      java.util.logging.Logger.getLogger(RedbusADG.class.getName()).log(Level.SEVERE, null, ex);
    }
    return new JSONObject();
  }

  /**
   * Use prompt parameter to moderate the questions is prompt!=null, using the generate "options": {
   * "num_ctx": 4096 }
   *
   * @param prompt (Note: This 'prompt' is largely superseded by 'messages' for chat APIs, keep for
   *     compatibility if needed elsewhere)
   * @param model The Ollama model to use (e.g., "llama3")
   * @param messages The JSONArray of messages representing the chat history
   * @param tools Optional JSONArray of tool definitions
   * @return JSONObject representing the Ollama API response
   */
  public static JSONObject callLLMChat(
      boolean stream, String prompt, String model, JSONArray messages, JSONArray tools) {
    JSONObject responseJ = new JSONObject();
    try {
      // API endpoint URL //OLLAMA_API_BASE
      String apiUrl = System.getenv(OLLAMA_EP);
      apiUrl = apiUrl + "/api/chat";

      // Constructing the JSON payload
      JSONObject payload = new JSONObject();
      payload.put("model", model);
      payload.put(
          "stream", false); // Assuming non-streaming as per current generateContent implementation
      payload.put("think", false);

      JSONObject options = new JSONObject();
      options.put("num_ctx", 4096);
      payload.put("options", options);

      // Add messages to the payload
      payload.put("messages", messages);

      // Add tools if provided
      if (tools != null) {
        payload.put("tools", tools);
      }

      // Convert payload to string
      String jsonString = payload.toString();

      // Create URL object
      URL url = new URL(apiUrl);

      // Open connection
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      logger.debug("HTTP Connection to Ollama API: {}", apiUrl);
      // Set request method
      connection.setRequestMethod("POST");

      // Set headers
      connection.setRequestProperty("Content-Type", "application/json");

      // Enable output and set content length
      connection.setDoOutput(true);
      connection.setFixedLengthStreamingMode(jsonString.getBytes().length);

      // Write JSON data to output stream
      try (DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream())) {
        outputStream.writeBytes(jsonString);
        outputStream.flush();
      }

      // Read response
      int responseCode = connection.getResponseCode();
      logger.debug("Response Code: {}", responseCode);

      // Read response body
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
        StringBuilder response = new StringBuilder();
        String line;

        if (stream) {
          StringBuilder streamOutput = new StringBuilder();
          // Read each line (JSON object) from the stream
          while ((line = reader.readLine()) != null) {
            // Parse each line as a JSON object
            JSONObject jsonObject = new JSONObject(line);

            /**
             * { "model": "llama3.2", "created_at": "2023-08-04T08:52:19.385406455-07:00",
             * "message": { "role": "assistant", "content": "The", "images": null }, "done": false }
             */
            // Extract values from the JSON object
            String responseText = jsonObject.getJSONObject("message").getString("content");
            boolean done = jsonObject.getBoolean("done");
            streamOutput.append(responseText);

            // Display the parsed data
            logger.debug("Model: {}, Response Text: {}, Done: {}", model, responseText, done);

            // Break if response is marked as done
            if (done) {
              break;
            }
          }

          // reconstruct for further processing.
          responseJ = new JSONObject();
          // getJSONObject("message").getString("content");
          JSONObject message = new JSONObject();
          message.put("content", streamOutput.toString());
          responseJ.put("message", message);

        } else {

          while ((line = reader.readLine()) != null) {
            response.append(line);
          }
          String responseBody = response.toString();
          logger.debug("Response Body: {}", responseBody);

          responseJ = new JSONObject(responseBody);
        }
      }

      // Close connection
      connection.disconnect();

    } catch (MalformedURLException ex) {
      logger.error("Malformed URL for Ollama API.", ex);
      java.util.logging.Logger.getLogger(OllamaBaseLM.class.getName()).log(Level.SEVERE, null, ex);
    } catch (IOException ex) {
      logger.error("IO Exception when calling Ollama API.", ex);
      java.util.logging.Logger.getLogger(OllamaBaseLM.class.getName()).log(Level.SEVERE, null, ex);
    } catch (Exception ex) { // Catch any other unexpected exceptions
      logger.error("An unexpected error occurred when calling Ollama API.", ex);
      java.util.logging.Logger.getLogger(OllamaBaseLM.class.getName()).log(Level.SEVERE, null, ex);
    }
    return responseJ;
  }

  public static byte[] fileToBase64Bytes(File imageFile) throws IOException {

    // 1. Read file into raw bytes
    byte[] fileBytes = Files.readAllBytes(imageFile.toPath());

    // 2. Encode to Base64 (String or bytes)
    String base64String = Base64.getEncoder().encodeToString(fileBytes);

    // 3. Decode Base64 back to byte[]
    return Base64.getDecoder().decode(base64String);
  }

  public static String toBase64String(byte[] data) {
    return Base64.getEncoder().encodeToString(data);
  }

  public static void main(String[] args) {
    // --- Create the 'messages' part of the JSON using org.json ---
    String messagesJsonString =
        """
    [
        {
            "role": "system",
            "content": "You are a helpful assistant."
        },
        {
            "role": "user",
            "content": "Why is the sky blue?"
        }
    ]
    """;

    JSONArray messagesArray;
    try {
      messagesArray = new JSONArray(messagesJsonString);
    } catch (Exception e) {
      System.err.println("Failed to parse JSON string into JSONArray: " + e.getMessage());
      return;
    }

    String modelId = "llama3.1:8b"; // Example model ID
    OllamaBaseLM ollamaLlm = new OllamaBaseLM(modelId);

    // --- Test Streaming Call ---
    System.out.println("--- Testing Streaming API Call ---");
    try {
      System.out.println("Attempting to call Ollama API (Streaming)...");
      System.out.println("Using model ID: " + modelId);
      System.out.println("Fetching Ollama endpoint from environment variable: " + OLLAMA_EP);

      BufferedReader responseReader = ollamaLlm.callLLMChatStream(modelId, messagesArray, null);

      if (responseReader != null) {
        System.out.println("\nAPI Call Successful! Streaming response:");
        responseReader
            .lines()
            .forEach(
                line -> {
                  System.out.println(line);
                });
      } else {
        System.err.println("Streaming API Call failed. Check logs for details.");
      }

    } catch (RuntimeException e) {
      System.err.println("Error during Streaming API call (Runtime): " + e.getMessage());
      System.err.println(
          "Please ensure the environment variable '" + OLLAMA_EP + "' is set correctly.");
      e.printStackTrace();
    } catch (Exception e) {
      System.err.println(
          "An unexpected error occurred during Streaming API call: " + e.getMessage());
      e.printStackTrace();
    }

    System.out.println("\n\n--- Testing Non-Streaming API Call ---");
    // --- Test Non-Streaming Call ---
    try {
      System.out.println("Attempting to call Ollama API (Non-Streaming)...");
      System.out.println("Using model ID: " + modelId);

      JSONObject responseJson =
          ollamaLlm.callLLMChat(
              GenerateContentConfig.builder().build(), modelId, messagesArray, null);

      if (responseJson != null && !responseJson.isEmpty()) {
        System.out.println("\nAPI Call Successful! Non-Streaming response:");
        System.out.println(responseJson.toString(4)); // Pretty print JSON
      } else {
        System.err.println("Non-Streaming API Call failed. Check logs for details.");
      }

    } catch (RuntimeException e) {
      System.err.println("Error during Non-Streaming API call (Runtime): " + e.getMessage());
      e.printStackTrace();
    } catch (Exception e) {
      System.err.println(
          "An unexpected error occurred during Non-Streaming API call: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
