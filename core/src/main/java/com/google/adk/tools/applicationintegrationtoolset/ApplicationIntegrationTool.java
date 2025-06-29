package com.google.adk.tools.applicationintegrationtoolset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Application Integration Toolset */
public class ApplicationIntegrationTool extends BaseTool {

  private String openApiSpec;
  private String pathUrl;
  private String toolName;
  private String toolDescription;
  private final HttpExecutor httpExecutor;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /**
   * An interface that describes the contract for sending HTTP requests. It can be public or
   * package-private. Public is fine if it might be useful elsewhere.
   */
  interface HttpExecutor {
    <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException;
  }

  static class DefaultHttpExecutor implements HttpExecutor {
    private final HttpClient client = HttpClient.newHttpClient();

    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException {
      return client.send(request, responseBodyHandler);
    }
  }

  /** Public constructor for users. */
  public ApplicationIntegrationTool(
      String openApiSpec, String pathUrl, String toolName, String toolDescription) {
    // Chain to the internal constructor, providing real dependencies.
    this(openApiSpec, pathUrl, toolName, toolDescription, new DefaultHttpExecutor());
  }

  ApplicationIntegrationTool(
      String openApiSpec,
      String pathUrl,
      String toolName,
      String toolDescription,
      HttpExecutor httpExecutor) {
    super(toolName, toolDescription);
    this.openApiSpec = openApiSpec;
    this.pathUrl = pathUrl;
    this.toolName = toolName;
    this.toolDescription = toolDescription;
    this.httpExecutor = httpExecutor;
  }

  Schema toGeminiSchema(String openApiSchema, String operationId) throws Exception {
    String resolvedSchemaString = getResolvedRequestSchemaByOperationId(openApiSchema, operationId);
    return Schema.fromJson(resolvedSchemaString);
  }

  @Nullable String extractTriggerIdFromPath(String path) {
    String prefix = "triggerId=api_trigger/";
    int startIndex = path.indexOf(prefix);
    if (startIndex == -1) {
      return null;
    }
    return path.substring(startIndex + prefix.length());
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    try {
      String operationId = extractTriggerIdFromPath(pathUrl);
      Schema parametersSchema = toGeminiSchema(openApiSpec, operationId);
      String operationDescription = getOperationDescription(openApiSpec, operationId);

      FunctionDeclaration declaration =
          FunctionDeclaration.builder()
              .name(operationId)
              .description(operationDescription)
              .parameters(parametersSchema)
              .build();
      return Optional.of(declaration);
    } catch (Exception e) {
      System.err.println("Failed to get OpenAPI spec: " + e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    return Single.fromCallable(
        () -> {
          try {
            String response = executeIntegration(args);
            return ImmutableMap.of("result", response);
          } catch (Exception e) {
            System.err.println("Failed to execute integration: " + e.getMessage());
            return ImmutableMap.of("error", e.getMessage());
          }
        });
  }

  private String executeIntegration(Map<String, Object> args) throws Exception {
    String url = String.format("https://integrations.googleapis.com%s", pathUrl);
    String jsonRequestBody;
    try {
      jsonRequestBody = OBJECT_MAPPER.writeValueAsString(args);
    } catch (IOException e) {
      throw new Exception("Error converting args to JSON: " + e.getMessage(), e);
    }
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + getAccessToken())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody))
            .build();
    HttpResponse<String> response =
        httpExecutor.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new Exception(
          "Error executing integration. Status: "
              + response.statusCode()
              + " , Response: "
              + response.body());
    }
    return response.body();
  }

  String getAccessToken() throws IOException {
    GoogleCredentials credentials =
        GoogleCredentials.getApplicationDefault()
            .createScoped("https://www.googleapis.com/auth/cloud-platform");
    credentials.refreshIfExpired();
    return credentials.getAccessToken().getTokenValue();
  }

  private String getResolvedRequestSchemaByOperationId(
      String openApiSchemaString, String operationId) throws Exception {
    JsonNode topLevelNode = OBJECT_MAPPER.readTree(openApiSchemaString);
    JsonNode specNode = topLevelNode.path("openApiSpec");
    if (specNode.isMissingNode() || !specNode.isTextual()) {
      throw new IllegalArgumentException(
          "Failed to get OpenApiSpec, please check the project and region for the integration.");
    }
    JsonNode rootNode = OBJECT_MAPPER.readTree(specNode.asText());

    JsonNode operationNode = findOperationNodeById(rootNode, operationId);
    if (operationNode == null) {
      throw new Exception("Could not find operation with operationId: " + operationId);
    }
    JsonNode requestSchemaNode =
        operationNode.path("requestBody").path("content").path("application/json").path("schema");

    if (requestSchemaNode.isMissingNode()) {
      throw new Exception("Could not find request body schema for operationId: " + operationId);
    }

    JsonNode resolvedSchema = resolveRefs(requestSchemaNode, rootNode);

    return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(resolvedSchema);
  }

  private @Nullable JsonNode findOperationNodeById(JsonNode rootNode, String operationId) {
    JsonNode paths = rootNode.path("paths");
    // Iterate through each path in the OpenAPI spec.
    for (JsonNode pathItem : paths) {
      // Iterate through each HTTP method (e.g., GET, POST) for the current path.
      Iterator<Map.Entry<String, JsonNode>> methods = pathItem.fields();
      while (methods.hasNext()) {
        Map.Entry<String, JsonNode> methodEntry = methods.next();
        JsonNode operationNode = methodEntry.getValue();
        // Check if the operationId matches the target operationId.
        if (operationNode.path("operationId").asText().equals(operationId)) {
          return operationNode;
        }
      }
    }
    return null;
  }

  // Resolves references to other nodes in the OpenAPI spec.
  private JsonNode resolveRefs(JsonNode currentNode, JsonNode rootNode) {
    if (currentNode.isObject()) {
      ObjectNode objectNode = (ObjectNode) currentNode;
      if (objectNode.has("$ref")) {
        String refPath = objectNode.get("$ref").asText();
        JsonNode referencedNode = rootNode.at(refPath.substring(1));
        return resolveRefs(referencedNode, rootNode);
      } else {
        ObjectNode newObjectNode = OBJECT_MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = currentNode.fields();
        while (fields.hasNext()) {
          Map.Entry<String, JsonNode> field = fields.next();
          newObjectNode.set(field.getKey(), resolveRefs(field.getValue(), rootNode));
        }
        return newObjectNode;
      }
    } else if (currentNode.isArray()) {
      ArrayNode newArrayNode = OBJECT_MAPPER.createArrayNode();
      for (JsonNode element : currentNode) {
        newArrayNode.add(resolveRefs(element, rootNode));
      }
      return newArrayNode;
    }
    return currentNode;
  }

  private String getOperationDescription(String openApiSchemaString, String operationId)
      throws Exception {
    JsonNode topLevelNode = OBJECT_MAPPER.readTree(openApiSchemaString);
    JsonNode specNode = topLevelNode.path("openApiSpec");
    if (specNode.isMissingNode() || !specNode.isTextual()) {
      return "";
    }
    JsonNode rootNode = OBJECT_MAPPER.readTree(specNode.asText());
    JsonNode operationNode = findOperationNodeById(rootNode, operationId);
    if (operationNode == null) {
      return "";
    }
    return operationNode.path("summary").asText();
  }
}
