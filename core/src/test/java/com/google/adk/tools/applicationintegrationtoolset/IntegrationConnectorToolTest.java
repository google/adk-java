package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.adk.tools.ToolContext;
import com.google.adk.tools.applicationintegrationtoolset.IntegrationConnectorTool.HttpExecutor;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.observers.TestObserver;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
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
public final class IntegrationConnectorToolTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private HttpExecutor mockHttpExecutor;
  @Mock private HttpResponse<String> mockHttpResponse;
  @Mock private ToolContext mockToolContext;

  private IntegrationConnectorTool integrationTool;
  private IntegrationConnectorTool connectorTool;
  private IntegrationConnectorTool connectorToolWithAction;
  private IntegrationConnectorTool connectorToolWithServiceAccount;
  private static final String MOCK_ACCESS_TOKEN = "test-token";
  private static final String MOCK_ACCESS_TOKEN_2 = "test-token-2";
  private static final String MOCK_SERVICE_ACCOUNT_JSON =
      "{\"type\": \"service_account\",\"project_id\": \"test-project\",\"private_key_data\":"
          + " \"test-private-key-data\",\"client_email\": \"test-client-email\",\"client_id\":"
          + " \"test-client-id\",\"auth_uri\":"
          + " \"https://accounts.google.com/o/oauth2/auth\",\"token_uri\":"
          + " \"https://oauth2.googleapis.com/token\",\"refresh_token\": \"1/1234567890\"}";
  private static final String FAKE_PATH_URL =
      "/v2/projects/test-project/locations/test-region/integrations/test:execute?triggerId=api_trigger/Trigger1";
  private static final String FAKE_TOOL_NAME = "Trigger1";

  private static final String MOCK_INTEGRATION_OPEN_API_SPEC =
      "{\"openApiSpec\":\""
          + "{\\\"openapi\\\":\\\"3.0.1\\\",\\\"info\\\":{\\\"title\\\":\\\"test\\\",\\\"version\\\":\\\"4\\\"},"
          + "\\\"paths\\\":{\\\"/v2/projects/test-project/locations/test-region/integrations/test:execute?triggerId=api_trigger/Trigger1\\\":{\\\"post\\\":{\\\"summary\\\":\\\"test"
          + " summary"
          + " 1\\\",\\\"operationId\\\":\\\"Trigger1\\\",\\\"requestBody\\\":{\\\"content\\\":{\\\"application/json\\\":{\\\"schema\\\":{\\\"$ref\\\":\\\"#/components/schemas/Trigger1_Request\\\"}}}}}},\\\"/v1/trigger/Trigger2\\\":{\\\"post\\\":{\\\"summary\\\":\\\"test"
          + " summary 2\\\",\\\"operationId\\\":\\\"Trigger2\\\"}}},"
          + "\\\"components\\\":{\\\"schemas\\\":{\\\"ItemObject\\\":{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"id\\\":{\\\"type\\\":\\\"string\\\"},"
          + " \\\"name\\\":{\\\"type\\\":\\\"string\\\"}}},"
          + "\\\"Trigger1_Request\\\":{\\\"type\\\":\\\"OBJECT\\\",\\\"properties\\\":{"
          + "\\\"line_items\\\":{\\\"type\\\":\\\"array\\\",\\\"items\\\":{\\\"$ref\\\":\\\"#/components/schemas/ItemObject\\\"}},"
          + " \\\"order_id\\\":{\\\"type\\\":\\\"string\\\"}}, \\\"required\\\":"
          + " [\\\"order_id\\\"]}}}}\"}";

  public static final String LIST_ENTITIES_SPEC =
      "{\"openApiSpec\": \"{\\\"openapi\\\":\\\"3.0.1\\\",\\\"info\\\":{\\\"title\\\":\\\"Connector"
          + " for Listing"
          + " Issues\\\"},\\\"paths\\\":{\\\"/v2/projects/test-project/locations/test-region/integrations/ExecuteConnection:execute\\\":{\\\"post\\\":{\\\"summary\\\":\\\"List"
          + " Issue entities from the"
          + " connection\\\",\\\"operationId\\\":\\\"list_issues\\\",\\\"x-entity\\\":\\\"Issue\\\",\\\"x-operation\\\":\\\"LIST_ENTITIES\\\",\\\"requestBody\\\":{\\\"required\\\":true,\\\"content\\\":{\\\"application/json\\\":{\\\"schema\\\":{\\\"$ref\\\":\\\"#/components/schemas/ListRequest\\\"}}}}}}},\\\"components\\\":{\\\"schemas\\\":{\\\"ListRequest\\\":{\\\"type\\\":\\\"object\\\",\\\"required\\\":[\\\"connectionName\\\",\\\"entity\\\",\\\"operation\\\"],\\\"properties\\\":{\\\"connectionName\\\":{\\\"type\\\":\\\"string\\\"},\\\"serviceName\\\":{\\\"type\\\":\\\"string\\\"},\\\"host\\\":{\\\"type\\\":\\\"string\\\"},\\\"entity\\\":{\\\"type\\\":\\\"string\\\"},\\\"operation\\\":{\\\"type\\\":\\\"string\\\"},\\\"pageSize\\\":{\\\"type\\\":\\\"integer\\\"}}}}}}\"}";

  public static final String EXECUTE_ACTION_SPEC =
      "{\"openApiSpec\": \"{\\\"openapi\\\":\\\"3.0.1\\\",\\\"info\\\":{\\\"title\\\":\\\"Connector"
          + " for Executing"
          + " Action\\\"},\\\"paths\\\":{\\\"/v2/projects/test-project/locations/test-region/integrations/ExecuteConnection:execute\\\":{\\\"post\\\":{\\\"summary\\\":\\\"Execute"
          + " a custom"
          + " action\\\",\\\"operationId\\\":\\\"execute_custom_action\\\",\\\"x-action\\\":\\\"CUSTOM_ACTION\\\""
          + " ,\\\"x-operation\\\":\\\"EXECUTE_ACTION\\\",\\\"requestBody\\\":{\\\"required\\\":true,\\\"content\\\":{\\\"application/json\\\":{\\\"schema\\\":{\\\"$ref\\\":\\\"#/components/schemas/ActionRequest\\\"}}}}}}},\\\"components\\\":{\\\"schemas\\\":{\\\"ActionRequest\\\":{\\\"type\\\":\\\"object\\\",\\\"required\\\":[\\\"connectionName\\\",\\\"action\\\",\\\"operation\\\"],\\\"properties\\\":{\\\"connectionName\\\":{\\\"type\\\":\\\"string\\\"},\\\"serviceName\\\":{\\\"type\\\":\\\"string\\\"},\\\"host\\\":{\\\"type\\\":\\\"string\\\"},\\\"action\\\":{\\\"type\\\":\\\"string\\\"},\\\"operation\\\":{\\\"type\\\":\\\"string\\\"}}}}}}\"}";

  @Before
  public void setUp() {
    integrationTool =
        new IntegrationConnectorTool(
            MOCK_INTEGRATION_OPEN_API_SPEC,
            FAKE_PATH_URL,
            FAKE_TOOL_NAME,
            "A test tool",
            null,
            null,
            null,
            null,
            mockHttpExecutor);

    connectorTool =
        new IntegrationConnectorTool(
            LIST_ENTITIES_SPEC,
            "/v2/projects/test-project/locations/test-region/integrations/ExecuteConnection:execute",
            "list_issues",
            "A test tool for listing entities",
            "test-connection",
            "test-service",
            "test-host",
            null,
            mockHttpExecutor);

    connectorToolWithAction =
        new IntegrationConnectorTool(
            EXECUTE_ACTION_SPEC,
            "/v2/projects/test-project/locations/test-region/integrations/ExecuteConnection:execute",
            "execute_custom_action",
            "A test tool for executing an action",
            "test-connection-action",
            "test-service-action",
            "test-host-action",
            null,
            mockHttpExecutor);

    connectorToolWithServiceAccount =
        new IntegrationConnectorTool(
            EXECUTE_ACTION_SPEC,
            "/v2/projects/test-project/locations/test-region/integrations/ExecuteConnection:execute",
            "execute_custom_action",
            "A test tool for executing an action",
            "test-connection-action",
            "test-service-action",
            "test-host-action",
            MOCK_SERVICE_ACCOUNT_JSON,
            mockHttpExecutor);
  }

  @Test
  public void integrationTool_declaration_success() {
    Optional<FunctionDeclaration> declarationOpt = integrationTool.declaration();
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
    assertThat(paramsSchema.required()).hasValue(ImmutableList.of("order_id"));
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
  public void declaration_removesExcludedAndOptionalFields_fromSchema() {
    Optional<FunctionDeclaration> declarationOpt = connectorTool.declaration();
    assertThat(declarationOpt).isPresent();

    Schema paramsSchema = declarationOpt.get().parameters().get();

    assertThat(paramsSchema.type()).hasValue(new Type("OBJECT"));
    Map<String, Schema> propsMap = paramsSchema.properties().get();
    assertThat(propsMap).doesNotContainKey("connectionName");
    assertThat(propsMap).doesNotContainKey("serviceName");
    assertThat(propsMap).doesNotContainKey("host");
    assertThat(propsMap).doesNotContainKey("entity");
    assertThat(propsMap).doesNotContainKey("operation");
    assertThat(propsMap).doesNotContainKey("action");
    assertThat(propsMap).containsKey("pageSize");
    assertThat(paramsSchema.required().get()).isEmpty();
  }

  @Test
  public void integrationTool_declaration_operationNotFound_returnsEmpty() {
    IntegrationConnectorTool badTool =
        new IntegrationConnectorTool(
            MOCK_INTEGRATION_OPEN_API_SPEC,
            "/bad/path/triggerId=api_trigger/not-found",
            "not-found",
            "",
            null,
            null,
            null,
            null,
            mockHttpExecutor);

    Optional<FunctionDeclaration> declarationOpt = badTool.declaration();

    assertThat(declarationOpt).isEmpty();
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void integrationTool_runAsync_success() throws Exception {
    String expectedResponse = "{\"executionId\":\"12345\"}";
    Map<String, Object> inputArgs = new HashMap<>(ImmutableMap.of("username", "testuser"));
    IntegrationConnectorTool spyTool = spy(integrationTool);

    when(mockHttpExecutor.getToken()).thenReturn(MOCK_ACCESS_TOKEN);
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
  public void connectorTool_runAsync_success() throws Exception {
    String expectedResponse = "{\"connectorOutputPayload\":[\"issue1\"]}";
    IntegrationConnectorTool spyTool = spy(connectorTool);
    var unused = spyTool.declaration();

    Map<String, Object> inputArgs = new HashMap<>();

    when(mockHttpExecutor.getToken()).thenReturn(MOCK_ACCESS_TOKEN);
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(expectedResponse);
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(), any());

    TestObserver<Map<String, Object>> testObserver =
        spyTool.runAsync(inputArgs, mockToolContext).test();
    testObserver.assertNoErrors().assertValue(ImmutableMap.of("result", expectedResponse));

    assertThat(inputArgs).containsEntry("connectionName", "test-connection");
    assertThat(inputArgs).containsEntry("serviceName", "test-service");
    assertThat(inputArgs).containsEntry("host", "test-host");
    assertThat(inputArgs).containsEntry("entity", "Issue");
    assertThat(inputArgs).containsEntry("operation", "LIST_ENTITIES");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void connectorToolWithAction_runAsync_success() throws Exception {
    String expectedResponse = "{\"connectorOutputPayload\":[\"issue1\"]}";
    IntegrationConnectorTool spyTool = spy(connectorToolWithAction);
    var unused = spyTool.declaration();

    Map<String, Object> inputArgs = new HashMap<>();

    when(mockHttpExecutor.getToken()).thenReturn(MOCK_ACCESS_TOKEN);
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(expectedResponse);
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(), any());

    TestObserver<Map<String, Object>> testObserver =
        spyTool.runAsync(inputArgs, mockToolContext).test();
    testObserver.assertNoErrors().assertValue(ImmutableMap.of("result", expectedResponse));

    assertThat(inputArgs).containsEntry("connectionName", "test-connection-action");
    assertThat(inputArgs).containsEntry("serviceName", "test-service-action");
    assertThat(inputArgs).containsEntry("host", "test-host-action");
    assertThat(inputArgs).containsEntry("action", "CUSTOM_ACTION");
    assertThat(inputArgs).containsEntry("operation", "EXECUTE_ACTION");
    assertThat(inputArgs).doesNotContainKey("entity");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void runAsync_serviceAccountJson_throwsPermissionDenied() throws Exception {
    String errorResponse = "{\"error\":{\"message\":\"Permission denied.\"}}";
    Map<String, Object> inputArgs = new HashMap<>(ImmutableMap.of("username", "testuser"));
    IntegrationConnectorTool spyTool = spy(connectorToolWithServiceAccount);
    when(mockHttpExecutor.getToken()).thenReturn(MOCK_ACCESS_TOKEN);

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
  @SuppressWarnings("MockitoDoSetup")
  public void connectorToolWithServiceAccount_runAsync_success() throws Exception {
    String expectedResponse = "{\"action_result\":\"success\"}";
    IntegrationConnectorTool spyTool = spy(connectorToolWithServiceAccount);
    var unused = spyTool.declaration();

    Map<String, Object> inputArgs = new HashMap<>();
    inputArgs.put("payload", "data");

    when(mockHttpExecutor.getToken()).thenReturn(MOCK_ACCESS_TOKEN_2);
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(expectedResponse);
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(), any());

    TestObserver<Map<String, Object>> testObserver =
        spyTool.runAsync(inputArgs, mockToolContext).test();
    testObserver.assertNoErrors().assertValue(ImmutableMap.of("result", expectedResponse));

    assertThat(inputArgs).containsEntry("connectionName", "test-connection-action");
    assertThat(inputArgs).containsEntry("serviceName", "test-service-action");
    assertThat(inputArgs).containsEntry("host", "test-host-action");
    assertThat(inputArgs).containsEntry("action", "CUSTOM_ACTION");
    assertThat(inputArgs).containsEntry("payload", "data");
    assertThat(inputArgs).containsEntry("operation", "EXECUTE_ACTION");
    assertThat(inputArgs).doesNotContainKey("entity");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void runAsync_httpError_returnsErrorMap() throws Exception {
    String errorResponse = "{\"error\":{\"message\":\"Permission denied.\"}}";
    Map<String, Object> inputArgs = new HashMap<>(ImmutableMap.of("username", "testuser"));
    IntegrationConnectorTool spyTool = spy(integrationTool);
    when(mockHttpExecutor.getToken()).thenReturn(MOCK_ACCESS_TOKEN);

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
  public void getOperationIdFromPathUrl_success() throws Exception {
    String triggerId =
        integrationTool.getOperationIdFromPathUrl(MOCK_INTEGRATION_OPEN_API_SPEC, FAKE_PATH_URL);
    assertThat(triggerId).isEqualTo(FAKE_TOOL_NAME);
  }

  @Test
  public void getOperationIdFromPathUrl_noTrigger_throwsException() {
    String pathWithoutTrigger = "/v1/integrations/some/other/path";
    String expectedErrorMessage = "Could not find operationId for pathUrl: " + pathWithoutTrigger;

    Exception exception =
        assertThrows(
            Exception.class,
            () ->
                integrationTool.getOperationIdFromPathUrl(
                    MOCK_INTEGRATION_OPEN_API_SPEC, pathWithoutTrigger));

    assertThat(exception).hasMessageThat().contains(expectedErrorMessage);
  }
}
