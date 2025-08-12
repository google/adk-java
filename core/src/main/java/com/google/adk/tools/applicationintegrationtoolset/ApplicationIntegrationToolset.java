package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.applicationintegrationtoolset.IntegrationConnectorTool.DefaultHttpExecutor;
import com.google.adk.tools.applicationintegrationtoolset.IntegrationConnectorTool.HttpExecutor;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Application Integration Toolset */
public class ApplicationIntegrationToolset extends BaseToolset {
  String project;
  String location;
  @Nullable String integration;
  @Nullable List<String> triggers;
  @Nullable String connection;
  @Nullable Map<String, List<String>> entityOperations;
  @Nullable List<String> actions;
  String serviceAccountJson;
  @Nullable String toolNamePrefix;
  @Nullable String toolInstructions;
  public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  HttpExecutor httpExecutor;

  /**
   * ApplicationIntegrationToolset generates tools from a given Application Integration resource.
   *
   * <p>Example Usage:
   *
   * <p>integrationTool = new ApplicationIntegrationToolset( project="test-project",
   * location="us-central1", integration="test-integration",
   * triggers=ImmutableList.of("api_trigger/test_trigger", "api_trigger/test_trigger_2",
   * serviceAccountJson="{....}"),connection=null,enitityOperations=null,actions=null,toolNamePrefix="test-integration-tool",toolInstructions="This
   * tool is used to get response from test-integration.");
   *
   * <p>connectionTool = new ApplicationIntegrationToolset( project="test-project",
   * location="us-central1", integration=null, triggers=null, connection="test-connection",
   * entityOperations=ImmutableMap.of("Entity1", ImmutableList.of("LIST", "GET", "UPDATE")),
   * "Entity2", ImmutableList.of()), actions=ImmutableList.of("ExecuteCustomQuery"),
   * serviceAccountJson="{....}", toolNamePrefix="test-tool", toolInstructions="This tool is used to
   * list, get and update issues in Jira.");
   *
   * @param project The GCP project ID.
   * @param location The GCP location of integration.
   * @param integration The integration name.
   * @param triggers(Optional) The list of trigger ids in the integration.
   * @param connection(Optional) The connection name.
   * @param entityOperations(Optional) The entity operations.
   * @param actions(Optional) The actions.
   * @param serviceAccountJson(Optional) The service account configuration as a dictionary. Required
   *     if not using default service credential. Used for fetching the Application Integration or
   *     Integration Connector resource.
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
      String serviceAccountJson,
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
        serviceAccountJson,
        toolNamePrefix,
        toolInstructions,
        new DefaultHttpExecutor().createExecutor(serviceAccountJson));
  }

  ApplicationIntegrationToolset(
      String project,
      String location,
      String integration,
      List<String> triggers,
      String connection,
      Map<String, List<String>> entityOperations,
      List<String> actions,
      String serviceAccountJson,
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
    this.serviceAccountJson = serviceAccountJson;
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

  private List<BaseTool> getAllTools() throws Exception {
    String openApiSchemaString = null;
    List<BaseTool> tools = new ArrayList<>();
    if (!isNullOrEmpty(this.integration)) {
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
              new IntegrationConnectorTool(
                  openApiSchemaString,
                  pathUrl,
                  toolName,
                  toolInstructions,
                  null,
                  null,
                  null,
                  this.serviceAccountJson,
                  this.httpExecutor));
        }
      }
    } else if (!isNullOrEmpty(this.connection)
        && (this.entityOperations != null || this.actions != null)) {
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
        if (!isNullOrEmpty(toolName)) {
          ConnectionsClient connectionsClient =
              new ConnectionsClient(
                  this.project, this.location, this.connection, this.httpExecutor, OBJECT_MAPPER);
          ConnectionsClient.ConnectionDetails connectionDetails =
              connectionsClient.getConnectionDetails();

          tools.add(
              new IntegrationConnectorTool(
                  openApiSchemaString,
                  pathUrl,
                  toolName,
                  "",
                  connectionDetails.name,
                  connectionDetails.serviceName,
                  connectionDetails.host,
                  this.serviceAccountJson,
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

  @Override
  public Flowable<BaseTool> getTools(@Nullable ReadonlyContext readonlyContext) {
    try {
      List<BaseTool> allTools = getAllTools();
      return Flowable.fromIterable(allTools);
    } catch (Exception e) {
      return Flowable.error(e);
    }
  }

  @Override
  public void close() throws Exception {
    // Nothing to close.
  }
}
