package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.Credentials;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Utility class for interacting with Google Cloud Application Integration.
 *
 * <p>This class provides methods for retrieving OpenAPI spec for an integration or a connection.
 */
public class IntegrationClient {
  private final String project;
  private final String location;
  private final String integration;
  private final List<String> triggers;
  private final String connection;
  private final Map<String, List<String>> entityOperations;
  private final List<String> actions;
  private final String serviceAccountJson;
  private final HttpClient httpClient;
  private final CredentialsHelper credentialsHelper;

  public static final ObjectMapper objectMapper = new ObjectMapper();

  IntegrationClient(
      String project,
      String location,
      String integration,
      List<String> triggers,
      String connection,
      Map<String, List<String>> entityOperations,
      List<String> actions,
      String serviceAccountJson,
      HttpClient httpClient,
      CredentialsHelper credentialsHelper) {

    this.project = project;
    this.location = location;
    this.integration = integration;
    this.triggers = triggers;
    this.connection = connection;
    this.entityOperations = entityOperations;
    this.actions = actions;
    this.serviceAccountJson = serviceAccountJson;
    this.httpClient = Preconditions.checkNotNull(httpClient);
    this.credentialsHelper = Preconditions.checkNotNull(credentialsHelper);
    if (!isNullOrEmpty(connection)) {
      validate();
    }
  }

  IntegrationClient(
      String project,
      String location,
      String integration,
      List<String> triggers,
      String connection,
      Map<String, List<String>> entityOperations,
      List<String> actions) {
    this(
        project,
        location,
        integration,
        triggers,
        connection,
        entityOperations,
        actions,
        HttpClient.newHttpClient());
  }

  IntegrationClient(
      String project,
      String location,
      String integration,
      List<String> triggers,
      String connection,
      Map<String, List<String>> entityOperations,
      List<String> actions,
      HttpClient httpClient) {
    this(
        project,
        location,
        integration,
        triggers,
        connection,
        entityOperations,
        actions,
        null,
        httpClient,
        new GoogleCredentialsHelper());
  }

  private void validate() {
    // Check if both are null, throw exception

    if (this.entityOperations == null && this.actions == null) {
      throw new IllegalArgumentException(
          "No entity operations or actions provided. Please provide at least one of them.");
    }

    if (this.entityOperations != null) {
      Preconditions.checkArgument(
          !this.entityOperations.isEmpty(), "entityOperations map cannot be empty");
      for (Map.Entry<String, List<String>> entry : this.entityOperations.entrySet()) {
        String key = entry.getKey();
        List<String> value = entry.getValue();
        Preconditions.checkArgument(
            key != null && !key.isEmpty(),
            "Enitity in entityOperations map cannot be null or empty");
        Preconditions.checkArgument(
            value != null, "Operations for entity '%s' cannot be null", key);
        for (String str : value) {
          Preconditions.checkArgument(
              str != null && !str.isEmpty(),
              "Operation for entity '%s' cannot be null or empty",
              key);
        }
      }
    }

    // Validate actions if it's not null
    if (this.actions != null) {
      Preconditions.checkArgument(!this.actions.isEmpty(), "Actions list cannot be empty");
      Preconditions.checkArgument(
          this.actions.stream().allMatch(Objects::nonNull),
          "Actions list cannot contain null values");
      Preconditions.checkArgument(
          this.actions.stream().noneMatch(String::isEmpty),
          "Actions list cannot contain empty strings");
    }
  }

