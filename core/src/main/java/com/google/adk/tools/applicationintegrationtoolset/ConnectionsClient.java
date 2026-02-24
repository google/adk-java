package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.Credentials;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Utility class for interacting with the Google Cloud Connectors API.
 *
 * <p>This class provides methods to fetch connection details, schemas for entities and actions, and
 * to generate OpenAPI specifications for creating tools based on these connections.
 */
public class ConnectionsClient {

  private final String project;
  private final String location;
  private final String connection;
  private static final String CONNECTOR_URL = "https://connectors.googleapis.com";
  private final HttpClient httpClient;
  private final String serviceAccountJson;
  private final CredentialsHelper credentialsHelper;
  private final ObjectMapper objectMapper;

  /** Represents details of a connection. */
  public static class ConnectionDetails {
    public String name;
    public String serviceName;
    public String host;
  }

  /** Represents the schema and available operations for an entity. */
  public static class EntitySchemaAndOperations {
    public Map<String, Object> schema;
    public List<String> operations;
  }

  /** Represents the schema for an action. */
  public static class ActionSchema {
    public Map<String, Object> inputSchema;
    public Map<String, Object> outputSchema;
    public String description;
    public String displayName;
  }

  /**
   * Initializes the ConnectionsClient.
   *
   * @param project The Google Cloud project ID.
   * @param location The Google Cloud location (e.g., us-central1).
   * @param connection The connection name.
   */
  public ConnectionsClient(
      String project,
      String location,
      String connection,
      String serviceAccountJson,
      HttpClient httpClient,
      CredentialsHelper credentialsHelper,
      ObjectMapper objectMapper) {

    this.project = project;
    this.location = location;
    this.connection = connection;
    this.httpClient = Preconditions.checkNotNull(httpClient);
    this.objectMapper = objectMapper;
    this.serviceAccountJson = serviceAccountJson;
    this.credentialsHelper = Preconditions.checkNotNull(credentialsHelper);
  }

  public ConnectionsClient(
      String project,
      String location,
      String connection,
      HttpClient httpClient,
      ObjectMapper objectMapper) {
    this(
        project,
        location,
        connection,
        null,
        httpClient,
        new GoogleCredentialsHelper(),
        objectMapper);
  }

  /**
   * Retrieves service details for a given connection.
   *
   * @return A {@link ConnectionDetails} object with the connection's info.
   * @throws IOException If there is an issue with network communication or credentials.
   * @throws InterruptedException If the thread is interrupted during the API call.
   */
  public ConnectionDetails getConnectionDetails() throws IOException, InterruptedException {
    String url =
        String.format(
            "%s/v1/projects/%s/locations/%s/connections/%s?view=BASIC",
            CONNECTOR_URL, project, location, connection);

    HttpResponse<String> response = executeApiCall(url);
    Map<String, Object> connectionData = parseJson(response.body());

    ConnectionDetails details = new ConnectionDetails();
    details.name = (String) connectionData.getOrDefault("name", "");
    details.serviceName = (String) connectionData.getOrDefault("serviceDirectory", "");
    details.host = (String) connectionData.getOrDefault("host", "");
    if (details.host != null && !details.host.isEmpty()) {
      details.serviceName = (String) connectionData.getOrDefault("tlsServiceDirectory", "");
    }
    return details;
  }

