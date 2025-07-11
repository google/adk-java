package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.applicationintegrationtoolset.ApplicationIntegrationTool.HttpExecutor;
import com.google.adk.tools.applicationintegrationtoolset.ConnectionsClient.ActionSchema;
import com.google.adk.tools.applicationintegrationtoolset.ConnectionsClient.ConnectionDetails;
import com.google.adk.tools.applicationintegrationtoolset.ConnectionsClient.EntitySchemaAndOperations;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
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

  @Mock private HttpExecutor mockHttpExecutor;
  @Mock private HttpResponse<String> mockHttpResponse;

  private ConnectionsClient client;
  private final ObjectMapper objectMapper = new ObjectMapper();

  private static final String PROJECT = "test-project";
  private static final String LOCATION = "us-central1";
  private static final String CONNECTION = "test-conn";

  @Before
  public void setUp() throws IOException {
    client = new ConnectionsClient(PROJECT, LOCATION, CONNECTION, mockHttpExecutor, objectMapper);
    when(mockHttpExecutor.getToken()).thenReturn("fake-test-token");
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
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(HttpRequest.class), any());

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
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(HttpRequest.class), any());

    EntitySchemaAndOperations result = client.getEntitySchemaAndOperations("Issue");

    assertThat(result.operations).containsExactly("GET", "LIST").inOrder();
    assertThat(result.schema).containsEntry("type", "object");
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void getEntitySchemaAndOperations_noOperationId_throwsIOException() throws Exception {
    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn("{}");
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(), any());

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
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(HttpRequest.class), any());

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
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(), any());

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
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(), any());

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
    doReturn(mockHttpResponse).when(mockHttpExecutor).send(any(), any());

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
}
