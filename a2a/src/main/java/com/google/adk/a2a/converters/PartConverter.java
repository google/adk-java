package com.google.adk.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Blob;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.a2a.spec.DataPart;
import io.a2a.spec.FileContent;
import io.a2a.spec.FilePart;
import io.a2a.spec.FileWithBytes;
import io.a2a.spec.FileWithUri;
import io.a2a.spec.TextPart;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for converting between Google GenAI Parts and A2A DataParts.
 *
 * @apiNote **EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do
 *     not use in production code.
 */
public final class PartConverter {
  private static final Logger logger = LoggerFactory.getLogger(PartConverter.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  // Constants for metadata types
  public static final String A2A_DATA_PART_METADATA_TYPE_KEY = "type";
  public static final String A2A_DATA_PART_METADATA_IS_LONG_RUNNING_KEY = "is_long_running";
  public static final String A2A_DATA_PART_METADATA_TYPE_FUNCTION_CALL = "function_call";
  public static final String A2A_DATA_PART_METADATA_TYPE_FUNCTION_RESPONSE = "function_response";
  public static final String A2A_DATA_PART_METADATA_TYPE_CODE_EXECUTION_RESULT =
      "code_execution_result";
  public static final String A2A_DATA_PART_METADATA_TYPE_EXECUTABLE_CODE = "executable_code";

  /** Convert an A2A JSON part into a Google GenAI part representation. */
  public static Optional<com.google.genai.types.Part> toGenaiPart(io.a2a.spec.Part<?> a2aPart) {
    if (a2aPart == null) {
      return Optional.empty();
    }

    if (a2aPart instanceof TextPart textPart) {
      return Optional.of(com.google.genai.types.Part.builder().text(textPart.getText()).build());
    }

    if (a2aPart instanceof FilePart filePart) {
      return convertFilePartToGenAiPart(filePart);
    }

    if (a2aPart instanceof DataPart dataPart) {
      return convertDataPartToGenAiPart(dataPart);
    }

    logger.warn("Unsupported A2A part type: {}", a2aPart.getClass());
    return Optional.empty();
  }

  /**
   * Convert a Google GenAI Part to an A2A Part.
   *
   * @param part The GenAI part to convert.
   * @return Optional containing the converted A2A Part, or empty if conversion fails.
   */
  public static Optional<DataPart> convertGenaiPartToA2aPart(Part part) {
    if (part == null) {
      return Optional.empty();
    }

    if (part.text().isPresent()) {
      // Text parts are handled directly in the Message content, not as DataPart
      return Optional.empty();
    } else if (part.functionCall().isPresent()) {
      return createDataPartFromFunctionCall(part.functionCall().get());
    } else if (part.functionResponse().isPresent()) {
      return createDataPartFromFunctionResponse(part.functionResponse().get());
    }

    logger.warn("Cannot convert unsupported part for Google GenAI part: " + part);
    return Optional.empty();
  }

  private static Optional<com.google.genai.types.Part> convertFilePartToGenAiPart(
      FilePart filePart) {
    FileContent fileContent = filePart.getFile();
    if (fileContent instanceof FileWithUri fileWithUri) {
      return Optional.of(
          com.google.genai.types.Part.builder()
              .fileData(
                  FileData.builder()
                      .fileUri(fileWithUri.uri())
                      .mimeType(fileWithUri.mimeType())
                      .build())
              .build());
    }

    if (fileContent instanceof FileWithBytes fileWithBytes) {
      String bytesString = fileWithBytes.bytes();
      if (bytesString == null) {
        logger.warn("FileWithBytes missing byte content");
        return Optional.empty();
      }
      try {
        byte[] decoded = Base64.getDecoder().decode(bytesString);
        return Optional.of(
            com.google.genai.types.Part.builder()
                .inlineData(Blob.builder().data(decoded).mimeType(fileWithBytes.mimeType()).build())
                .build());
      } catch (IllegalArgumentException e) {
        logger.warn("Failed to decode base64 file content", e);
        return Optional.empty();
      }
    }

    logger.warn("Unsupported FilePart content: {}", fileContent.getClass());
    return Optional.empty();
  }

  private static Optional<com.google.genai.types.Part> convertDataPartToGenAiPart(
      DataPart dataPart) {
    Map<String, Object> data =
        Optional.ofNullable(dataPart.getData()).map(HashMap::new).orElse(new HashMap<>());
    Map<String, Object> metadata =
        Optional.ofNullable(dataPart.getMetadata()).map(HashMap::new).orElse(new HashMap<>());

    String metadataType = metadata.getOrDefault(A2A_DATA_PART_METADATA_TYPE_KEY, "").toString();

    if (data.containsKey("name") && data.containsKey("args")
        || A2A_DATA_PART_METADATA_TYPE_FUNCTION_CALL.equals(metadataType)) {
      String functionName = String.valueOf(data.getOrDefault("name", ""));
      String functionId = String.valueOf(data.getOrDefault("id", ""));
      Map<String, Object> args = coerceToMap(data.get("args"));
      return Optional.of(
          com.google.genai.types.Part.builder()
              .functionCall(FunctionCall.builder().name(functionName).id(functionId).args(args).build())
              .build());
    }

    if (data.containsKey("name") && data.containsKey("response")
        || A2A_DATA_PART_METADATA_TYPE_FUNCTION_RESPONSE.equals(metadataType)) {
      String functionName = String.valueOf(data.getOrDefault("name", ""));
      String functionId = String.valueOf(data.getOrDefault("id", ""));
      Map<String, Object> response = coerceToMap(data.get("response"));
      return Optional.of(
          com.google.genai.types.Part.builder()
              .functionResponse(
                  FunctionResponse.builder().name(functionName).id(functionId).response(response).build())
              .build());
    }

    try {
      String json = OBJECT_MAPPER.writeValueAsString(data);
      return Optional.of(com.google.genai.types.Part.builder().text(json).build());
    } catch (JsonProcessingException e) {
      logger.warn("Failed to serialize DataPart payload", e);
      return Optional.empty();
    }
  }

  /**
   * Creates an A2A DataPart from a Google GenAI FunctionResponse.
   *
   * @return Optional containing the converted A2A Part, or empty if conversion fails.
   */
  private static Optional<DataPart> createDataPartFromFunctionCall(FunctionCall functionCall) {
    Map<String, Object> data = new HashMap<>();
    data.put("name", functionCall.name().orElse(""));
    data.put("args", functionCall.args().orElse(Map.of()));

    Map<String, Object> metadata =
        Map.of(A2A_DATA_PART_METADATA_TYPE_KEY, A2A_DATA_PART_METADATA_TYPE_FUNCTION_CALL);

    return Optional.of(new DataPart(data, metadata));
  }

  /**
   * Creates an A2A DataPart from a Google GenAI FunctionResponse.
   *
   * @param functionResponse The GenAI FunctionResponse to convert.
   * @return Optional containing the converted A2A Part, or empty if conversion fails.
   */
  private static Optional<DataPart> createDataPartFromFunctionResponse(
      FunctionResponse functionResponse) {
    Map<String, Object> data = new HashMap<>();
    data.put("name", functionResponse.name().orElse(""));
    data.put("response", functionResponse.response().orElse(Map.of()));

    Map<String, Object> metadata =
        Map.of(A2A_DATA_PART_METADATA_TYPE_KEY, A2A_DATA_PART_METADATA_TYPE_FUNCTION_RESPONSE);

    return Optional.of(new DataPart(data, metadata));
  }

  private PartConverter() {}

  /** Convert a GenAI part into the A2A JSON representation. */
  public static Optional<io.a2a.spec.Part<?>> fromGenaiPart(Part part) {
    if (part == null) {
      return Optional.empty();
    }

    if (part.text().isPresent()) {
      return Optional.of(new TextPart(part.text().get()));
    }

    if (part.fileData().isPresent()) {
      FileData fileData = part.fileData().get();
      String uri = fileData.fileUri().orElse(null);
      String mime = fileData.mimeType().orElse(null);
      String name = fileData.displayName().orElse(null);
      return Optional.of(new FilePart(new FileWithUri(mime, name, uri)));
    }

    if (part.inlineData().isPresent()) {
      Blob blob = part.inlineData().get();
      byte[] bytes = blob.data().orElse(null);
      String encoded = bytes != null ? Base64.getEncoder().encodeToString(bytes) : null;
      String mime = blob.mimeType().orElse(null);
      String name = blob.displayName().orElse(null);
      return Optional.of(new FilePart(new FileWithBytes(mime, name, encoded)));
    }

    if (part.functionCall().isPresent() || part.functionResponse().isPresent()) {
      return convertGenaiPartToA2aPart(part).map(data -> data);
    }

    logger.warn("Unsupported GenAI part type for JSON export: {}", part);
    return Optional.empty();
  }

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
        return OBJECT_MAPPER.readValue(str, Map.class);
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
