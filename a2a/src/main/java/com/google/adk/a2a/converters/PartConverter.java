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
package com.google.adk.a2a.converters;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.a2a.common.GenAiFieldMissingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Language;
import com.google.genai.types.Outcome;
import com.google.genai.types.Part;
import io.a2a.spec.DataPart;
import io.a2a.spec.FileContent;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
import io.a2a.spec.FileWithUri;
import io.a2a.spec.Message;
import io.a2a.spec.TextPart;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class for converting between Google GenAI Parts and A2A DataParts. */
public final class PartConverter {

  private static final Logger logger = LoggerFactory.getLogger(PartConverter.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();
  // Constants for metadata types.
  public static final String LANGUAGE_KEY = "language";
  public static final String OUTCOME_KEY = "outcome";
  public static final String CODE_KEY = "code";
  public static final String OUTPUT_KEY = "output";
  public static final String NAME_KEY = "name";
  public static final String ARGS_KEY = "args";
  public static final String RESPONSE_KEY = "response";
  public static final String ID_KEY = "id";
  public static final String WILL_CONTINUE_KEY = "willContinue";
  public static final String PARTIAL_ARGS_KEY = "partialArgs";
  public static final String SCHEDULING_KEY = "scheduling";
  public static final String PARTS_KEY = "parts";

  public static Optional<TextPart> toTextPart(io.a2a.spec.Part<?> part) {
    if (part instanceof TextPart textPart) {
      return Optional.of(textPart);
    }
    return Optional.empty();
  }

  /** Convert an A2A JSON part into a Google GenAI part representation. */
  public static com.google.genai.types.Part toGenaiPart(io.a2a.spec.Part<?> a2aPart) {
    if (a2aPart == null) {
      throw new IllegalArgumentException("A2A part cannot be null");
    }

    if (a2aPart instanceof TextPart textPart) {
      return com.google.genai.types.Part.builder().text(textPart.getText()).build();
    }

    if (a2aPart instanceof FilePart filePart) {
      return convertFilePartToGenAiPart(filePart);
    }

    if (a2aPart instanceof DataPart dataPart) {
      return convertDataPartToGenAiPart(dataPart);
    }

    throw new IllegalArgumentException("Unsupported A2A part type: " + a2aPart.getClass());
  }

  public static ImmutableList<com.google.genai.types.Part> toGenaiParts(
      List<io.a2a.spec.Part<?>> a2aParts) {
    return a2aParts.stream().map(PartConverter::toGenaiPart).collect(toImmutableList());
  }

  private static com.google.genai.types.Part convertFilePartToGenAiPart(FilePart filePart) {
    FileContent fileContent = filePart.getFile();
    if (fileContent instanceof FileWithUri fileWithUri) {
      return com.google.genai.types.Part.builder()
          .fileData(
              FileData.builder()
                  .fileUri(fileWithUri.uri())
                  .mimeType(fileWithUri.mimeType())
                  .build())
          .build();
    }

    if (fileContent instanceof FileWithBytes fileWithBytes) {
      String bytesString = fileWithBytes.bytes();
      if (bytesString == null) {
        throw new GenAiFieldMissingException("FileWithBytes missing byte content");
      }
      byte[] decoded = Base64.getDecoder().decode(bytesString);
      return com.google.genai.types.Part.builder()
          .inlineData(Blob.builder().data(decoded).mimeType(fileWithBytes.mimeType()).build())
          .build();
    }

    throw new IllegalArgumentException("Unsupported FilePart content: " + fileContent.getClass());
  }

  private static com.google.genai.types.Part convertDataPartToGenAiPart(DataPart dataPart) {
    Map<String, Object> data =
        Optional.ofNullable(dataPart.getData()).map(HashMap::new).orElseGet(HashMap::new);
    Map<String, Object> metadata =
        Optional.ofNullable(dataPart.getMetadata()).map(HashMap::new).orElseGet(HashMap::new);

    String metadataType = metadata.getOrDefault(A2AMetadataKey.TYPE.getType(), "").toString();

    if ((data.containsKey(NAME_KEY) && data.containsKey(ARGS_KEY))
        || metadataType.equals(A2ADataPartMetadataType.FUNCTION_CALL.getType())) {
      String functionName = String.valueOf(data.getOrDefault(NAME_KEY, ""));
      String functionId = String.valueOf(data.getOrDefault(ID_KEY, ""));
      Map<String, Object> args = coerceToMap(data.get(ARGS_KEY));
      return com.google.genai.types.Part.builder()
          .functionCall(FunctionCall.builder().name(functionName).id(functionId).args(args).build())
          .build();
    }

    if ((data.containsKey(NAME_KEY) && data.containsKey(RESPONSE_KEY))
        || metadataType.equals(A2ADataPartMetadataType.FUNCTION_RESPONSE.getType())) {
      String functionName = String.valueOf(data.getOrDefault(NAME_KEY, ""));
      String functionId = String.valueOf(data.getOrDefault(ID_KEY, ""));
      Map<String, Object> response = coerceToMap(data.get(RESPONSE_KEY));
      return com.google.genai.types.Part.builder()
          .functionResponse(
              FunctionResponse.builder()
                  .name(functionName)
                  .id(functionId)
                  .response(response)
                  .build())
          .build();
    }

    if ((data.containsKey(CODE_KEY) && data.containsKey(LANGUAGE_KEY))
        || metadataType.equals(A2ADataPartMetadataType.EXECUTABLE_CODE.getType())) {
      String code = String.valueOf(data.getOrDefault(CODE_KEY, ""));
      String language =
          String.valueOf(
              data.getOrDefault(LANGUAGE_KEY, Language.Known.LANGUAGE_UNSPECIFIED.toString()));
      return com.google.genai.types.Part.builder()
          .executableCode(
              ExecutableCode.builder().code(code).language(new Language(language)).build())
          .build();
    }

    if ((data.containsKey(OUTCOME_KEY) && data.containsKey(OUTPUT_KEY))
        || metadataType.equals(A2ADataPartMetadataType.CODE_EXECUTION_RESULT.getType())) {
      String outcome =
          String.valueOf(data.getOrDefault(OUTCOME_KEY, Outcome.Known.OUTCOME_OK).toString());
      String output = String.valueOf(data.getOrDefault(OUTPUT_KEY, ""));
      return com.google.genai.types.Part.builder()
          .codeExecutionResult(
              CodeExecutionResult.builder().outcome(new Outcome(outcome)).output(output).build())
          .build();
    }

    try {
      String json = objectMapper.writeValueAsString(data);
      return com.google.genai.types.Part.builder().text(json).build();
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Failed to serialize DataPart payload", e);
    }
  }

  /**
   * Converts an A2A Message to a Google GenAI Content object.
   *
   * @param message The A2A Message to convert.
   * @return The converted Google GenAI Content object.
   */
  public static Content messageToContent(Message message) {
    ImmutableList<com.google.genai.types.Part> parts = toGenaiParts(message.getParts());
    return Content.builder().role("user").parts(parts).build();
  }

  /**
   * Creates an A2A DataPart from a Google GenAI FunctionResponse.
   *
   * @return Optional containing the converted A2A Part, or empty if conversion fails.
   */
  private static DataPart createDataPartFromFunctionCall(
      FunctionCall functionCall, ImmutableMap.Builder<String, Object> metadata) {
    ImmutableMap.Builder<String, Object> data = ImmutableMap.builder();
    addValueIfPresent(data, NAME_KEY, functionCall.name());
    addValueIfPresent(data, ID_KEY, functionCall.id());
    addValueIfPresent(data, ARGS_KEY, functionCall.args());
    addValueIfPresent(data, WILL_CONTINUE_KEY, functionCall.willContinue());
    addValueIfPresent(data, PARTIAL_ARGS_KEY, functionCall.partialArgs());

    metadata.put(A2AMetadataKey.TYPE.getType(), A2ADataPartMetadataType.FUNCTION_CALL.getType());

    return new DataPart(data.buildOrThrow(), metadata.buildOrThrow());
  }

  private static void addValueIfPresent(
      ImmutableMap.Builder<String, Object> data, String key, Optional<?> value) {
    value.ifPresent(v -> data.put(key, v));
  }

  /**
   * Creates an A2A DataPart from a Google GenAI FunctionResponse.
   *
   * @param functionResponse The GenAI FunctionResponse to convert.
   * @return The converted A2A Part.
   */
  private static DataPart createDataPartFromFunctionResponse(
      FunctionResponse functionResponse, ImmutableMap.Builder<String, Object> metadata) {
    ImmutableMap.Builder<String, Object> data = ImmutableMap.builder();
    addValueIfPresent(data, NAME_KEY, functionResponse.name());
    addValueIfPresent(data, ID_KEY, functionResponse.id());
    addValueIfPresent(data, RESPONSE_KEY, functionResponse.response());
    addValueIfPresent(data, WILL_CONTINUE_KEY, functionResponse.willContinue());
    addValueIfPresent(data, SCHEDULING_KEY, functionResponse.scheduling());
    addValueIfPresent(data, PARTS_KEY, functionResponse.parts());

    metadata.put(
        A2AMetadataKey.TYPE.getType(), A2ADataPartMetadataType.FUNCTION_RESPONSE.getType());

    return new DataPart(data.buildOrThrow(), metadata.buildOrThrow());
  }

  /**
   * Creates an A2A DataPart from a Google GenAI CodeExecutionResult.
   *
   * @param codeExecutionResult The GenAI CodeExecutionResult to convert.
   * @return The converted A2A Part.
   */
  private static DataPart createDataPartFromCodeExecutionResult(
      CodeExecutionResult codeExecutionResult, ImmutableMap.Builder<String, Object> metadata) {
    ImmutableMap.Builder<String, Object> data = ImmutableMap.builder();
    data.put(
        OUTCOME_KEY,
        codeExecutionResult
            .outcome()
            .map(Outcome::toString)
            .orElse(new Outcome(Outcome.Known.OUTCOME_UNSPECIFIED).toString()));
    addValueIfPresent(data, OUTPUT_KEY, codeExecutionResult.output());

    metadata.put(
        A2AMetadataKey.TYPE.getType(), A2ADataPartMetadataType.CODE_EXECUTION_RESULT.getType());

    return new DataPart(data.buildOrThrow(), metadata.buildOrThrow());
  }

  /**
   * Creates an A2A DataPart from a Google GenAI ExecutableCode.
   *
   * @param executableCode The GenAI ExecutableCode to convert.
   * @return The converted A2A Part.
   */
  private static DataPart createDataPartFromExecutableCode(
      ExecutableCode executableCode, ImmutableMap.Builder<String, Object> metadata) {
    ImmutableMap.Builder<String, Object> data = ImmutableMap.builder();
    data.put(
        LANGUAGE_KEY,
        executableCode
            .language()
            .map(Language::toString)
            .orElse(Language.Known.LANGUAGE_UNSPECIFIED.toString()));
    addValueIfPresent(data, CODE_KEY, executableCode.code());

    metadata.put(A2AMetadataKey.TYPE.getType(), A2ADataPartMetadataType.EXECUTABLE_CODE.getType());

    return new DataPart(data.buildOrThrow(), metadata.buildOrThrow());
  }

  private PartConverter() {}

  /** Convert a GenAI part into the A2A JSON representation. */
  public static io.a2a.spec.Part<?> fromGenaiPart(Part part, boolean isPartial) {
    if (part == null) {
      throw new GenAiFieldMissingException("GenAI part cannot be null");
    }
    ImmutableMap.Builder<String, Object> metadata = ImmutableMap.builder();
    if (isPartial) {
      metadata.put(A2AMetadataKey.PARTIAL.getType(), true);
    }

    if (part.text().isPresent()) {
      addValueIfPresent(metadata, "thought", part.thought());
      return new TextPart(part.text().get(), metadata.buildOrThrow());
    }

    if (part.fileData().isPresent() || part.inlineData().isPresent()) {
      return filePartToA2A(part, metadata);
    }

    if (part.functionCall().isPresent()
        || part.functionResponse().isPresent()
        || part.executableCode().isPresent()
        || part.codeExecutionResult().isPresent()) {
      return dataPartToA2A(part, metadata);
    }

    throw new IllegalArgumentException("Unsupported GenAI part type: " + part);
  }

  private static DataPart dataPartToA2A(Part part, ImmutableMap.Builder<String, Object> metadata) {

    if (part.functionCall().isPresent()) {
      return createDataPartFromFunctionCall(part.functionCall().get(), metadata);
    } else if (part.functionResponse().isPresent()) {
      return createDataPartFromFunctionResponse(part.functionResponse().get(), metadata);
    } else if (part.codeExecutionResult().isPresent()) {
      return createDataPartFromCodeExecutionResult(part.codeExecutionResult().get(), metadata);
    } else if (part.executableCode().isPresent()) {
      return createDataPartFromExecutableCode(part.executableCode().get(), metadata);
    }

    throw new IllegalArgumentException("Unsupported GenAI data part type: " + part);
  }

  private static FilePart filePartToA2A(Part part, ImmutableMap.Builder<String, Object> metadata) {
    if (part.fileData().isPresent()) {
      FileData fileData = part.fileData().get();
      String uri = fileData.fileUri().orElse(null);
      String mime = fileData.mimeType().orElse(null);
      String name = fileData.displayName().orElse(null);
      return new FilePart(new FileWithUri(mime, name, uri), metadata.buildOrThrow());
    }
    Blob blob = part.inlineData().get();
    byte[] bytes = blob.data().orElse(null);
    String encoded = bytes != null ? Base64.getEncoder().encodeToString(bytes) : null;
    addValueIfPresent(metadata, "video_metadata", part.videoMetadata());
    return new FilePart(
        new FileWithBytes(blob.mimeType().orElse(null), blob.displayName().orElse(null), encoded),
        metadata.buildOrThrow());
  }

  /**
   * Converts a remote call part to a user part.
   *
   * <p>Events are rephrased as if a user was telling what happened in the session up to the point.
   * E.g.
   *
   * <pre>{@code
   * For context:
   * User said: Now help me with Z
   * Agent A said: Agent B can help you with it!
   * Agent B said: Agent C might know better.*
   * }</pre>
   *
   * @param author The author of the part.
   * @param part The part to convert.
   * @return The converted part.
   */
  public static Part remoteCallAsUserPart(String author, Part part) {
    if (part.text().isPresent()) {
      String partText = String.format("[%s] said: %s", author, part.text().get());
      return Part.builder().text(partText).build();
    } else if (part.functionCall().isPresent()) {
      FunctionCall functionCall = part.functionCall().get();
      String partText =
          String.format(
              "[%s] called tool %s with parameters: %s",
              author,
              functionCall.name().orElse("<unknown>"),
              functionCall.args().orElse(ImmutableMap.of()));
      return Part.builder().text(partText).build();
    } else if (part.functionResponse().isPresent()) {
      FunctionResponse functionResponse = part.functionResponse().get();
      String partText =
          String.format(
              "[%s] %s tool returned result: %s",
              author,
              functionResponse.name().orElse("<unknown>"),
              functionResponse.response().orElse(ImmutableMap.of()));
      return Part.builder().text(partText).build();
    } else {
      return part;
    }
  }

  @SuppressWarnings("unchecked") // safe conversion from objectMapper.readValue
  private static Map<String, Object> coerceToMap(Object value) {
    if (value == null) {
      return new HashMap<>();
    }
    if (value instanceof Optional<?> optional) {
      return coerceToMap(optional.orElse(null));
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> result = new HashMap<>();
      map.forEach((k, v) -> result.put(String.valueOf(k), v));
      return result;
    }
    if (value instanceof String str) {
      if (str.isEmpty()) {
        return new HashMap<>();
      }
      try {
        return objectMapper.readValue(str, Map.class);
      } catch (JsonProcessingException e) {
        logger.warn("Failed to parse map from string payload", e);
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("value", str);
        return fallback;
      }
    }
    Map<String, Object> wrapper = new HashMap<>();
    wrapper.put("value", value);
    return wrapper;
  }
}
