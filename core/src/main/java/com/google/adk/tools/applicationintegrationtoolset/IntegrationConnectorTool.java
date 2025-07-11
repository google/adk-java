package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Application Integration Tool */
public class IntegrationConnectorTool extends BaseTool {

  private final String openApiSpec;
  private final String pathUrl;
  private final HttpExecutor httpExecutor;
  private final String connectionName;
  private final String serviceName;
  private final String host;
  private String entity;
  private String operation;
  private String action;
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  interface HttpExecutor {
    <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException;

    String getToken() throws IOException;

    public HttpExecutor createExecutor(String serviceAccountJson);
  }

  static class DefaultHttpExecutor implements HttpExecutor {
    private final HttpClient client = HttpClient.newHttpClient();
    private final String serviceAccountJson;

    /** Default constructor for when no service account is specified. */
    DefaultHttpExecutor() {
      this(null);
    }

    /**
     * Constructor that accepts an optional service account JSON string.
     *
     * @param serviceAccountJson The service account key as a JSON string, or null.
     */
    DefaultHttpExecutor(@Nullable String serviceAccountJson) {
      this.serviceAccountJson = serviceAccountJson;
    }

    @Override
    public <T> HttpResponse<T> send(
        HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
        throws IOException, InterruptedException {
      return client.send(request, responseBodyHandler);
    }

    @Override
    public String getToken() throws IOException {
      GoogleCredentials credentials;

      if (this.serviceAccountJson != null && !this.serviceAccountJson.trim().isEmpty()) {
        try (InputStream is = new ByteArrayInputStream(this.serviceAccountJson.getBytes(UTF_8))) {
          credentials =
              GoogleCredentials.fromStream(is)
                  .createScoped("https://www.googleapis.com/auth/cloud-platform");
        } catch (IOException e) {
          throw new IOException("Failed to load credentials from service_account_json.", e);
        }
      } else {
        try {
          credentials =
              GoogleCredentials.getApplicationDefault()
                  .createScoped("https://www.googleapis.com/auth/cloud-platform");
        } catch (IOException e) {
          throw new IOException(
              "Please provide a service account or configure Application Default Credentials. To"
                  + " set up ADC, see"
                  + " https://cloud.google.com/docs/authentication/external/set-up-adc.",
              e);
        }
      }

      credentials.refreshIfExpired();
      return credentials.getAccessToken().getTokenValue();
    }

    @Override
    public HttpExecutor createExecutor(String serviceAccountJson) {
      if (isNullOrEmpty(serviceAccountJson)) {
        return new DefaultHttpExecutor();
      } else {
        return new DefaultHttpExecutor(serviceAccountJson);
      }
    }
  }

  private static final ImmutableList<String> EXCLUDE_FIELDS =
      ImmutableList.of("connectionName", "serviceName", "host", "entity", "operation", "action");

  private static final ImmutableList<String> OPTIONAL_FIELDS =
      ImmutableList.of("pageSize", "pageToken", "filter", "sortByColumns");

  /** Constructor for Application Integration Tool for integration */
  IntegrationConnectorTool(
      String openApiSpec,
      String pathUrl,
      String toolName,
      String toolDescription,
      String serviceAccountJson) {
    this(
        openApiSpec,
        pathUrl,
        toolName,
        toolDescription,
        null,
        null,
        null,
        serviceAccountJson,
        new DefaultHttpExecutor().createExecutor(serviceAccountJson));
  }

  /**
   * Constructor for Application Integration Tool with connection name, service name, host, entity,
   * operation, and action
   */
  IntegrationConnectorTool(
      String openApiSpec,
      String pathUrl,
      String toolName,
      String toolDescription,
      String connectionName,
      String serviceName,
      String host,
      String serviceAccountJson) {
    this(
        openApiSpec,
        pathUrl,
        toolName,
        toolDescription,
        connectionName,
        serviceName,
        host,
        serviceAccountJson,
        new DefaultHttpExecutor().createExecutor(serviceAccountJson));
  }

  IntegrationConnectorTool(
      String openApiSpec,
      String pathUrl,
      String toolName,
      String toolDescription,
      @Nullable String connectionName,
      @Nullable String serviceName,
      @Nullable String host,
      @Nullable String serviceAccountJson,
      HttpExecutor httpExecutor) {
    super(toolName, toolDescription);
    this.openApiSpec = openApiSpec;
    this.pathUrl = pathUrl;
    this.connectionName = connectionName;
    this.serviceName = serviceName;
    this.host = host;
    this.httpExecutor = httpExecutor;
  }