  String generateOpenApiSpec() throws IOException, InterruptedException {
    String url =
        String.format(
            "https://%s-integrations.googleapis.com/v1/projects/%s/locations/%s:generateOpenApiSpec",
            this.location, this.project, this.location);

    String jsonRequestBody =
        objectMapper.writeValueAsString(
            ImmutableMap.of(
                "apiTriggerResources",
                ImmutableList.of(
                    ImmutableMap.of(
                        "integrationResource",
                        this.integration,
                        "triggerId",
                        Arrays.asList(this.triggers))),
                "fileFormat",
                "JSON"));
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRequestBody));

    Credentials credentials = credentialsHelper.getGoogleCredentials(serviceAccountJson);
    requestBuilder = CredentialsHelper.populateHeaders(requestBuilder, credentials);

    HttpResponse<String> response =
        httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("Error fetching OpenAPI spec. Status: " + response.statusCode());
    }
    return response.body();
  }

  @SuppressWarnings("unchecked")
  ObjectNode getOpenApiSpecForConnection(String toolName, String toolInstructions)
      throws IOException, InterruptedException {
    final String integrationName = "ExecuteConnection";

    ConnectionsClient connectionsClient = createConnectionsClient();

    ImmutableMap<String, Object> baseSpecMap = ConnectionsClient.getConnectorBaseSpec();
    ObjectNode connectorSpec = objectMapper.valueToTree(baseSpecMap);

    ObjectNode paths = (ObjectNode) connectorSpec.path("paths");
    ObjectNode schemas = (ObjectNode) connectorSpec.path("components").path("schemas");

    if (this.entityOperations != null) {
      for (Map.Entry<String, List<String>> entry : this.entityOperations.entrySet()) {
        String entity = entry.getKey();
        List<String> operations = entry.getValue();

        ConnectionsClient.EntitySchemaAndOperations schemaInfo;
        try {
          schemaInfo = connectionsClient.getEntitySchemaAndOperations(entity);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Operation was interrupted while getting entity schema", e);
        }

        Map<String, Object> schemaMap = schemaInfo.schema;
        List<String> supportedOperations = schemaInfo.operations;

        if (operations == null || operations.isEmpty()) {
          operations = supportedOperations;
        }

        String jsonSchemaAsString = objectMapper.writeValueAsString(schemaMap);
        String entityLower = entity.toLowerCase(Locale.ROOT);

        schemas.set(
            "connectorInputPayload_" + entityLower,
            objectMapper.valueToTree(connectionsClient.connectorPayload(schemaMap)));

        for (String operation : operations) {
          String operationLower = operation.toLowerCase(Locale.ROOT);
          String path =
              String.format(
                  "/v2/projects/%s/locations/%s/integrations/%s:execute?triggerId=api_trigger/%s#%s_%s",
                  this.project,
                  this.location,
                  integrationName,
                  integrationName,
                  operationLower,
                  entityLower);

          switch (operationLower) {
            case "create":
              paths.set(
                  path,
                  objectMapper.valueToTree(
                      ConnectionsClient.createOperation(entityLower, toolName, toolInstructions)));
              schemas.set(
                  "create_" + entityLower + "_Request",
                  objectMapper.valueToTree(ConnectionsClient.createOperationRequest(entityLower)));
              break;
            case "update":
              paths.set(
                  path,
                  objectMapper.valueToTree(
                      ConnectionsClient.updateOperation(entityLower, toolName, toolInstructions)));
              schemas.set(
                  "update_" + entityLower + "_Request",
                  objectMapper.valueToTree(ConnectionsClient.updateOperationRequest(entityLower)));
              break;
            case "delete":
              paths.set(
                  path,
                  objectMapper.valueToTree(
                      ConnectionsClient.deleteOperation(entityLower, toolName, toolInstructions)));
              schemas.set(
                  "delete_" + entityLower + "_Request",
                  objectMapper.valueToTree(ConnectionsClient.deleteOperationRequest()));
              break;
            case "list":
              paths.set(
                  path,
                  objectMapper.valueToTree(
                      ConnectionsClient.listOperation(
                          entityLower, jsonSchemaAsString, toolName, toolInstructions)));
              schemas.set(
                  "list_" + entityLower + "_Request",
                  objectMapper.valueToTree(ConnectionsClient.listOperationRequest()));
              break;
            case "get":
              paths.set(
                  path,
                  objectMapper.valueToTree(
                      ConnectionsClient.getOperation(
                          entityLower, jsonSchemaAsString, toolName, toolInstructions)));
              schemas.set(
                  "get_" + entityLower + "_Request",
                  objectMapper.valueToTree(ConnectionsClient.getOperationRequest()));
              break;
            default:
              throw new IllegalArgumentException(
                  "Invalid operation: " + operation + " for entity: " + entity);
          }
        }
      }
    } else if (this.actions != null) {
      for (String action : this.actions) {
        ObjectNode actionDetails =
            objectMapper.valueToTree(connectionsClient.getActionSchema(action));

        JsonNode inputSchemaNode = actionDetails.path("inputSchema");
        JsonNode outputSchemaNode = actionDetails.path("outputSchema");

        String actionDisplayName = actionDetails.path("displayName").asText("").replace(" ", "");
        String operation = "EXECUTE_ACTION";

        Map<String, Object> inputSchemaMap = objectMapper.treeToValue(inputSchemaNode, Map.class);
        Map<String, Object> outputSchemaMap = objectMapper.treeToValue(outputSchemaNode, Map.class);

        if (Objects.equals(action, "ExecuteCustomQuery")) {
          schemas.set(
              actionDisplayName + "_Request",
              objectMapper.valueToTree(ConnectionsClient.executeCustomQueryRequest()));
          operation = "EXECUTE_QUERY";
        } else {
          schemas.set(
              actionDisplayName + "_Request",
              objectMapper.valueToTree(ConnectionsClient.actionRequest(actionDisplayName)));
          schemas.set(
              "connectorInputPayload_" + actionDisplayName,
              objectMapper.valueToTree(connectionsClient.connectorPayload(inputSchemaMap)));
        }

        schemas.set(
            "connectorOutputPayload_" + actionDisplayName,
            objectMapper.valueToTree(connectionsClient.connectorPayload(outputSchemaMap)));
        schemas.set(
            actionDisplayName + "_Response",
            objectMapper.valueToTree(ConnectionsClient.actionResponse(actionDisplayName)));

        String path =
            String.format(
                "/v2/projects/%s/locations/%s/integrations/%s:execute?triggerId=api_trigger/%s#%s",
                this.project, this.location, integrationName, integrationName, action);

        paths.set(
            path,
            objectMapper.valueToTree(
                ConnectionsClient.getActionOperation(
                    action, operation, actionDisplayName, toolName, toolInstructions)));
      }
    } else {
      throw new IllegalArgumentException(
          "No entity operations or actions provided. Please provide at least one of them.");
    }
    return connectorSpec;
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

        if (operationNode.has("operationId")) {
          return operationNode.path("operationId").asText();
        }
      }
    }
    throw new IOException("Could not find operationId for pathUrl: " + pathUrl);
  }

  ConnectionsClient createConnectionsClient() {
    return new ConnectionsClient(
        this.project,
        this.location,
        this.connection,
        this.serviceAccountJson,
        this.httpClient,
        this.credentialsHelper,
        objectMapper);
  }
}
