package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParseException;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.applicationintegrationtoolset.ApplicationIntegrationTool.HttpExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class ApplicationIntegrationToolsetTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();
  @Mock private HttpExecutor mockHttpExecutor;
  @Mock private HttpResponse<String> mockHttpResponse;

  private static final String LOCATION = "us-central1";
  private static final String PROJECT = "test-project";
  private static final String MOCK_ACCESS_TOKEN = "test-token";
  private static final String CONNECTION =
      "projects/test-project/locations/us-central1/connections/test-conn";

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void getTools_forIntegration_success() throws Exception {
    ApplicationIntegrationToolset toolset =
        new ApplicationIntegrationToolset(
            PROJECT,
            LOCATION,
            "test-integration",
            ImmutableList.of("api_trigger/trigger-1"),
            null,
            null,
            null,
            null,
            null,
            mockHttpExecutor);

    String mockOpenApiSpecJson =
        "{\"openApiSpec\":"
            + "\"{\\\"paths\\\":{\\\"/p1?triggerId=api_trigger/trigger-1\\\":{\\\"post\\\":{\\\"operationId\\\":\\\"trigger-1\\\"}}},"
            + "\\\"components\\\":{\\\"schemas\\\":{}}}\"}";
    when(mockHttpExecutor.getToken()).thenReturn(MOCK_ACCESS_TOKEN);
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(mockOpenApiSpecJson);
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(HttpRequest.class), any());

    List<BaseTool> tools = toolset.getTools();

    assertThat(tools).hasSize(1);
    assertThat(tools.get(0).name()).isEqualTo("trigger-1");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void getTools_forConnection_success() throws Exception {
    ApplicationIntegrationToolset toolset =
        new ApplicationIntegrationToolset(
            PROJECT,
            LOCATION,
            null,
            null,
            CONNECTION,
            ImmutableMap.of("Issue", ImmutableList.of("GET")),
            null,
            "Jira",
            "Tools for Jira",
            mockHttpExecutor);

    String mockConnectionDetailsJson =
        "{\"name\":\""
            + CONNECTION
            + "\", \"serviceName\":\"jira.example.com\", \"host\":\"1.2.3.4\"}";
    String mockEntitySchemaJson =
        "{\"name\": \"op1\", \"done\": true, \"response\": {\"jsonSchema\": {}, \"operations\":"
            + " [\"GET\"]}}";
    when(mockHttpExecutor.getToken()).thenReturn(MOCK_ACCESS_TOKEN);
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body())
        .thenReturn(mockConnectionDetailsJson)
        .thenReturn(mockEntitySchemaJson);
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(HttpRequest.class), any());

    List<BaseTool> tools = toolset.getTools();

    assertThat(tools).hasSize(1);
    assertThat(tools.get(0).name()).isEqualTo("Jira_get_issue");
  }

  @Test
  public void getTools_invalidArguments_throwsException() {
    ApplicationIntegrationToolset toolset =
        new ApplicationIntegrationToolset(
            PROJECT, LOCATION, null, null, CONNECTION, null, null, null, null, mockHttpExecutor);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, toolset::getTools);
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Invalid request, Either integration or (connection and"
                + " (entityOperations or actions)) should be provided.");
  }

  @Test
  public void getTools_forConnection_noEntityOperationsOrActions_throwsException()
      throws Exception {
    ApplicationIntegrationToolset toolset =
        new ApplicationIntegrationToolset(
            PROJECT, LOCATION, null, null, null, null, null, null, null, mockHttpExecutor);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, toolset::getTools);
    assertThat(e)
        .hasMessageThat()
        .contains(
            "Invalid request, Either integration or (connection and"
                + " (entityOperations or actions)) should be provided.");
  }

  @Test
  public void getPathUrl_success() throws Exception {
    String openApiSpec =
        "{\"openApiSpec\": \"{\\\"paths\\\":{\\\"/path1\\\":{},\\\"/path2\\\":{}}}\"}";
    ApplicationIntegrationToolset toolset =
        new ApplicationIntegrationToolset(null, null, null, null, null, null, null, null, null);
    List<String> paths = toolset.getPathUrl(openApiSpec);
    assertThat(paths).containsExactly("/path1", "/path2").inOrder();
  }

  @Test
  public void getPathUrl_invalidJson_throwsException() {
    String openApiSpec = "{\"openApiSpec\": \"invalid json\"}";
    ApplicationIntegrationToolset toolset =
        new ApplicationIntegrationToolset(null, null, null, null, null, null, null, null, null);
    assertThrows(JsonParseException.class, () -> toolset.getPathUrl(openApiSpec));
  }
}