  /**
   * Retrieves the JSON schema and available operations for a given entity.
   *
   * @param entity The entity name.
   * @return A {@link EntitySchemaAndOperations} object.
   * @throws IOException If there is an issue with network communication or credentials.
   * @throws InterruptedException If the thread is interrupted during polling.
   */
  @SuppressWarnings("unchecked")
  public EntitySchemaAndOperations getEntitySchemaAndOperations(String entity)
      throws IOException, InterruptedException {
    String url =
        String.format(
            "%s/v1/projects/%s/locations/%s/connections/%s/connectionSchemaMetadata:getEntityType?entityId=%s",
            CONNECTOR_URL, project, location, connection, entity);

    HttpResponse<String> initialResponse = executeApiCall(url);
    String operationId = (String) parseJson(initialResponse.body()).get("name");

    if (isNullOrEmpty(operationId)) {
      throw new IOException("Failed to get operation ID for entity: " + entity);
    }

    Map<String, Object> operationResponse = pollOperation(operationId);
    Map<String, Object> responseData =
        (Map<String, Object>) operationResponse.getOrDefault("response", ImmutableMap.of());

    Map<String, Object> schema =
        (Map<String, Object>) responseData.getOrDefault("jsonSchema", ImmutableMap.of());
    List<String> operations =
        (List<String>) responseData.getOrDefault("operations", ImmutableList.of());
    EntitySchemaAndOperations entitySchemaAndOperations = new EntitySchemaAndOperations();
    entitySchemaAndOperations.schema = schema;
    entitySchemaAndOperations.operations = operations;
    return entitySchemaAndOperations;
  }

  /**
   * Retrieves the input and output JSON schema for a given action.
   *
   * @param action The action name.
   * @return An {@link ActionSchema} object.
   * @throws IOException If there is an issue with network communication or credentials.
   * @throws InterruptedException If the thread is interrupted during polling.
   */
  @SuppressWarnings("unchecked")
  public ActionSchema getActionSchema(String action) throws IOException, InterruptedException {
    String url =
        String.format(
            "%s/v1/projects/%s/locations/%s/connections/%s/connectionSchemaMetadata:getAction?actionId=%s",
            CONNECTOR_URL, project, location, connection, action);

    HttpResponse<String> initialResponse = executeApiCall(url);
    String operationId = (String) parseJson(initialResponse.body()).get("name");

    if (isNullOrEmpty(operationId)) {
      throw new IOException("Failed to get operation ID for action: " + action);
    }

    Map<String, Object> operationResponse = pollOperation(operationId);
    Map<String, Object> responseData =
        (Map<String, Object>) operationResponse.getOrDefault("response", ImmutableMap.of());

    ActionSchema actionSchema = new ActionSchema();
    actionSchema.inputSchema =
        (Map<String, Object>) responseData.getOrDefault("inputJsonSchema", ImmutableMap.of());
    actionSchema.outputSchema =
        (Map<String, Object>) responseData.getOrDefault("outputJsonSchema", ImmutableMap.of());
    actionSchema.description = (String) responseData.getOrDefault("description", "");
    actionSchema.displayName = (String) responseData.getOrDefault("displayName", "");

    return actionSchema;
  }

  private HttpResponse<String> executeApiCall(String url) throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .GET();

    Credentials credentials = credentialsHelper.getGoogleCredentials(serviceAccountJson);
    requestBuilder = CredentialsHelper.populateHeaders(requestBuilder, credentials);

