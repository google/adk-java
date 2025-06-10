package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParseException;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.applicationintegrationtoolset.ApplicationIntegrationTool.HttpExecutor;
import com.google.common.collect.ImmutableList;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.Before;
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
  @Mock private HttpExecutor mockHttpExecutor; // Mock our own interface
  @Mock private HttpResponse<String> mockHttpResponse; // Mock the response

  private static final String LOCATION = "us-central1";
  private static final String PROJECT = "test-project";
  private static final String INTEGRATION = "test-integration";
  private static final ImmutableList<String> TRIGGERS = ImmutableList.of("trigger-1");
  private static final String MOCK_ACCESS_TOKEN = "test-token";
  private ApplicationIntegrationToolset toolset;

  @Before
  public void setUp() {
    toolset =
        new ApplicationIntegrationToolset(
            PROJECT, LOCATION, INTEGRATION, TRIGGERS, mockHttpExecutor);
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void generateOpenApiSpec_success() throws Exception {
    String mockResponseJson = "{\"openApiSpec\": \"{\\\"paths\\\":{}}\"}";

    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(mockResponseJson);
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(HttpRequest.class), any());

    ApplicationIntegrationToolset spyToolset = spy(toolset);
    doReturn(MOCK_ACCESS_TOKEN).when(spyToolset).getAccessToken();

    String result = spyToolset.generateOpenApiSpec();

    assertThat(result).isEqualTo(mockResponseJson);
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void generateOpenApiSpec_error() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(404);
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(HttpRequest.class), any());

    ApplicationIntegrationToolset spyToolset = spy(toolset);
    doReturn(MOCK_ACCESS_TOKEN).when(spyToolset).getAccessToken();

    Exception exception = assertThrows(Exception.class, spyToolset::generateOpenApiSpec);
    assertThat(exception).hasMessageThat().isEqualTo("Error fetching OpenAPI spec. Status: " + 404);
  }

  @Test
  public void extractTriggerIdFromPath_success() {
    String path =
        "/v1/projects/test-project/locations/us-central1/integrations/test-integration/trigger/triggerId=api_trigger/trigger-id";

    String triggerId = toolset.extractTriggerIdFromPath(path);

    assertThat(triggerId).isEqualTo("trigger-id");
  }

  @Test
  public void extractTriggerIdFromPath_noTriggerId() {
    String path = "/v1/projects/test-project/locations/us-central1/integrations/test-integration";

    String triggerId = toolset.extractTriggerIdFromPath(path);

    assertThat(triggerId).isNull();
  }

  @Test
  public void getPathUrl_success() throws Exception {
    String openApiSpec =
        "{\"openApiSpec\": \"{\\\"paths\\\":{\\\"/path1\\\":{},\\\"/path2\\\":{}}}\"}";

    List<String> paths = toolset.getPathUrl(openApiSpec);

    assertThat(paths).containsExactly("/path1", "/path2");
  }

  @Test
  public void getPathUrl_invalidJson() throws Exception {
    String openApiSpec = "{\"openApiSpec\": \"invalid json\"}";

    assertThrows(JsonParseException.class, () -> toolset.getPathUrl(openApiSpec));
  }

  @Test
  public void getTools_success() throws Exception {
    String mockOpenApiSpec =
        "{\"openApiSpec\":"
            + "\"{\\\"paths\\\":{\\\"/p1/triggerId=api_trigger/trigger-1\\\":{},\\\"/p2/triggerId=api_trigger/trigger-2\\\":{}}}\"}";

    ApplicationIntegrationToolset spyToolset = spy(toolset);

    doReturn(mockOpenApiSpec).when(spyToolset).generateOpenApiSpec();

    List<BaseTool> tools = spyToolset.getTools();

    assertThat(tools).hasSize(2);
    assertThat(tools.get(0).name()).isEqualTo("trigger-1");
    assertThat(tools.get(1).name()).isEqualTo("trigger-2");
  }
}
