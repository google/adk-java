package com.google.adk.tools.applicationintegrationtoolset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.applicationintegrationtoolset.ApplicationIntegrationTool.DefaultHttpExecutor;
import com.google.adk.tools.applicationintegrationtoolset.ApplicationIntegrationTool.HttpExecutor;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Application Integration Toolset */
public class ApplicationIntegrationToolset {
  String project;
  String location;
  String integration;
  List<String> triggers;
  private final HttpExecutor httpExecutor;
  public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public ApplicationIntegrationToolset(
      String project, String location, String integration, List<String> triggers) {
    this(project, location, integration, triggers, new DefaultHttpExecutor());
  }

  ApplicationIntegrationToolset(
      String project,
      String location,
      String integration,
      List<String> triggers,
      HttpExecutor httpExecutor) {
    this.project = project;
    this.location = location;
    this.integration = integration;
    this.triggers = triggers;
    this.httpExecutor = httpExecutor;
  }

  String generateOpenApiSpec() throws Exception {
    String url =
        String.format(
            "https://%s-integrations.googleapis.com/v1/projects/%s/locations/%s:generateOpenApiSpec",
            this.location, this.project, this.location);

    String jsonRequestBody =
        OBJECT_MAPPER.writeValueAsString(
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
      throw new Exception("Error fetching OpenAPI spec. Status: " + response.statusCode());
    }
    return response.body();
  }

  String getAccessToken() throws IOException {
    GoogleCredentials credentials =
        GoogleCredentials.getApplicationDefault()
            .createScoped(ImmutableList.of("https://www.googleapis.com/auth/cloud-platform"));
    credentials.refreshIfExpired();
    return credentials.getAccessToken().getTokenValue();
  }

  List<String> getPathUrl(String openApiSchemaString) throws Exception {
    List<String> pathUrls = new ArrayList<>();
    JsonNode topLevelNode = OBJECT_MAPPER.readTree(openApiSchemaString);
    JsonNode specNode = topLevelNode.path("openApiSpec");
    if (specNode.isMissingNode() || !specNode.isTextual()) {
      throw new IllegalArgumentException(
          "API response must contain an 'openApiSpec' key with a string value.");
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

  @Nullable String extractTriggerIdFromPath(String path) {
    String prefix = "triggerId=api_trigger/";
    int startIndex = path.indexOf(prefix);
    if (startIndex == -1) {
      return null;
    }
    return path.substring(startIndex + prefix.length());
  }

  public List<BaseTool> getTools() throws Exception {
    String openApiSchemaString = generateOpenApiSpec();
    List<String> pathUrls = getPathUrl(openApiSchemaString);

    List<BaseTool> tools = new ArrayList<>();
    for (String pathUrl : pathUrls) {
      String toolName = extractTriggerIdFromPath(pathUrl);
      if (toolName != null) {
        tools.add(new ApplicationIntegrationTool(openApiSchemaString, pathUrl, toolName, ""));
      } else {
        System.err.println(
            "Failed  to get tool name , Please check the integration name , trigger id and location"
                + " and project id.");
      }
    }

    return tools;
  }
}