  Schema toGeminiSchema(String openApiSchema, String operationId) throws Exception {
    String resolvedSchemaString = getResolvedRequestSchemaByOperationId(openApiSchema, operationId);
    return Schema.fromJson(resolvedSchemaString);
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    try {
      String operationId = getOperationIdFromPathUrl(openApiSpec, pathUrl);
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
    if (this.connectionName != null) {
      args.put("connectionName", this.connectionName);
      args.put("serviceName", this.serviceName);
      args.put("host", this.host);
      if (!isNullOrEmpty(this.entity)) {
        args.put("entity", this.entity);
      } else if (!isNullOrEmpty(this.action)) {
        args.put("action", this.action);
      }
      if (!isNullOrEmpty(this.operation)) {
        args.put("operation", this.operation);
      }
    }

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
    String url = String.format("https://integrations.googleapis.com%s", this.pathUrl);
    String jsonRequestBody;
    try {
      jsonRequestBody = OBJECT_MAPPER.writeValueAsString(args);
    } catch (IOException e) {
      throw new Exception("Error converting args to JSON: " + e.getMessage(), e);
    }
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + httpExecutor.getToken())
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

  String getOperationIdFromPathUrl(String openApiSchemaString, String pathUrl) throws Exception {
    JsonNode topLevelNode = OBJECT_MAPPER.readTree(openApiSchemaString);
    JsonNode specNode = topLevelNode.path("openApiSpec");
    if (specNode.isMissingNode() || !specNode.isTextual()) {
      throw new IllegalArgumentException(
          "Failed to get OpenApiSpec, please check the project and region for the integration.");
    }
    JsonNode rootNode = OBJECT_MAPPER.readTree(specNode.asText());
    JsonNode paths = rootNode.path("paths");

    // Iterate through each path in the OpenAPI spec.
    Iterator<Map.Entry<String, JsonNode>> pathsFields = paths.fields();
    while (pathsFields.hasNext()) {
      Map.Entry<String, JsonNode> pathEntry = pathsFields.next();
      String currentPath = pathEntry.getKey();
      if (!currentPath.equals(pathUrl)) {
        continue;
      }
      JsonNode pathItem = pathEntry.getValue();

      Iterator<Map.Entry<String, JsonNode>> methods = pathItem.fields();
      while (methods.hasNext()) {
        Map.Entry<String, JsonNode> methodEntry = methods.next();
        JsonNode operationNode = methodEntry.getValue();
        // Set  values for entity, operation, and action
        this.entity = "";
        this.operation = "";
        this.action = "";
        if (operationNode.has("x-entity")) {
          this.entity = operationNode.path("x-entity").asText();
        } else if (operationNode.has("x-action")) {
          this.action = operationNode.path("x-action").asText();
        }
        if (operationNode.has("x-operation")) {
          this.operation = operationNode.path("x-operation").asText();
        }
        // Get the operationId from the operationNode
        if (operationNode.has("operationId")) {
          return operationNode.path("operationId").asText();
        }
      }
    }
    throw new Exception("Could not find operationId for pathUrl: " + pathUrl);
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

    if (resolvedSchema.isObject()) {
      ObjectNode schemaObject = (ObjectNode) resolvedSchema;

      // 1. Remove excluded fields from the 'properties' object.
      JsonNode propertiesNode = schemaObject.path("properties");
      if (propertiesNode.isObject()) {
        ObjectNode propertiesObject = (ObjectNode) propertiesNode;
        for (String field : EXCLUDE_FIELDS) {
          propertiesObject.remove(field);
        }
      }

      // 2. Remove optional and excluded fields from the 'required' array.
      JsonNode requiredNode = schemaObject.path("required");
      if (requiredNode.isArray()) {
        // Combine the lists of fields to remove
        List<String> fieldsToRemove =
            Streams.concat(OPTIONAL_FIELDS.stream(), EXCLUDE_FIELDS.stream()).toList();

        // To safely remove items from a list while iterating, we must use an Iterator.
        ArrayNode requiredArray = (ArrayNode) requiredNode;
        Iterator<JsonNode> elements = requiredArray.elements();
        while (elements.hasNext()) {
          JsonNode element = elements.next();
          if (element.isTextual() && fieldsToRemove.contains(element.asText())) {
            // This removes the current element from the underlying array.
            elements.remove();
          }
        }
      }
    }
    return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(resolvedSchema);
  }

  private @Nullable JsonNode findOperationNodeById(JsonNode rootNode, String operationId) {
    JsonNode paths = rootNode.path("paths");
    for (JsonNode pathItem : paths) {
      Iterator<Map.Entry<String, JsonNode>> methods = pathItem.fields();
      while (methods.hasNext()) {
        Map.Entry<String, JsonNode> methodEntry = methods.next();
        JsonNode operationNode = methodEntry.getValue();
        if (operationNode.path("operationId").asText().equals(operationId)) {
          return operationNode;
        }
      }
    }
    return null;
  }

  private JsonNode resolveRefs(JsonNode currentNode, JsonNode rootNode) {
    if (currentNode.isObject()) {
      ObjectNode objectNode = (ObjectNode) currentNode;
      if (objectNode.has("$ref")) {
        String refPath = objectNode.get("$ref").asText();
        if (refPath.isEmpty() || !refPath.startsWith("#/")) {
          return objectNode;
        }
        JsonNode referencedNode = rootNode.at(refPath.substring(1));
        if (referencedNode.isMissingNode()) {
          return objectNode;
        }
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
