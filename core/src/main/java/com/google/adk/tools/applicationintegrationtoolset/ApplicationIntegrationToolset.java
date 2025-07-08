package com.google.adk.tools.applicationintegrationtoolset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.applicationintegrationtoolset.ApplicationIntegrationTool.DefaultHttpExecutor;
import com.google.adk.tools.applicationintegrationtoolset.ApplicationIntegrationTool.HttpExecutor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Application Integration Toolset */
public class ApplicationIntegrationToolset {
  String project;
  String location;
  String integration;
  List<String> triggers;
  String connection;
  Map<String, List<String>> entityOperations;
  List<String> actions;
  String toolNamePrefix;
  String toolInstructions;
  public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  HttpExecutor httpExecutor;

  /**
   * ApplicationIntegrationToolset generates tools from a given Application Integration resource.
   *
   * <p>Example Usage:
   *
   * <p>integrationTool = new ApplicationIntegrationToolset( project="test-project",
   * location="us-central1", integration="test-integration",
   * triggers=ImmutableList.of("api_trigger/test_trigger", "api_trigger/test_trigger_2"));
   *
   * <p>connectionTool = new ApplicationIntegrationToolset( project="test-project",
   * location="us-central1", connection="test-connection",
   * entityOperations=ImmutableMap.of("Entity1", ImmutableList.of("LIST", "GET", "UPDATE")),
   * "Entity2", ImmutableList.of()), actions=ImmutableList.of("ExecuteCustomQuery"),
   * toolNamePrefix="test-tool", toolInstructions="This tool is used to list, get and update issues
   * in Jira.");
   *
   * @param project The GCP project ID.
   * @param location The GCP location of integration.
   * @param integration The integration name.
   * @param triggers(Optional) The list of trigger ids in the integration.
   * @param connection(Optional) The connection name.
   * @param entityOperations(Optional) The entity operations.
   * @param actions(Optional) The actions.
   * @param toolNamePrefix(Optional) The tool name prefix.
   * @param toolInstructions(Optional) The tool instructions.
   */
  public ApplicationIntegrationToolset(
      String project,
      String location,
      String integration,
      List<String> triggers,
      String connection,
      Map<String, List<String>> entityOperations,
      List<String> actions,
      String toolNamePrefix,
      String toolInstructions) {
    this(
        project,
        location,
        integration,
        triggers,
        connection,
        entityOperations,
        actions,
        toolNamePrefix,
        toolInstructions,
        new DefaultHttpExecutor());
  }

  ApplicationIntegrationToolset(
      String project,
      String location,
      String integration,
      List<String> triggers,
      String connection,
      Map<String, List<String>> entityOperations,
      List<String> actions,
      String toolNamePrefix,
      String toolInstructions,
      HttpExecutor httpExecutor) {
    this.project = project;
    this.location = location;
    this.integration = integration;
    this.triggers = triggers;
    this.connection = connection;
    this.entityOperations = entityOperations;
    this.actions = actions;
    this.toolNamePrefix = toolNamePrefix;
    this.toolInstructions = toolInstructions;
    this.httpExecutor = httpExecutor;
  }

  List<String> getPathUrl(String openApiSchemaString) throws Exception {
    List<String> pathUrls = new ArrayList<>();
    JsonNode topLevelNode = OBJECT_MAPPER.readTree(openApiSchemaString);
    JsonNode specNode = topLevelNode.path("openApiSpec");
    if (specNode.isMissingNode() || !specNode.isTextual()) {
      throw new IllegalArgumentException(
          "Failed to get OpenApiSpec, please check the project and region for the integration.");
    }
    JsonNode rootNode = OBJECT_MAPPER.readTree(specNode.asText());
    JsonNode pathsNode = rootNode.path("paths");
    Iterator<Map.Entry<String, JsonNode>> paths = pathsNode.fields();
    while (paths.hasNext()) {
      Map.Entry<String, JsonNode> pathEntry = paths.next();
      String pathUrl = pathEntry.getKey();
      pathUrls.add(pathUrl);
    }
    return pathUrls;
  }

  public List<BaseTool> getTools() throws Exception {
    String openApiSchemaString = null;
    List<BaseTool> tools = new ArrayList<>();
    if (this.integration != null) {
      IntegrationClient integrationClient =
          new IntegrationClient(
              this.project,
              this.location,
              this.integration,
              this.triggers,
              null,
              null,
              null,
              this.httpExecutor);
      openApiSchemaString = integrationClient.generateOpenApiSpec();
      List<String> pathUrls = getPathUrl(openApiSchemaString);
      for (String pathUrl : pathUrls) {
        String toolName = integrationClient.getOperationIdFromPathUrl(openApiSchemaString, pathUrl);
        if (toolName != null) {
          tools.add(
              new ApplicationIntegrationTool(
                  openApiSchemaString, pathUrl, toolName, "", null, null, null, this.httpExecutor));
        }
      }
    } else if (this.connection != null && (this.entityOperations != null || this.actions != null)) {
      IntegrationClient integrationClient =
          new IntegrationClient(
              this.project,
              this.location,
              null,
              null,
              this.connection,
              this.entityOperations,
              this.actions,
              this.httpExecutor);
      ObjectNode parentOpenApiSpec = OBJECT_MAPPER.createObjectNode();
      ObjectNode openApiSpec =
          integrationClient.getOpenApiSpecForConnection(toolNamePrefix, toolInstructions);
      String openApiSpecString = OBJECT_MAPPER.writeValueAsString(openApiSpec);
      parentOpenApiSpec.put("openApiSpec", openApiSpecString);
      openApiSchemaString = OBJECT_MAPPER.writeValueAsString(parentOpenApiSpec);
      List<String> pathUrls = getPathUrl(openApiSchemaString);
      for (String pathUrl : pathUrls) {
        String toolName = integrationClient.getOperationIdFromPathUrl(openApiSchemaString, pathUrl);
        if (toolName != null) {
          ConnectionsClient connectionsClient =
              new ConnectionsClient(
                  this.project, this.location, this.connection, this.httpExecutor, OBJECT_MAPPER);
          ConnectionsClient.ConnectionDetails connectionDetails =
              connectionsClient.getConnectionDetails();

          tools.add(
              new ApplicationIntegrationTool(
                  openApiSchemaString,
                  pathUrl,
                  toolName,
                  "",
                  connectionDetails.name,
                  connectionDetails.serviceName,
                  connectionDetails.host,
                  this.httpExecutor));
        }
      }
    } else {
      throw new IllegalArgumentException(
          "Invalid request, Either integration or (connection and"
              + " (entityOperations or actions)) should be provided.");
    }

    return tools;
  }
}
