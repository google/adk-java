package com.google.adk.models.azure;

import com.google.adk.models.LlmRequest;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Schema;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared request conversion utilities for all Azure transports.
 *
 * <p>Consolidates duplicated logic that was previously in both {@code AzureBaseLM} and {@code
 * AzureRealtimeLlmConnection}: instruction extraction, tool schema conversion, and schema-to-JSON
 * mapping.
 */
public final class AzureRequestConverter {

  private static final Logger logger = LoggerFactory.getLogger(AzureRequestConverter.class);

  private static final String FORBIDDEN_CHARACTERS_REGEX = "[^a-zA-Z0-9_\\.-]";

  private AzureRequestConverter() {}

  /**
   * Extracts system instructions from the LlmRequest config.
   *
   * @return combined system instruction text, or empty string if none
   */
  public static String extractInstructions(LlmRequest llmRequest) {
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

  /**
   * Builds a JSON array of tool definitions from the LlmRequest tools map.
   *
   * <p>Uses {@code llmRequest.tools()} (Map of BaseTool) as the single source of truth for all
   * transports. Output format matches Azure/OpenAI function tool schema.
   *
   * @return JSONArray of tool objects, may be empty
   */
  public static JSONArray buildTools(LlmRequest llmRequest) {
    JSONArray tools = new JSONArray();

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
              if (decl.name().isEmpty() || decl.name().get().isBlank()) {
                logger.warn("Skipping function declaration without a name");
                return;
              }

              JSONObject toolObj = new JSONObject();
              toolObj.put("type", "function");
              toolObj.put("name", cleanForIdentifier(decl.name().get()));
              toolObj.put("description", decl.description().orElse(""));
              toolObj.put(
                  "parameters",
                  decl.parameters()
                      .map(AzureRequestConverter::schemaToJson)
                      .orElseGet(
                          () ->
                              new JSONObject()
                                  .put("type", "object")
                                  .put("properties", new JSONObject())));

              tools.put(toolObj);
            });

    return tools;
  }

  /**
   * Recursively converts a {@link Schema} to a JSON object suitable for the OpenAI/Azure tool
   * parameter format.
   */
  public static JSONObject schemaToJson(Schema schema) {
    JSONObject obj = new JSONObject();
    schema
        .type()
        .ifPresent(type -> obj.put("type", type.knownEnum().name().toLowerCase(Locale.ROOT)));
    schema.description().ifPresent(desc -> obj.put("description", desc));

    schema
        .properties()
        .ifPresent(
            props -> {
              JSONObject propsObj = new JSONObject();
              for (Map.Entry<String, Schema> entry : props.entrySet()) {
                propsObj.put(entry.getKey(), schemaToJson(entry.getValue()));
              }
              obj.put("properties", propsObj);
            });

    schema.required().ifPresent(req -> obj.put("required", new JSONArray(req)));
    schema.items().ifPresent(items -> obj.put("items", schemaToJson(items)));

    schema
        .enum_()
        .ifPresent(
            enums -> {
              JSONArray enumArr = new JSONArray();
              for (String e : enums) {
                enumArr.put(e);
              }
              obj.put("enum", enumArr);
            });

    return obj;
  }

  /**
   * Sanitizes a string for use as a function/tool identifier by removing forbidden characters.
   * Allows: {@code [a-zA-Z0-9_.-]}
   */
  public static String cleanForIdentifier(String input) {
    if (input == null) {
      return null;
    }
    return input.replaceAll(FORBIDDEN_CHARACTERS_REGEX, "");
  }
}