    HttpResponse<String> response =
        httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() >= 400) {
      String body = response.body();
      if (response.statusCode() == 400 || response.statusCode() == 404) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid request. Please check the provided values of project(%s), location(%s),"
                    + " connection(%s). Error: %s",
                project, location, connection, body));
      }
      if (response.statusCode() == 401 || response.statusCode() == 403) {
        throw new SecurityException(
            String.format("Permission error (status %d): %s", response.statusCode(), body));
      }
      throw new IOException(
          String.format("API call failed with status %d: %s", response.statusCode(), body));
    }
    return response;
  }

  private Map<String, Object> pollOperation(String operationId)
      throws IOException, InterruptedException {
    boolean operationDone = false;
    Map<String, Object> operationResponse = null;

    while (!operationDone) {
      String getOperationUrl = String.format("%s/v1/%s", CONNECTOR_URL, operationId);
      HttpResponse<String> response = executeApiCall(getOperationUrl);
      operationResponse = parseJson(response.body());

      Object doneObj = operationResponse.get("done");
      if (doneObj instanceof Boolean b) {
        operationDone = b;
      }

      if (!operationDone) {
        Thread.sleep(1000);
      }
    }
    return operationResponse;
  }

  /**
   * Converts a JSON Schema dictionary to an OpenAPI schema dictionary.
   *
   * @param jsonSchema The input JSON schema map.
   * @return The converted OpenAPI schema map.
   */
  public Map<String, Object> convertJsonSchemaToOpenApiSchema(Map<String, Object> jsonSchema) {
    Map<String, Object> openapiSchema = new HashMap<>();

    if (jsonSchema.containsKey("description")) {
      openapiSchema.put("description", jsonSchema.get("description"));
    }

    if (jsonSchema.containsKey("type")) {
      Object type = jsonSchema.get("type");
      if (type instanceof List) {
        List<?> typeList = (List<?>) type;
        if (typeList.contains("null")) {
          openapiSchema.put("nullable", true);
          typeList.stream()
              .filter(t -> t instanceof String && !t.equals("null"))
              .findFirst()
              .ifPresent(t -> openapiSchema.put("type", t));
        } else if (!typeList.isEmpty()) {
          openapiSchema.put("type", typeList.get(0));
        }
      } else {
        openapiSchema.put("type", type);
      }
    }
    if (Objects.equals(openapiSchema.get("type"), "object")
        && jsonSchema.containsKey("properties")) {
      @SuppressWarnings("unchecked")
      Map<String, Map<String, Object>> properties =
          (Map<String, Map<String, Object>>) jsonSchema.get("properties");
      Map<String, Object> convertedProperties = new HashMap<>();
      for (Map.Entry<String, Map<String, Object>> entry : properties.entrySet()) {
        convertedProperties.put(entry.getKey(), convertJsonSchemaToOpenApiSchema(entry.getValue()));
      }
      openapiSchema.put("properties", convertedProperties);
    } else if (Objects.equals(openapiSchema.get("type"), "array")
        && jsonSchema.containsKey("items")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> itemsSchema = (Map<String, Object>) jsonSchema.get("items");
      openapiSchema.put("items", convertJsonSchemaToOpenApiSchema(itemsSchema));
    }

    return openapiSchema;
  }

  public Map<String, Object> connectorPayload(Map<String, Object> jsonSchema) {
    return convertJsonSchemaToOpenApiSchema(jsonSchema);
  }

  private Map<String, Object> parseJson(String json) throws IOException {
    return objectMapper.readValue(json, new TypeReference<>() {});
  }

  public static ImmutableMap<String, Object> getConnectorBaseSpec() {
    return ImmutableMap.ofEntries(
        Map.entry("openapi", "3.0.1"),
        Map.entry(
            "info",
            ImmutableMap.of(
                "title", "ExecuteConnection",
                "description", "This tool can execute a query on connection",
                "version", "4")),
        Map.entry(
            "servers",
            ImmutableList.of(ImmutableMap.of("url", "https://integrations.googleapis.com"))),
        Map.entry(
            "security",
            ImmutableList.of(
                ImmutableMap.of(
                    "google_auth",
                    ImmutableList.of("https://www.googleapis.com/auth/cloud-platform")))),
        Map.entry("paths", ImmutableMap.of()),
        Map.entry(
            "components",
            ImmutableMap.ofEntries(
                Map.entry(
                    "schemas",
                    ImmutableMap.ofEntries(
                        Map.entry(
                            "operation",
                            ImmutableMap.of(
                                "type", "string",
                                "default", "LIST_ENTITIES",
                                "description",
                                    "Operation to execute. Possible values are LIST_ENTITIES,"
                                        + " GET_ENTITY, CREATE_ENTITY, UPDATE_ENTITY, DELETE_ENTITY"
                                        + " in case of entities. EXECUTE_ACTION in case of"
                                        + " actions. and EXECUTE_QUERY in case of custom"
                                        + " queries.")),
                        Map.entry(
                            "entityId",
                            ImmutableMap.of("type", "string", "description", "Name of the entity")),
                        Map.entry("connectorInputPayload", ImmutableMap.of("type", "object")),
                        Map.entry(
                            "filterClause",
                            ImmutableMap.of(
                                "type", "string",
                                "default", "",
                                "description", "WHERE clause in SQL query")),
                        Map.entry(
                            "pageSize",
                            ImmutableMap.of(
                                "type", "integer",
                                "default", 50,
                                "description", "Number of entities to return in the response")),
                        Map.entry(
                            "pageToken",
                            ImmutableMap.of(
                                "type", "string",
                                "default", "",
                                "description", "Page token to return the next page of entities")),
                        Map.entry(
                            "connectionName",
                            ImmutableMap.of(
                                "type", "string",
                                "default", "",
                                "description", "Connection resource name to run the query for")),
                        Map.entry(
                            "serviceName",
                            ImmutableMap.of(
                                "type", "string",
                                "default", "",
                                "description", "Service directory for the connection")),
                        Map.entry(
                            "host",
                            ImmutableMap.of(
                                "type", "string",
                                "default", "",
                                "description", "Host name incase of tls service directory")),
                        Map.entry(
                            "entity",
                            ImmutableMap.of(
                                "type", "string",
                                "default", "Issues",
                                "description", "Entity to run the query for")),
                        Map.entry(
                            "action",
                            ImmutableMap.of(
                                "type", "string",
                                "default", "ExecuteCustomQuery",
                                "description", "Action to run the query for")),
                        Map.entry(
                            "query",
                            ImmutableMap.of(
                                "type", "string",
                                "default", "",
                                "description", "Custom Query to execute on the connection")),
                        Map.entry(
                            "timeout",
                            ImmutableMap.of(
                                "type", "integer",
                                "default", 120,
                                "description", "Timeout in seconds for execution of custom query")),
                        Map.entry(
                            "sortByColumns",
                            ImmutableMap.of(
                                "type",
                                "array",
                                "items",
                                ImmutableMap.of("type", "string"),
                                "default",
                                ImmutableList.of(),
                                "description",
                                "Column to sort the results by")),
                        Map.entry("connectorOutputPayload", ImmutableMap.of("type", "object")),
                        Map.entry("nextPageToken", ImmutableMap.of("type", "string")),
                        Map.entry(
                            "execute-connector_Response",
                            ImmutableMap.of(
                                "required", ImmutableList.of("connectorOutputPayload"),
                                "type", "object",
                                "properties",
                                    ImmutableMap.of(
                                        "connectorOutputPayload",
                                        ImmutableMap.of(
                                            "$ref", "#/components/schemas/connectorOutputPayload"),
                                        "nextPageToken",
                                        ImmutableMap.of(
                                            "$ref", "#/components/schemas/nextPageToken")))))),
                Map.entry(
                    "securitySchemes",
                    ImmutableMap.of(
                        "google_auth",
                        ImmutableMap.of(
                            "type",
                            "oauth2",
                            "flows",
                            ImmutableMap.of(
                                "implicit",
                                ImmutableMap.of(
                                    "authorizationUrl",
                                    "https://accounts.google.com/o/oauth2/auth",
                                    "scopes",
                                    ImmutableMap.of(
                                        "https://www.googleapis.com/auth/cloud-platform",
                                        "Auth for google cloud services")))))))));
  }

  public static ImmutableMap<String, Object> getActionOperation(
      String action,
      String operation,
      String actionDisplayName,
      String toolName,
      String toolInstructions) {
    String description = "Use this tool to execute " + action;
    if (Objects.equals(operation, "EXECUTE_QUERY")) {
      description +=
          " Use pageSize = 50 and timeout = 120 until user specifies a different value"
              + " otherwise. If user provides a query in natural language, convert it to SQL query"
              + " and then execute it using the tool.";
    }

    return ImmutableMap.of(
        "post",
        ImmutableMap.ofEntries(
            Map.entry("summary", actionDisplayName),
            Map.entry("description", description + " " + toolInstructions),
            Map.entry("operationId", toolName + "_" + actionDisplayName),
            Map.entry("x-action", action),
            Map.entry("x-operation", operation),
            Map.entry(
                "requestBody",
                ImmutableMap.of(
                    "content",
                    ImmutableMap.of(
                        "application/json",
                        ImmutableMap.of(
                            "schema",
                            ImmutableMap.of(
                                "$ref",
                                String.format(
                                    "#/components/schemas/%s_Request", actionDisplayName)))))),
            Map.entry(
                "responses",
                ImmutableMap.of(
                    "200",
                    ImmutableMap.of(
                        "description",
                        "Success response",
                        "content",
                        ImmutableMap.of(
                            "application/json",
                            ImmutableMap.of(
                                "schema",
                                ImmutableMap.of(
                                    "$ref",
                                    String.format(
                                        "#/components/schemas/%s_Response",
                                        actionDisplayName)))))))));
  }

  public static ImmutableMap<String, Object> listOperation(
      String entity, String schemaAsString, String toolName, String toolInstructions) {
    return ImmutableMap.of(
        "post",
        ImmutableMap.ofEntries(
            Map.entry("summary", "List " + entity),
            Map.entry(
                "description",
                String.format(
                    "Returns the list of %s data. If the page token was available in the response,"
                        + " let users know there are more records available. Ask if the user wants"
                        + " to fetch the next page of results. When passing filter use the"
                        + " following format: `field_name1='value1' AND field_name2='value2'`. %s",
                    entity, toolInstructions)),
            Map.entry("x-operation", "LIST_ENTITIES"),
            Map.entry("x-entity", entity),
            Map.entry("operationId", toolName + "_list_" + entity),
            Map.entry(
                "requestBody",
                ImmutableMap.of(
                    "content",
                    ImmutableMap.of(
                        "application/json",
                        ImmutableMap.of(
                            "schema",
                            ImmutableMap.of(
                                "$ref", "#/components/schemas/list_" + entity + "_Request"))))),
            Map.entry(
                "responses",
                ImmutableMap.of(
                    "200",
                    ImmutableMap.of(
                        "description",
                        "Success response",
                        "content",
                        ImmutableMap.of(
                            "application/json",
                            ImmutableMap.of(
                                "schema",
                                ImmutableMap.of(
                                    "description",
                                    String.format(
                                        "Returns a list of %s of json schema: %s",
                                        entity, schemaAsString),
                                    "$ref",
                                    "#/components/schemas/execute-connector_Response"))))))));
  }

  public static ImmutableMap<String, Object> getOperation(
      String entity, String schemaAsString, String toolName, String toolInstructions) {
    return ImmutableMap.of(
        "post",
        ImmutableMap.ofEntries(
            Map.entry("summary", "Get " + entity),
            Map.entry(
                "description",
                String.format("Returns the details of the %s. %s", entity, toolInstructions)),
            Map.entry("operationId", toolName + "_get_" + entity),
            Map.entry("x-operation", "GET_ENTITY"),
            Map.entry("x-entity", entity),
            Map.entry(
                "requestBody",
                ImmutableMap.of(
                    "content",
                    ImmutableMap.of(
                        "application/json",
                        ImmutableMap.of(
                            "schema",
                            ImmutableMap.of(
                                "$ref", "#/components/schemas/get_" + entity + "_Request"))))),
            Map.entry(
                "responses",
                ImmutableMap.of(
                    "200",
                    ImmutableMap.of(
                        "description",
                        "Success response",
                        "content",
                        ImmutableMap.of(
                            "application/json",
                            ImmutableMap.of(
                                "schema",
                                ImmutableMap.of(
                                    "description",
                                    String.format(
                                        "Returns %s of json schema: %s", entity, schemaAsString),
                                    "$ref",
                                    "#/components/schemas/execute-connector_Response"))))))));
  }

  public static ImmutableMap<String, Object> createOperation(
      String entity, String toolName, String toolInstructions) {
    return ImmutableMap.of(
        "post",
        ImmutableMap.ofEntries(
            Map.entry("summary", "Creates a new " + entity),
            Map.entry(
                "description", String.format("Creates a new %s. %s", entity, toolInstructions)),
            Map.entry("x-operation", "CREATE_ENTITY"),
            Map.entry("x-entity", entity),
            Map.entry("operationId", toolName + "_create_" + entity),
            Map.entry(
                "requestBody",
                ImmutableMap.of(
                    "content",
                    ImmutableMap.of(
                        "application/json",
                        ImmutableMap.of(
                            "schema",
                            ImmutableMap.of(
                                "$ref", "#/components/schemas/create_" + entity + "_Request"))))),
            Map.entry(
                "responses",
                ImmutableMap.of(
                    "200",
                    ImmutableMap.of(
                        "description",
                        "Success response",
                        "content",
                        ImmutableMap.of(
                            "application/json",
                            ImmutableMap.of(
                                "schema",
                                ImmutableMap.of(
                                    "$ref",
                                    "#/components/schemas/execute-connector_Response"))))))));
  }

  public static ImmutableMap<String, Object> updateOperation(
      String entity, String toolName, String toolInstructions) {
    return ImmutableMap.of(
        "post",
        ImmutableMap.ofEntries(
            Map.entry("summary", "Updates the " + entity),
            Map.entry("description", String.format("Updates the %s. %s", entity, toolInstructions)),
            Map.entry("x-operation", "UPDATE_ENTITY"),
            Map.entry("x-entity", entity),
            Map.entry("operationId", toolName + "_update_" + entity),
            Map.entry(
                "requestBody",
                ImmutableMap.of(
                    "content",
                    ImmutableMap.of(
                        "application/json",
                        ImmutableMap.of(
                            "schema",
                            ImmutableMap.of(
                                "$ref", "#/components/schemas/update_" + entity + "_Request"))))),
            Map.entry(
                "responses",
                ImmutableMap.of(
                    "200",
                    ImmutableMap.of(
                        "description",
                        "Success response",
                        "content",
                        ImmutableMap.of(
                            "application/json",
                            ImmutableMap.of(
                                "schema",
                                ImmutableMap.of(
                                    "$ref",
                                    "#/components/schemas/execute-connector_Response"))))))));
  }

  public static ImmutableMap<String, Object> deleteOperation(
      String entity, String toolName, String toolInstructions) {
    return ImmutableMap.of(
        "post",
        ImmutableMap.ofEntries(
            Map.entry("summary", "Delete the " + entity),
            Map.entry("description", String.format("Deletes the %s. %s", entity, toolInstructions)),
            Map.entry("x-operation", "DELETE_ENTITY"),
            Map.entry("x-entity", entity),
            Map.entry("operationId", toolName + "_delete_" + entity),
            Map.entry(
                "requestBody",
                ImmutableMap.of(
                    "content",
                    ImmutableMap.of(
                        "application/json",
                        ImmutableMap.of(
                            "schema",
                            ImmutableMap.of(
                                "$ref", "#/components/schemas/delete_" + entity + "_Request"))))),
            Map.entry(
                "responses",
                ImmutableMap.of(
                    "200",
                    ImmutableMap.of(
                        "description",
                        "Success response",
                        "content",
                        ImmutableMap.of(
                            "application/json",
                            ImmutableMap.of(
                                "schema",
                                ImmutableMap.of(
                                    "$ref",
                                    "#/components/schemas/execute-connector_Response"))))))));
  }

  public static ImmutableMap<String, Object> createOperationRequest(String entity) {
    return ImmutableMap.of(
        "type",
        "object",
        "required",
        ImmutableList.of(
            "connectorInputPayload",
            "operation",
            "connectionName",
            "serviceName",
            "host",
            "entity"),
        "properties",
        ImmutableMap.ofEntries(
            Map.entry(
                "connectorInputPayload",
                ImmutableMap.of("$ref", "#/components/schemas/connectorInputPayload_" + entity)),
            Map.entry("operation", ImmutableMap.of("$ref", "#/components/schemas/operation")),
            Map.entry(
                "connectionName", ImmutableMap.of("$ref", "#/components/schemas/connectionName")),
            Map.entry("serviceName", ImmutableMap.of("$ref", "#/components/schemas/serviceName")),
            Map.entry("host", ImmutableMap.of("$ref", "#/components/schemas/host")),
            Map.entry("entity", ImmutableMap.of("$ref", "#/components/schemas/entity"))));
  }

  public static ImmutableMap<String, Object> updateOperationRequest(String entity) {
    return ImmutableMap.of(
        "type",
        "object",
        "required",
        ImmutableList.of(
            "connectorInputPayload",
            "entityId",
            "operation",
            "connectionName",
            "serviceName",
            "host",
            "entity"),
        "properties",
        ImmutableMap.ofEntries(
            Map.entry(
                "connectorInputPayload",
                ImmutableMap.of("$ref", "#/components/schemas/connectorInputPayload_" + entity)),
            Map.entry("entityId", ImmutableMap.of("$ref", "#/components/schemas/entityId")),
            Map.entry("operation", ImmutableMap.of("$ref", "#/components/schemas/operation")),
            Map.entry(
                "connectionName", ImmutableMap.of("$ref", "#/components/schemas/connectionName")),
            Map.entry("serviceName", ImmutableMap.of("$ref", "#/components/schemas/serviceName")),
            Map.entry("host", ImmutableMap.of("$ref", "#/components/schemas/host")),
            Map.entry("entity", ImmutableMap.of("$ref", "#/components/schemas/entity")),
            Map.entry(
                "filterClause", ImmutableMap.of("$ref", "#/components/schemas/filterClause"))));
  }

  public static ImmutableMap<String, Object> getOperationRequest() {
    return ImmutableMap.of(
        "type",
        "object",
        "required",
        ImmutableList.of(
            "entityId", "operation", "connectionName", "serviceName", "host", "entity"),
        "properties",
        ImmutableMap.ofEntries(
            Map.entry("entityId", ImmutableMap.of("$ref", "#/components/schemas/entityId")),
            Map.entry("operation", ImmutableMap.of("$ref", "#/components/schemas/operation")),
            Map.entry(
                "connectionName", ImmutableMap.of("$ref", "#/components/schemas/connectionName")),
            Map.entry("serviceName", ImmutableMap.of("$ref", "#/components/schemas/serviceName")),
            Map.entry("host", ImmutableMap.of("$ref", "#/components/schemas/host")),
            Map.entry("entity", ImmutableMap.of("$ref", "#/components/schemas/entity"))));
  }

  public static ImmutableMap<String, Object> deleteOperationRequest() {
    return ImmutableMap.of(
        "type",
        "object",
        "required",
        ImmutableList.of(
            "entityId", "operation", "connectionName", "serviceName", "host", "entity"),
        "properties",
        ImmutableMap.ofEntries(
            Map.entry("entityId", ImmutableMap.of("$ref", "#/components/schemas/entityId")),
            Map.entry("operation", ImmutableMap.of("$ref", "#/components/schemas/operation")),
            Map.entry(
                "connectionName", ImmutableMap.of("$ref", "#/components/schemas/connectionName")),
            Map.entry("serviceName", ImmutableMap.of("$ref", "#/components/schemas/serviceName")),
            Map.entry("host", ImmutableMap.of("$ref", "#/components/schemas/host")),
            Map.entry("entity", ImmutableMap.of("$ref", "#/components/schemas/entity")),
            Map.entry(
                "filterClause", ImmutableMap.of("$ref", "#/components/schemas/filterClause"))));
  }

  public static ImmutableMap<String, Object> listOperationRequest() {
    return ImmutableMap.of(
        "type",
        "object",
        "required",
        ImmutableList.of("operation", "connectionName", "serviceName", "host", "entity"),
        "properties",
        ImmutableMap.ofEntries(
            Map.entry("filterClause", ImmutableMap.of("$ref", "#/components/schemas/filterClause")),
            Map.entry("pageSize", ImmutableMap.of("$ref", "#/components/schemas/pageSize")),
            Map.entry("pageToken", ImmutableMap.of("$ref", "#/components/schemas/pageToken")),
            Map.entry("operation", ImmutableMap.of("$ref", "#/components/schemas/operation")),
            Map.entry(
                "connectionName", ImmutableMap.of("$ref", "#/components/schemas/connectionName")),
            Map.entry("serviceName", ImmutableMap.of("$ref", "#/components/schemas/serviceName")),
            Map.entry("host", ImmutableMap.of("$ref", "#/components/schemas/host")),
            Map.entry("entity", ImmutableMap.of("$ref", "#/components/schemas/entity")),
            Map.entry(
                "sortByColumns", ImmutableMap.of("$ref", "#/components/schemas/sortByColumns"))));
  }

  public static ImmutableMap<String, Object> actionRequest(String action) {
    return ImmutableMap.of(
        "type",
        "object",
        "required",
        ImmutableList.of(
            "operation",
            "connectionName",
            "serviceName",
            "host",
            "action",
            "connectorInputPayload"),
        "properties",
        ImmutableMap.ofEntries(
            Map.entry("operation", ImmutableMap.of("$ref", "#/components/schemas/operation")),
            Map.entry(
                "connectionName", ImmutableMap.of("$ref", "#/components/schemas/connectionName")),
            Map.entry("serviceName", ImmutableMap.of("$ref", "#/components/schemas/serviceName")),
            Map.entry("host", ImmutableMap.of("$ref", "#/components/schemas/host")),
            Map.entry("action", ImmutableMap.of("$ref", "#/components/schemas/action")),
            Map.entry(
                "connectorInputPayload",
                ImmutableMap.of("$ref", "#/components/schemas/connectorInputPayload_" + action))));
  }

  public static ImmutableMap<String, Object> actionResponse(String action) {
    return ImmutableMap.of(
        "type",
        "object",
        "properties",
        ImmutableMap.of(
            "connectorOutputPayload",
            ImmutableMap.of("$ref", "#/components/schemas/connectorOutputPayload_" + action)));
  }

  public static ImmutableMap<String, Object> executeCustomQueryRequest() {
    return ImmutableMap.of(
        "type",
        "object",
        "required",
        ImmutableList.of(
            "operation",
            "connectionName",
            "serviceName",
            "host",
            "action",
            "query",
            "timeout",
            "pageSize"),
        "properties",
        ImmutableMap.ofEntries(
            Map.entry("operation", ImmutableMap.of("$ref", "#/components/schemas/operation")),
            Map.entry(
                "connectionName", ImmutableMap.of("$ref", "#/components/schemas/connectionName")),
            Map.entry("serviceName", ImmutableMap.of("$ref", "#/components/schemas/serviceName")),
            Map.entry("host", ImmutableMap.of("$ref", "#/components/schemas/host")),
            Map.entry("action", ImmutableMap.of("$ref", "#/components/schemas/action")),
            Map.entry("query", ImmutableMap.of("$ref", "#/components/schemas/query")),
            Map.entry("timeout", ImmutableMap.of("$ref", "#/components/schemas/timeout")),
            Map.entry("pageSize", ImmutableMap.of("$ref", "#/components/schemas/pageSize"))));
  }
}
