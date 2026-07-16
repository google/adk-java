package com.google.adk.models;

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
import java.util.ArrayList;
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
 * Redbus AD Gateway to access Azure LLMs
 *
 * @author manoj.kumar, Sandeep Belgavi
 */
public class RedbusADG extends BaseLlm {

  private static final String DEFAULT_API_URL = "ADURL"; // https://abc.com";
  private static final String USERNAME_ENV_VAR = "ADU"; // Username
  private static final String PASSWORD_ENV_VAR = "ADP"; // Password
  private static final String FORBIDDEN_CHARACTERS_REGEX = "[^a-zA-Z0-9_\\.-]";
  private static final String CONTINUE_OUTPUT_MESSAGE =
      "Continue output. DO NOT look at this line. ONLY look at the content before this line and"
          + " system instruction.";

  /**
   * Cleans a string by removing any characters that are not allowed by the pattern
   * [a-zA-Z0-9_\\.-]. This pattern is typically required for names or identifiers.
   *
   * @param input The string to clean. Can be null.
   * @return The cleaned string, containing only allowed characters. Returns null if the input was
   *     null.
   */
  public static String cleanForIdentifierPattern(String input) {
    if (input == null) {
      return null;
    }
    // Replace all characters that do NOT match the allowed set with an empty string
    return input.replaceAll(FORBIDDEN_CHARACTERS_REGEX, "");
  }

  private static final Logger logger = LoggerFactory.getLogger(RedbusADG.class);

  public RedbusADG(String model) {
    super(model);
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    if (stream) {
      return generateContentStream(llmRequest);
    } else {
      return generateContentStd(llmRequest);
    }
  }

