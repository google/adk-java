/*
 * Copyright 2025 Google LLC
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

package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.auth.Credentials;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application Integration Tool */
public class IntegrationConnectorTool extends BaseTool {

  private static final Logger logger = LoggerFactory.getLogger(IntegrationConnectorTool.class);

  private final String openApiSpec;
  private final String pathUrl;
  private final String connectionName;
  private final String serviceName;
  private final String host;
  private final String serviceAccountJson;
  private final HttpClient httpClient;
  private final CredentialsHelper credentialsHelper;

  private String entity;
  private String operation;
  private String action;

  private static final ObjectMapper objectMapper = new ObjectMapper();

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
    this(openApiSpec, pathUrl, toolName, toolDescription, null, null, null, serviceAccountJson);
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
        HttpClient.newHttpClient(),
        new GoogleCredentialsHelper());
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
      HttpClient httpClient,
      CredentialsHelper credentialsHelper) {
    super(toolName, toolDescription);
    this.openApiSpec = openApiSpec;
    this.pathUrl = pathUrl;
    this.connectionName = connectionName;
    this.serviceName = serviceName;
    this.host = host;
    this.serviceAccountJson = serviceAccountJson;
    this.httpClient = Preconditions.checkNotNull(httpClient);
    this.credentialsHelper = Preconditions.checkNotNull(credentialsHelper);
  }

  Schema toGeminiSchema(String openApiSchema, String operationId) throws IOException {
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
    } catch (IOException e) {
      logger.error("Failed to get OpenAPI spec", e);
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
          } catch (IOException | InterruptedException e) {
            logger.error("Failed to execute integration", e);
            return ImmutableMap.of("error", e.getMessage());
          }
        });
  }

  private String executeIntegration(Map<String, Object> args)
      throws IOException, InterruptedException {
    String url = String.format("https://integrations.googleapis.com%s", this.pathUrl);
    String jsonRequestBody;
    try {
      jsonRequestBody = objectMapper.writeValueAsString(args);
    } catch (IOException e) {
      throw new IOException("Error converting args to JSON: " + e.getMessage(), e);
    }
    Credentials credentials = credentialsHelper.getGoogleCredentials(this.serviceAccountJson);
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody));

    requestBuilder = CredentialsHelper.populateHeaders(requestBuilder, credentials);

    HttpResponse<String> response =
        httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException(
          "Error executing integration. Status: "
              + response.statusCode()
              + " , Response: "
              + response.body());
    }
    return response.body();
  }

  String getOperationIdFromPathUrl(String openApiSchemaString, String pathUrl) throws IOException {
    JsonNode topLevelNode = objectMapper.readTree(openApiSchemaString);
    JsonNode specNode = topLevelNode.path("openApiSpec");
    if (specNode.isMissingNode() || !specNode.isTextual()) {
      throw new IllegalArgumentException(
          "Failed to get OpenApiSpec, please check the project and region for the integration.");
    }
    JsonNode rootNode = objectMapper.readTree(specNode.asText());
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
    throw new IOException("Could not find operationId for pathUrl: " + pathUrl);
  }

  private String getResolvedRequestSchemaByOperationId(
      String openApiSchemaString, String operationId) throws IOException {
    JsonNode topLevelNode = objectMapper.readTree(openApiSchemaString);
    JsonNode specNode = topLevelNode.path("openApiSpec");
    if (specNode.isMissingNode() || !specNode.isTextual()) {
      throw new IllegalArgumentException(
          "Failed to get OpenApiSpec, please check the project and region for the integration.");
    }
    JsonNode rootNode = objectMapper.readTree(specNode.asText());
    JsonNode operationNode = findOperationNodeById(rootNode, operationId);
    if (operationNode == null) {
      throw new IOException("Could not find operation with operationId: " + operationId);
    }
    JsonNode requestSchemaNode =
        operationNode.path("requestBody").path("content").path("application/json").path("schema");

    if (requestSchemaNode.isMissingNode()) {
      throw new IOException("Could not find request body schema for operationId: " + operationId);
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
    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resolvedSchema);
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
        ObjectNode newObjectNode = objectMapper.createObjectNode();
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
      throws IOException {
    JsonNode topLevelNode = objectMapper.readTree(openApiSchemaString);
    JsonNode specNode = topLevelNode.path("openApiSpec");
    if (specNode.isMissingNode() || !specNode.isTextual()) {
      return "";
    }
    JsonNode rootNode = objectMapper.readTree(specNode.asText());
    JsonNode operationNode = findOperationNodeById(rootNode, operationId);
    if (operationNode == null) {
      return "";
    }
    return operationNode.path("summary").asText();
  }
}
