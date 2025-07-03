package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.adk.tools.ToolContext;
import com.google.adk.tools.applicationintegrationtoolset.ApplicationIntegrationTool.HttpExecutor;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class ApplicationIntegrationToolTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  // Mocks for injected dependencies
  @Mock private HttpExecutor mockHttpExecutor;
  @Mock private HttpResponse<String> mockHttpResponse;
  @Mock private ToolContext mockToolContext;

  private ApplicationIntegrationTool tool;
  private static final String MOCK_ACCESS_TOKEN = "test-fake-token";
  private static final String MOCK_OPEN_API_SPEC =
      "{\"openApiSpec\":\""
          + "{\\\"openapi\\\":\\\"3.0.1\\\",\\\"info\\\":{\\\"title\\\":\\\"test\\\",\\\"version\\\":\\\"4\\\"},"
          + "\\\"paths\\\":{\\\"/v1/trigger/Trigger1\\\":{\\\"post\\\":{\\\"summary\\\":\\\"test"
          + " summary"
          + " 1\\\",\\\"operationId\\\":\\\"Trigger1\\\",\\\"requestBody\\\":{\\\"content\\\":{\\\"application/json\\\":{\\\"schema\\\":{\\\"$ref\\\":\\\"#/components/schemas/Trigger1_Request\\\"}}}}}},\\\"/v1/trigger/Trigger2\\\":{\\\"post\\\":{\\\"summary\\\":\\\"test"
          + " summary 2\\\",\\\"operationId\\\":\\\"Trigger2\\\"}}},"
          + "\\\"components\\\":{\\\"schemas\\\":{\\\"ItemObject\\\":{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"id\\\":{\\\"type\\\":\\\"string\\\"},"
          + " \\\"name\\\":{\\\"type\\\":\\\"string\\\"}}},"
          + "\\\"Trigger1_Request\\\":{\\\"type\\\":\\\"OBJECT\\\",\\\"properties\\\":{"
          + "\\\"line_items\\\":{\\\"type\\\":\\\"array\\\",\\\"items\\\":{\\\"$ref\\\":\\\"#/components/schemas/ItemObject\\\"}},"
          + " \\\"order_id\\\":{\\\"type\\\":\\\"string\\\"}}},"
          + "\\\"Trigger2_Request\\\":{\\\"type\\\":\\\"object\\\"}}}}\"}";

  private static final String FAKE_PATH_URL =
      "/v2/projects/test-project/locations/test-region/integrations/test:execute?triggerId=api_trigger/Trigger1";
  private static final String FAKE_TOOL_NAME = "Trigger1";

  @Before
  public void setUp() {
    tool =
        new ApplicationIntegrationTool(
            MOCK_OPEN_API_SPEC, FAKE_PATH_URL, FAKE_TOOL_NAME, "A test tool", mockHttpExecutor);
  }

  @Test
  public void declaration_success() {
    Optional<FunctionDeclaration> declarationOpt = tool.declaration();
    assertThat(declarationOpt).isPresent();
    FunctionDeclaration declaration = declarationOpt.get();
    assertThat(declaration.name()).hasValue(FAKE_TOOL_NAME);
    assertThat(declaration.description()).hasValue("test summary 1");

    Optional<Schema> paramsOpt = declaration.parameters();
    assertThat(paramsOpt).isPresent();
    Schema paramsSchema = paramsOpt.get();
    assertThat(paramsSchema.type()).hasValue(new Type("OBJECT"));
    assertThat(paramsSchema.properties()).isPresent();

    Map<String, Schema> propsMap = paramsSchema.properties().get();
    assertThat(propsMap).containsKey("order_id");
    assertThat(propsMap).containsKey("line_items");

    Schema lineItemsSchema = propsMap.get("line_items");
    assertThat(lineItemsSchema.type()).hasValue(new Type("ARRAY"));
    assertThat(lineItemsSchema.items()).isPresent();

    Schema itemSchema = lineItemsSchema.items().get();
    assertThat(itemSchema.type()).hasValue(new Type("OBJECT"));
    assertThat(itemSchema.properties()).isPresent();
    Map<String, Schema> itemPropsMap = itemSchema.properties().get();
    assertThat(itemPropsMap).containsKey("id");
    assertThat(itemPropsMap).containsKey("name");
  }

  @Test
  public void declaration_operationNotFound_returnsEmpty() {
    ApplicationIntegrationTool badTool =
        new ApplicationIntegrationTool(
            MOCK_OPEN_API_SPEC,
            "/bad/path/triggerId=api_trigger/not-found",
            "not-found",
            "",
            mockHttpExecutor);

    Optional<FunctionDeclaration> declarationOpt = badTool.declaration();

    assertThat(declarationOpt.isPresent()).isFalse();
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void runAsync_success() throws Exception {
    String expectedResponse = "{\"executionId\":\"12345\"}";
    ImmutableMap<String, Object> inputArgs = ImmutableMap.of("username", "testuser");
    ApplicationIntegrationTool spyTool = spy(tool);

    doReturn(MOCK_ACCESS_TOKEN).when(spyTool).getAccessToken();
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(expectedResponse);

    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(HttpRequest.class), any());

    spyTool
        .runAsync(inputArgs, mockToolContext)
        .test()
        .assertNoErrors()
        .assertValue(ImmutableMap.of("result", expectedResponse));
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void runAsync_httpError_returnsErrorMap() throws Exception {
    String errorResponse = "{\"error\":{\"message\":\"Permission denied.\"}}";
    ImmutableMap<String, Object> inputArgs = ImmutableMap.of("username", "testuser");
    ApplicationIntegrationTool spyTool = spy(tool);
    doReturn(MOCK_ACCESS_TOKEN).when(spyTool).getAccessToken();

    when(mockHttpResponse.statusCode()).thenReturn(403);
    when(mockHttpResponse.body()).thenReturn(errorResponse);

    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(HttpRequest.class), any());

    String expectedErrorMessage =
        "Error executing integration. Status: 403 , Response: " + errorResponse;
    spyTool
        .runAsync(inputArgs, mockToolContext)
        .test()
        .assertNoErrors()
        .assertValue(ImmutableMap.of("error", expectedErrorMessage));
  }

  @Test
  public void extractTriggerIdFromPath_success() {
    String triggerId = tool.extractTriggerIdFromPath(FAKE_PATH_URL);

    assertThat(triggerId).isEqualTo(FAKE_TOOL_NAME);
  }

  @Test
  public void extractTriggerIdFromPath_noTrigger_returnsNull() {
    String pathWithoutTrigger = "/v1/integrations/some/other/path";

    String triggerId = tool.extractTriggerIdFromPath(pathWithoutTrigger);

    assertThat(triggerId).isNull();
  }
}
