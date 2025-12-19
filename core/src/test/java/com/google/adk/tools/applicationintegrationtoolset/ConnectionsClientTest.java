package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.applicationintegrationtoolset.ConnectionsClient.ActionSchema;
import com.google.adk.tools.applicationintegrationtoolset.ConnectionsClient.ConnectionDetails;
import com.google.adk.tools.applicationintegrationtoolset.ConnectionsClient.EntitySchemaAndOperations;
import com.google.auth.Credentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class ConnectionsClientTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private HttpClient mockHttpClient;
  @Mock private HttpResponse<String> mockHttpResponse;
  @Mock private CredentialsHelper mockCredentialsHelper;
  @Mock private Credentials mockCredentials;

  private ConnectionsClient client;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String PROJECT = "test-project";
  private static final String LOCATION = "us-central1";
  private static final String CONNECTION = "test-conn";

  @Before
  public void setUp() throws IOException {
    when(mockCredentialsHelper.getGoogleCredentials(any())).thenReturn(mockCredentials);
    client =
        new ConnectionsClient(
            PROJECT,
            LOCATION,
            CONNECTION,
            null,
            mockHttpClient,
            mockCredentialsHelper,
            objectMapper);
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void getConnectionDetails_success_parsesResponseCorrectly() throws Exception {
    String connectionName = "projects/test-project/locations/us-central1/connections/test-conn";
    String mockJsonResponse =
        String.format(
            "{\"name\": \"%s\", \"serviceDirectory\": \"my-service.com\"}", connectionName);

    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(mockJsonResponse);
    doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

    ConnectionDetails details = client.getConnectionDetails();

    assertThat(details.name).isEqualTo(connectionName);
    assertThat(details.serviceName).isEqualTo("my-service.com");
    assertThat(details.host).isEmpty();
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void getEntitySchemaAndOperations_withPolling_success() throws Exception {
    String initialCallResponse = "{\"name\": \"operations/123\"}";
    String firstPollResponse = "{\"name\": \"operations/123\", \"done\": false}";
    String finalPollResponse =
        "{\"name\": \"operations/123\", \"done\": true, \"response\": {\"jsonSchema\": {\"type\":"
            + " \"object\"}, \"operations\": [\"GET\", \"LIST\"]}}";

    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body())
        .thenReturn(initialCallResponse)
        .thenReturn(firstPollResponse)
        .thenReturn(finalPollResponse);
    doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

    EntitySchemaAndOperations result = client.getEntitySchemaAndOperations("Issue");

    assertThat(result.operations).containsExactly("GET", "LIST").inOrder();
    assertThat(result.schema).containsEntry("type", "object");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void getEntitySchemaAndOperations_noOperationId_throwsIOException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("{}");
    doReturn(mockHttpResponse).when(mockHttpClient).send(any(), any());

    IOException e =
        assertThrows(IOException.class, () -> client.getEntitySchemaAndOperations("InvalidEntity"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Failed to get operation ID for entity: InvalidEntity");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void getActionSchema_success() throws Exception {
    String initialCallResponse = "{\"name\": \"operations/456\"}";
    String finalPollResponse =
        "{\"name\": \"operations/456\", \"done\": true, \"response\": {\"inputJsonSchema\":"
            + " {\"type\": \"object\"}, \"outputJsonSchema\": {\"type\": \"array\"},"
            + " \"description\": \"Test Description\", \"displayName\": \"Test Action\"}}";

    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(initialCallResponse).thenReturn(finalPollResponse);
    doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

    ActionSchema result = client.getActionSchema("TestAction");

    assertThat(result.inputSchema).containsEntry("type", "object");
    assertThat(result.outputSchema).containsEntry("type", "array");
    assertThat(result.description).isEqualTo("Test Description");
    assertThat(result.displayName).isEqualTo("Test Action");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void getActionSchema_noOperationId_throwsIOException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("{}");
    doReturn(mockHttpResponse).when(mockHttpClient).send(any(), any());

    IOException e = assertThrows(IOException.class, () -> client.getActionSchema("InvalidAction"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Failed to get operation ID for action: InvalidAction");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void executeApiCall_on403_throwsSecurityException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(403);
    when(mockHttpResponse.body()).thenReturn("Permission Denied Error");
    doReturn(mockHttpResponse).when(mockHttpClient).send(any(), any());

    SecurityException e =
        assertThrows(SecurityException.class, () -> client.getConnectionDetails());
    assertThat(e).hasMessageThat().contains("Permission error (status 403)");
    assertThat(e).hasMessageThat().contains("Permission Denied Error");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void executeApiCall_on404_throwsIllegalArgumentException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(404);
    when(mockHttpResponse.body()).thenReturn("Not Found Error");
    doReturn(mockHttpResponse).when(mockHttpClient).send(any(), any());

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> client.getConnectionDetails());
    assertThat(e).hasMessageThat().contains("Invalid request");
    assertThat(e).hasMessageThat().contains("Not Found Error");
  }

  @Test
  public void convertJsonSchemaToOpenApiSchema_convertsCorrectly() {
    ImmutableMap<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "object",
            "description",
            "An issue object",
            "properties",
            ImmutableMap.of(
                "id", ImmutableMap.of("type", "integer"),
                "summary", ImmutableMap.of("type", ImmutableList.of("string", "null"))));

    Map<String, Object> openApiSchema = client.convertJsonSchemaToOpenApiSchema(jsonSchema);

    assertThat(openApiSchema.get("description")).isEqualTo("An issue object");
    assertThat(openApiSchema.get("type")).isEqualTo("object");

    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) openApiSchema.get("properties");
    assertThat(properties).isNotNull();

    @SuppressWarnings("unchecked")
    Map<String, Object> summaryProp = (Map<String, Object>) properties.get("summary");
    assertThat(summaryProp.get("nullable")).isEqualTo(true);
    assertThat(summaryProp.get("type")).isEqualTo("string");
  }

  @Test
  public void convertJsonSchemaToOpenApiSchema_arrayType_convertsCorrectly() {
    ImmutableMap<String, Object> jsonSchema =
        ImmutableMap.of(
            "type",
            "array",
            "description",
            "List of issues",
            "items",
            ImmutableMap.of("type", "string"));

    Map<String, Object> openApiSchema = client.convertJsonSchemaToOpenApiSchema(jsonSchema);

    assertThat(openApiSchema.get("description")).isEqualTo("List of issues");
    assertThat(openApiSchema.get("type")).isEqualTo("array");
    @SuppressWarnings("unchecked")
    Map<String, Object> items = (Map<String, Object>) openApiSchema.get("items");
    assertThat(items.get("type")).isEqualTo("string");
  }

  @Test
  public void convertJsonSchemaToOpenApiSchema_simpleType_convertsCorrectly() {
    ImmutableMap<String, Object> jsonSchema =
        ImmutableMap.of("type", "string", "description", "A String");

    Map<String, Object> openApiSchema = client.convertJsonSchemaToOpenApiSchema(jsonSchema);

    assertThat(openApiSchema.get("type")).isEqualTo("string");
    assertThat(openApiSchema.get("description")).isEqualTo("A String");
  }

  @Test
  public void convertJsonSchemaToOpenApiSchema_nullType_convertsCorrectly() {
    ImmutableMap<String, Object> jsonSchema = ImmutableMap.of("type", ImmutableList.of("null"));

    Map<String, Object> openApiSchema = client.convertJsonSchemaToOpenApiSchema(jsonSchema);

    assertThat(openApiSchema.get("nullable")).isEqualTo(true);
  }

  @Test
  public void convertJsonSchemaToOpenApiSchema_emptyJson_returnsEmptyMap() {
    ImmutableMap<String, Object> jsonSchema = ImmutableMap.of();

    Map<String, Object> openApiSchema = client.convertJsonSchemaToOpenApiSchema(jsonSchema);

    assertThat(openApiSchema).isEmpty();
  }

  @Test
  public void getConnectorBaseSpec_returnsCorrectSpec() {
    ImmutableMap<String, Object> spec = ConnectionsClient.getConnectorBaseSpec();

    assertThat(spec).containsKey("openapi");
    assertThat(spec.get("info")).isInstanceOf(Map.class);

    @SuppressWarnings("unchecked")
    Map<String, Object> info = (Map<String, Object>) spec.get("info");
    assertThat(info.get("title")).isEqualTo("ExecuteConnection");
    assertThat(spec).containsKey("components");

    @SuppressWarnings("unchecked")
    Map<String, Object> components = (Map<String, Object>) spec.get("components");
    assertThat(components).containsKey("schemas");

    @SuppressWarnings("unchecked")
    Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
    assertThat(schemas).containsKey("operation");
  }

  @Test
  public void getActionOperation_returnsCorrectOperation() {
    ImmutableMap<String, Object> operation =
        ConnectionsClient.getActionOperation(
            "TestAction",
            "EXECUTE_ACTION",
            "TestActionDisplayName",
            "test_tool",
            "tool instructions");

    assertThat(operation).containsKey("post");
    assertThat(operation.get("post")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> post = (Map<String, Object>) operation.get("post");
    assertThat(post.get("summary")).isEqualTo("TestActionDisplayName");
    assertThat(post.get("description"))
        .isEqualTo("Use this tool to execute TestAction tool instructions");
    assertThat(post).containsKey("operationId");
    assertThat(post.get("operationId")).isEqualTo("test_tool_TestActionDisplayName");
  }

  @Test
  public void getListOperation_returnsCorrectOperation() {
    ImmutableMap<String, Object> operation =
        ConnectionsClient.listOperation(
            "Entity1", "{\"type\": \"object\"}", "test_tool", "tool instructions");

    assertThat(operation).containsKey("post");
    assertThat(operation.get("post")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> post = (Map<String, Object>) operation.get("post");
    assertThat(post.get("summary")).isEqualTo("List Entity1");
    assertThat(post).containsKey("operationId");
    assertThat(post.get("operationId")).isEqualTo("test_tool_list_Entity1");
  }

  @Test
  public void getGetOperation_returnsCorrectOperation() {
    ImmutableMap<String, Object> operation =
        ConnectionsClient.getOperation(
            "Entity1", "{\"type\": \"object\"}", "test_tool", "tool instructions");

    assertThat(operation).containsKey("post");
    assertThat(operation.get("post")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> post = (Map<String, Object>) operation.get("post");
    assertThat(post.get("summary")).isEqualTo("Get Entity1");
    assertThat(post).containsKey("operationId");
    assertThat(post.get("operationId")).isEqualTo("test_tool_get_Entity1");
  }

  @Test
  public void getCreateOperation_returnsCorrectOperation() {
    ImmutableMap<String, Object> operation =
        ConnectionsClient.createOperation("Entity1", "test_tool", "tool instructions");

    assertThat(operation).containsKey("post");
    assertThat(operation.get("post")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> post = (Map<String, Object>) operation.get("post");
    assertThat(post.get("summary")).isEqualTo("Creates a new Entity1");
    assertThat(post).containsKey("operationId");
    assertThat(post.get("operationId")).isEqualTo("test_tool_create_Entity1");
  }

  @Test
  public void getUpdateOperation_returnsCorrectOperation() {
    ImmutableMap<String, Object> operation =
        ConnectionsClient.updateOperation("Entity1", "test_tool", "tool instructions");

    assertThat(operation).containsKey("post");
    assertThat(operation.get("post")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> post = (Map<String, Object>) operation.get("post");
    assertThat(post.get("summary")).isEqualTo("Updates the Entity1");
    assertThat(post).containsKey("operationId");
    assertThat(post.get("operationId")).isEqualTo("test_tool_update_Entity1");
  }

  @Test
  public void getDeleteOperation_returnsCorrectOperation() {
    ImmutableMap<String, Object> operation =
        ConnectionsClient.deleteOperation("Entity1", "test_tool", "tool instructions");

    assertThat(operation).containsKey("post");
    assertThat(operation.get("post")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> post = (Map<String, Object>) operation.get("post");
    assertThat(post.get("summary")).isEqualTo("Delete the Entity1");
    assertThat(post).containsKey("operationId");
    assertThat(post.get("operationId")).isEqualTo("test_tool_delete_Entity1");
  }

  @Test
  public void getCreateOperationRequest_returnsCorrectRequest() {
    ImmutableMap<String, Object> schema = ConnectionsClient.createOperationRequest("Entity1");

    assertThat(schema).containsKey("type");
    assertThat(schema.get("type")).isEqualTo("object");
    assertThat(schema).containsKey("properties");
    assertThat(schema.get("properties")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties).containsKey("connectorInputPayload");
  }

  @Test
  public void getUpdateOperationRequest_returnsCorrectRequest() {
    ImmutableMap<String, Object> schema = ConnectionsClient.updateOperationRequest("Entity1");

    assertThat(schema).containsKey("type");
    assertThat(schema.get("type")).isEqualTo("object");
    assertThat(schema).containsKey("properties");
    assertThat(schema.get("properties")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties).containsKey("entityId");
    assertThat(properties).containsKey("filterClause");
  }

  @Test
  public void getGetOperationRequestStatic_returnsCorrectRequest() {
    ImmutableMap<String, Object> schema = ConnectionsClient.getOperationRequest();

    assertThat(schema).containsKey("type");
    assertThat(schema.get("type")).isEqualTo("object");
    assertThat(schema).containsKey("properties");
    assertThat(schema.get("properties")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
    assertThat(properties).containsKey("entityId");
  }
}