  public Flowable<LlmResponse> generateContentStd(LlmRequest llmRequest) {

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
                // Log a warning or handle appropriately
                System.err.println(
                    "Skipping tool '" + baseTool.name() + "' with missing declaration.");
                // continue; // If inside a loop
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
              JSONObject jsonTool = new JSONObject(toolMap);

              // Add the generated tool JSON object to your functions list/array
              functions.put(jsonTool);
            });

    // Check if the tool is executed, then parse and response.

    logger.debug("functions: {}", functions.toString(1));

    String modelId =
        this.model(); // "devstral";//"llama3.2:3b-instruct-q2_K";//"llama3.2"; // The 1b doesn't
    // support tool

    // If last user response has the function reponse, then function calla is not needed.
    boolean LAST_RESP_TOOl_EXECUTED =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();

    JSONObject agentresponse =
        callLLMChat(
            modelId,
            messages,
            LAST_RESP_TOOl_EXECUTED ? null : (functions.length() > 0 ? functions : null),
            false); // Tools/functions can not be of 0 length
    // Parse usage information from the response
    GenerateContentResponseUsageMetadata usageMetadata = getUsageMetadata(agentresponse);
    JSONObject responseQuantum =
        agentresponse.has("response")
            ? agentresponse
                .getJSONObject("response")
                .getJSONObject("openAIResponse")
                .getJSONArray("choices")
                .getJSONObject(0)
            : new JSONObject();

    // Check if tool call is required
    // Tools call
    LlmResponse.Builder responseBuilder = LlmResponse.builder();
    List<Part> parts = new ArrayList<>();
    Part part = oaiContentBlockToPart(responseQuantum);
    parts.add(part);

    // Call tool
    if (responseQuantum.has("finish_reason")
        && "function_call".contentEquals(responseQuantum.getString("finish_reason"))) {

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
    // Add usage metadata if available
    if (usageMetadata != null) {
      responseBuilder.usageMetadata(usageMetadata);
    }
    return Flowable.just(responseBuilder.build());
  }

  public Flowable<LlmResponse> generateContentStream(LlmRequest llmRequest) {
    List<Content> contents = llmRequest.contents();
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
    JSONArray messages = new JSONArray();
    JSONObject llmMessageJson1 = new JSONObject();
    llmMessageJson1.put("role", "system");
    llmMessageJson1.put("content", systemText);
    messages.put(llmMessageJson1);
    llmRequest.contents().stream()
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
    JSONArray functions = new JSONArray();
    llmRequest
        .tools()
        .entrySet()
        .forEach(
            tooldetail -> {
              BaseTool baseTool = tooldetail.getValue();
              Optional<FunctionDeclaration> declarationOptional = baseTool.declaration();
              if (!declarationOptional.isPresent()) {
                System.err.println(
                    "Skipping tool '" + baseTool.name() + "' with missing declaration.");
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
              JSONObject jsonTool = new JSONObject(toolMap);
              functions.put(jsonTool);
            });
    logger.info("Functions for LLM: {}", functions.toString(1));
    String modelId = this.model();
    boolean isLastResponseToolExecuted =
        Iterables.getLast(Iterables.getLast(contents).parts().get()).functionResponse().isPresent();
    BufferedReader reader =
        callLLMChatStream(
            modelId,
            messages,
            isLastResponseToolExecuted ? null : (functions.length() > 0 ? functions : null),
            true);
    Flowable<LlmResponse> flowable =
        Flowable.create(
            emitter -> {
              // Buffer for accumulating function call arguments per choice index
              final Map<Integer, String> functionCallNameBuffer = new HashMap<>();
              final Map<Integer, StringBuilder> functionCallArgsBuffer = new HashMap<>();
              final AtomicBoolean functionCallDetected = new AtomicBoolean(false);
              final StringBuilder accumulatedText = new StringBuilder();
              try {
                String line;
                // Usage tracking variables
                int totalPromptTokens = 0;
                int totalCompletionTokens = 0;
                int totalTokens = 0;
                outer:
                while (reader != null && (line = reader.readLine()) != null) {
                  line = line.trim();

                  if (line.startsWith("data:")) {
                    line = line.substring(5).trim();
                  }

                  if (line.equals("[DONE]")) {
                    logger.info("[DONE] marker found, completing stream");
                    if (accumulatedText.length() > 0 && !functionCallDetected.get()) {
                      // Create usage metadata if we have token counts
                      GenerateContentResponseUsageMetadata usageMetadata =
                          getUsageMetadata(totalPromptTokens, totalCompletionTokens, totalTokens);

                      // Emit any remaining accumulated text as a final, complete response
                      LlmResponse.Builder finalResponseBuilder =
                          LlmResponse.builder()
                              .content(
                                  Content.builder()
                                      .role("model")
                                      .parts(
                                          ImmutableList.of(
                                              Part.builder()
                                                  .text(accumulatedText.toString())
                                                  .build()))
                                      .build())
                              .partial(false); // This is a final content part

                      if (usageMetadata != null) {
                        finalResponseBuilder.usageMetadata(usageMetadata);
                      }

                      emitter.onNext(finalResponseBuilder.build());
                    }
                    break outer;
                  }
                  if (line.isEmpty()) {
                    //                    logger.info("Skipping empty line");
                    continue;
                  }
                  JSONObject chunk = null;
                  try {
                    if (!line.trim().isEmpty()) {
                      chunk = new JSONObject(line);
                    } else {
                      //                      logger.info("Skipping empty or null line after trim");
                    }
                  } catch (JSONException e) {
                    //                    logger.warn("Failed to parse JSON line: [{}]", line);
                    logger.warn("Error: {}", e.getMessage());
                    continue;
                  } catch (NullPointerException e) {
                    logger.warn("NullPointerException: 'line' variable is null.");
                    continue;
                  }
                  if (chunk == null) {
                    logger.info("Chunk is null, skipping");
                    continue;
                  }

                  // Parse usage information if present
                  if (chunk.has("usage")) {
                    JSONObject usage = chunk.optJSONObject("usage");
                    if (usage != null) {
                      totalPromptTokens =
                          Math.max(totalPromptTokens, usage.optInt("prompt_tokens", 0));
                      totalCompletionTokens =
                          Math.max(totalCompletionTokens, usage.optInt("completion_tokens", 0));
                      totalTokens = Math.max(totalTokens, usage.optInt("total_tokens", 0));
                      logger.info(
                          "Updated token counts: prompt={}, completion={}, total={}",
                          totalPromptTokens,
                          totalCompletionTokens,
                          totalTokens);
                    }
                  }

                  if (chunk.has("choices")) {
                    JSONArray choices = chunk.optJSONArray("choices");
                    //                    logger.info(
                    //                        "Choices array found, length: {}", choices != null ?
                    // choices.length() : 0);
                    if (choices == null || choices.length() == 0) {
                      //                      logger.info("Choices array is null or empty,
                      // skipping");
                      continue;
                    }
                    for (int i = 0; i < choices.length(); i++) {
                      JSONObject choice = choices.optJSONObject(i);
                      if (choice != null) {
                        boolean done = "stop".equals(choice.optString("finish_reason", ""));
                        JSONObject delta = choice.optJSONObject("delta");
                        if (delta != null) {
                          // Buffer function_call arguments
                          if (delta.has("function_call")) {
                            // If there's accumulated text, emit it as a complete response before
                            // the function call
                            if (accumulatedText.length() > 0) {
                              LlmResponse aggregatedTextResponse =
                                  LlmResponse.builder()
                                      .content(
                                          Content.builder()
                                              .role("model")
                                              .parts(
                                                  ImmutableList.of(
                                                      Part.builder()
                                                          .text(accumulatedText.toString())
                                                          .build()))
                                              .build())
                                      .partial(
                                          false) // This is a complete text turn before function
                                      // call
                                      .build();
                              logger.info(
                                  "Emitting aggregated text before FunctionCall: {}",
                                  aggregatedTextResponse);
                              emitter.onNext(aggregatedTextResponse);
                              accumulatedText.setLength(0); // Clear buffer after emitting
                            }
                            JSONObject functionCallJson = delta.getJSONObject("function_call");
                            String functionName = functionCallJson.optString("name", null);
                            String argumentsFragment =
                                functionCallJson.optString("arguments", null);
                            if (functionName != null) {
                              functionCallNameBuffer.put(i, functionName);
                            }
                            if (argumentsFragment != null) {
                              functionCallArgsBuffer
                                  .computeIfAbsent(i, k -> new StringBuilder())
                                  .append(argumentsFragment);
                            }
                          } else if (choice.has(
                              "function_call")) { // Check function_call directly in choice if delta
                            // is null
                            JSONObject functionCallJson = choice.getJSONObject("function_call");
                            String functionName = functionCallJson.optString("name", null);
                            String argumentsFragment =
                                functionCallJson.optString("arguments", null);
                            if (functionName != null) {
                              functionCallNameBuffer.put(i, functionName);
                            }
                            if (argumentsFragment != null) {
                              functionCallArgsBuffer
                                  .computeIfAbsent(i, k -> new StringBuilder())
                                  .append(argumentsFragment);
                            }
                            functionCallDetected.set(true); // Set flag if function call is found
                          }
                          // If finish_reason is function_call, emit the function call event
                          if (choice.has("finish_reason")
                              && "function_call".equals(choice.optString("finish_reason"))) {
                            String functionName = functionCallNameBuffer.get(i);
                            StringBuilder argsBuilder = functionCallArgsBuffer.get(i);
                            Map<String, Object> functionArgs = new HashMap<>();
                            if (argsBuilder != null) {
                              String argsString = argsBuilder.toString();
                              try {
                                if (!argsString.isEmpty()) {
                                  JSONObject argsJson = new JSONObject(argsString);
                                  for (String key : argsJson.keySet()) {
                                    functionArgs.put(key, argsJson.get(key));
                                  }
                                }
                              } catch (JSONException e) {
                                logger.warn(
                                    "Failed to parse accumulated function_call arguments as JSON: {}",
                                    argsString,
                                    e);
                              }
                            }
                            if (functionName != null) {
                              FunctionCall functionCall =
                                  FunctionCall.builder()
                                      .name(functionName)
                                      .args(functionArgs)
                                      .build();
                              LlmResponse functionCallResponse =
                                  LlmResponse.builder()
                                      .content(
                                          Content.builder()
                                              .role("model")
                                              .parts(
                                                  ImmutableList.of(
                                                      Part.builder()
                                                          .functionCall(functionCall)
                                                          .build()))
                                              .build())
                                      .partial(false) // Function call is a complete turn
                                      .build();
                              logger.info(
                                  "Emitting FunctionCall LlmResponse: {}", functionCallResponse);
                              emitter.onNext(functionCallResponse);
                            }
                            // Clear buffers for this index
                            functionCallArgsBuffer.remove(i);
                            functionCallNameBuffer.remove(i);
                            functionCallDetected.set(true); // Set flag if function call is found
                          }

                          // Handle text content as a separate event
                          String text = delta.optString("content", "");
                          if (text != null && !text.isEmpty()) {
                            accumulatedText.append(text);
                            LlmResponse textResponse =
                                LlmResponse.builder()
                                    .content(
                                        Content.builder()
                                            .role("model")
                                            .parts(ImmutableList.of(Part.fromText(text)))
                                            .build())
                                    .partial(true) // Text chunks are usually partial
                                    .build();
                            emitter.onNext(textResponse);
                          }
                        } else if (choice.has(
                            "content")) { // This handles non-delta content, likely for final
                          // response
                          String text = choice.optString("content", "");
                          if (text != null && !text.isEmpty()) {
                            LlmResponse finalResponse =
                                LlmResponse.builder()
                                    .content(
                                        Content.builder()
                                            .role("model")
                                            .parts(ImmutableList.of(Part.fromText(text)))
                                            .build())
                                    .partial(false) // This is a final content part
                                    .build();
                            logger.info("Emitting Final Text LlmResponse: {}", finalResponse);
                            emitter.onNext(finalResponse);
                          }
                        }
                      }
                    }
                  }
                }
                emitter.onComplete();
              } catch (IOException e) {
                logger.error("IOException in stream: {}", e.getMessage());
                emitter.onError(e);
              } finally {
                try {
                  if (reader != null) {
                    logger.info("Closing BufferedReader");
                    reader.close();
                  }
                } catch (IOException e) {
                  logger.error("Error closing stream reader", e);
                }
              }
            },
            io.reactivex.rxjava3.core.BackpressureStrategy.BUFFER);
    return flowable;
  }

  /**
   * This method is specifically for parsing *complete* OpenAI-like content blocks in a
   * non-streaming context. It is less suitable for incremental streaming parsing.
   *
   * @param choice0 The JSON object representing a single choice from an OpenAI-like response.
   * @return A Part object.
   */
  public static Part oaiContentBlockToPart(JSONObject choice0) {
    // Assuming choice0 is already the "choice" object, not the full stream chunk.
    JSONObject message = choice0.optJSONObject("message"); // This might be null for stream deltas
    if (message == null) {
      // For non-streaming, a 'message' object should usually be present.
      // For streaming 'delta' might be directly at the choice level for function calls
      // Or directly within 'delta'
      throw new UnsupportedOperationException(
          "Input choice0 does not contain a 'message' object for content parsing.");
    }

    if (message.has("function_call")) {
      JSONObject function = message.getJSONObject("function_call");

      if (function.has("name")) {
        String name = function.optString("name", null);
        Map<String, Object> args = new HashMap<>();

        // Try to get arguments as a JSONObject directly
        JSONObject argsJson = function.optJSONObject("arguments");
        if (argsJson != null) {
          args = argsJson.toMap();
        } else if (function.has("arguments")) {
          // If not a direct JSONObject, try to parse as a stringified JSON
          String argsString = function.optString("arguments", null);
          if (argsString != null && !argsString.isEmpty()) {
            try {
              argsJson = new JSONObject(argsString);
              args = argsJson.toMap();
            } catch (JSONException e) {
              logger.warn("Failed to parse function arguments as JSON string: {}", argsString, e);
              // Continue with empty args if parsing fails
            }
          }
        }

        if (name != null) {
          FunctionCall functionCall = FunctionCall.builder().name(name).args(args).build();
          return Part.builder().functionCall(functionCall).build();
        }
      }
    }

    if (message.has("content")) {
      Object content = message.opt("content");
      if (content instanceof String) {
        String text = (String) content;
        return Part.builder().text(text).build();
      }
    }

    // Fallback if no recognizable content or function call is found.
    throw new UnsupportedOperationException(
        "Unsupported content block format or missing required fields in message: "
            + message.toString());
  }

  // Create a shared HttpClient instance (thread-safe and efficient)
  private static final HttpClient httpClient =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_2) // Or HTTP_1_1
          .connectTimeout(Duration.ofSeconds(60)) // Example timeout
          .build();

  public static BufferedReader callLLMChatStream(
      String model, JSONArray messages, JSONArray tools, boolean stream) {
    String username = System.getenv(USERNAME_ENV_VAR);
    String password = System.getenv(PASSWORD_ENV_VAR);
    String apiUrl = System.getenv(DEFAULT_API_URL);

    if (username == null || username.isEmpty()) {
      throw new RuntimeException("Environment variable '" + USERNAME_ENV_VAR + "' not set.");
    }
    if (password == null || password.isEmpty()) {
      throw new RuntimeException("Environment variable '" + PASSWORD_ENV_VAR + "' not set.");
    }
    if (apiUrl == null || apiUrl.isEmpty()) {
      throw new RuntimeException("Environment variable '" + DEFAULT_API_URL + "' not set.");
    }

    JSONObject payload = new JSONObject();
    payload.put("username", username);
    payload.put("password", password);
    payload.put("stream", stream); // Ensure this matches the request's stream parameter

    payload.put("api", model);

    JSONObject request = new JSONObject();
    request.put("messages", messages);
    if (tools != null) {
      request.put(
          "functions", tools); // Assuming 'functions' is the correct key for RedbusADG's backend
    }
    request.put("temperature", 0.9);
    request.put("stream", stream); // Ensure this matches the payload's stream parameter

    payload.put("request", request);
    String jsonString = payload.toString();

    try {

      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(apiUrl))
              .header("Content-Type", "application/json; charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(jsonString, StandardCharsets.UTF_8))
              .build();
      HttpResponse<InputStream> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      int statusCode = response.statusCode();
      StringBuilder responseBody = new StringBuilder();
      System.out.println("Response Code: " + statusCode);
      if (statusCode >= 200 && statusCode < 300) {

        return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));

      } else {
        // Read error stream for more details if available
        System.err.println("Error Response Body: " + responseBody.toString());
        logger.error(
            "HTTP request failed with status code {}: {}", statusCode, responseBody.toString());
        return null;
      }

    } catch (IOException | InterruptedException ex) {
      logger.error("HTTP request failed during streaming call.", ex);
      return null;
    } catch (Exception ex) {
      logger.error("An unexpected error occurred during streaming API call.", ex);
      return null;
    }
  }

  /**
   * - * Makes a POST request to a specified URL with a dynamic JSON body using HttpClient. Fetches
   * - * username and password from environment variables. - * - * @param model The model ID (used
   * in the "api" field of the request payload). - * @param messages The list of messages for the
   * "request.messages" field. - * @param tools The list of tools/functions for the
   * "request.functions" field (can be null). - * @return The response body as a JSONObject, or an
   * empty JSONObject in case of failure. - * @throws RuntimeException If environment variables are
   * not set. -
   */
  public static JSONObject callLLMChat(
      String model, JSONArray messages, JSONArray tools, boolean stream) {
    String username = System.getenv(USERNAME_ENV_VAR);
    String password = System.getenv(PASSWORD_ENV_VAR);
    String apiUrl = System.getenv(DEFAULT_API_URL);

    if (username == null || username.isEmpty()) {
      throw new RuntimeException("Environment variable '" + USERNAME_ENV_VAR + "' not set.");
    }
    if (password == null || password.isEmpty()) {
      throw new RuntimeException("Environment variable '" + PASSWORD_ENV_VAR + "' not set.");
    }
    if (apiUrl == null || apiUrl.isEmpty()) {
      throw new RuntimeException("Environment variable '" + DEFAULT_API_URL + "' not set.");
    }

    JSONObject payload = new JSONObject();
    payload.put("username", username);
    payload.put("password", password);
    payload.put("api", model);

    JSONObject request = new JSONObject();
    request.put("messages", messages);
    if (tools != null) {
      request.put("functions", tools);
    }
    request.put("temperature", 0.9);
    request.put("stream", stream);

    payload.put("request", request);
    String jsonString = payload.toString();

    try {
      HttpRequest httpRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(apiUrl))
              .header("Content-Type", "application/json; charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(jsonString, StandardCharsets.UTF_8))
              .build();

      HttpResponse<String> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

      int statusCode = response.statusCode();
      String responseBody = response.body();
      if (statusCode >= 200 && statusCode < 300) {
        return new JSONObject(responseBody);
      } else {
        try {
          return new JSONObject(responseBody);
        } catch (Exception jsonEx) {
          logger.warn("Could not parse error response body as JSON: {}", responseBody, jsonEx);
          return new JSONObject();
        }
      }

    } catch (IOException | InterruptedException ex) {
      logger.error("HTTP request failed during non-streaming call.", ex);
      return new JSONObject();
    } catch (Exception ex) {
      logger.error("An unexpected error occurred during non-streaming API call.", ex);
      return new JSONObject();
    }
  }

  @Override // Re-added @Override based on BaseLlm abstract method
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException("Not supported yet.");
  }

  private GenerateContentResponseUsageMetadata getUsageMetadata(JSONObject agentResponse) {
    return Optional.ofNullable(agentResponse)
        .map(response -> response.optJSONObject("response"))
        .map(response -> response.optJSONObject("openAIResponse"))
        .map(openAIResponse -> openAIResponse.optJSONObject("usage"))
        .flatMap(
            usage -> {
              int promptTokens = usage.optInt("prompt_tokens", 0);
              int completionTokens = usage.optInt("completion_tokens", 0);
              int totalTokens = usage.optInt("total_tokens", 0);

              if (totalTokens == 0) {
                totalTokens = promptTokens + completionTokens;
              }

              if (totalTokens > 0) {
                logger.info(
                    "Non-streaming token counts: prompt={}, completion={}, total={}",
                    promptTokens,
                    completionTokens,
                    totalTokens);
                return Optional.of(
                    GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(promptTokens)
                        .candidatesTokenCount(completionTokens)
                        .totalTokenCount(totalTokens)
                        .build());
              }
              return Optional.empty();
            })
        .orElse(null);
  }

  private GenerateContentResponseUsageMetadata getUsageMetadata(
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

  public static void main(String[] args) {
    // Example model ID as a String
    String modelId = "413";
    try {
      RedbusADG llm = new RedbusADG(modelId);
      // Create a simple LlmRequest with a user prompt
      LlmRequest request =
          LlmRequest.builder()
              .contents(ImmutableList.of(Content.fromParts(Part.fromText("Tell me a joke."))))
              .build();
      llm.generateContent(request, true)
          .blockingSubscribe(
              response -> {
                // Print each streaming response chunk
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
                                                      text -> System.out.println("Text: " + text));
                                              part.functionCall()
                                                  .ifPresent(
                                                      fc ->
                                                          System.out.println(
                                                              "Function Call: " + fc));
                                            })));
              },
              error -> {
                System.err.println("An error occurred during streaming API call:");
                error.printStackTrace();
              },
              () -> System.out.println("\nStream completed."));
    } catch (RuntimeException e) {
      System.err.println("Error during API call (Runtime): " + e.getMessage());
      System.err.println(
          "Please ensure environment variables '"
              + USERNAME_ENV_VAR
              + "', '"
              + PASSWORD_ENV_VAR
              + "', and '"
              + DEFAULT_API_URL
              + "' are set.");
    } catch (Exception e) {
      System.err.println("An unexpected error occurred during API call: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
