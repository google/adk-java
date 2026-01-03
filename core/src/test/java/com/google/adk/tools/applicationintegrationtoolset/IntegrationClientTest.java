package com.google.adk.tools.applicationintegrationtoolset;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.tools.applicationintegrationtoolset.ConnectionsClient.EntitySchemaAndOperations;
import com.google.auth.Credentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
public class IntegrationClientTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private HttpClient mockHttpClient;
  @Mock private HttpResponse<String> mockHttpResponse;
  @Mock private ConnectionsClient mockConnectionsClient; // The mock we want the factory to return
  @Mock private CredentialsHelper mockCredentialsHelper;
  @Mock private Credentials mockCredentials;

  private static final String PROJECT = "test-project";
  private static final String LOCATION = "us-central1";
  private static final String INTEGRATION = "test-integration";
  private static final String CONNECTION = "test-connection";
  private static final String TOOL_NAME = "MyTool";
  private static final String TOOL_INSTRUCTIONS = "Instructions";

  @Before
  public void setUp() throws IOException {
    when(mockCredentialsHelper.getGoogleCredentials(any())).thenReturn(mockCredentials);
  }

  @Test
  public void constructor_entityOperationsNullAndActionsNull_throwsException() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    INTEGRATION,
                    ImmutableList.of("trigger1"),
                    CONNECTION,
                    null,
                    null,
                    null,
                    mockHttpClient,
                    mockCredentialsHelper));

    assertThat(exception)
        .hasMessageThat()
        .contains("No entity operations or actions provided. Please provide at least one of them.");
  }

  @Test
  public void constructor_entityOperationsEmpty_throwsException() {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    INTEGRATION,
                    ImmutableList.of("trigger1"),
                    CONNECTION,
                    ImmutableMap.of(),
                    null,
                    null,
                    mockHttpClient,
                    mockCredentialsHelper));

    assertThat(exception).hasMessageThat().contains("entityOperations map cannot be empty");
  }

  @Test
  public void constructor_entityOperationsNullKey_throwsException() {
    Map<String, List<String>> invalidEntityOperations = new HashMap<>();
    invalidEntityOperations.put(null, ImmutableList.of("value1", "value2"));

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    INTEGRATION,
                    ImmutableList.of("trigger1"),
                    CONNECTION,
                    invalidEntityOperations,
                    null,
                    mockHttpClient));

    assertThat(exception)
        .hasMessageThat()
        .contains("Enitity in entityOperations map cannot be null or empty");
  }

  @Test
  public void constructor_entityOperationsEmptyKey_throwsException() {
    ImmutableMap<String, List<String>> invalidEntityOperations =
        ImmutableMap.of("", ImmutableList.of("value1", "value2"));

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    INTEGRATION,
                    ImmutableList.of("trigger1"),
                    CONNECTION,
                    invalidEntityOperations,
                    null,
                    mockHttpClient));

    assertThat(exception)
        .hasMessageThat()
        .contains("Enitity in entityOperations map cannot be null or empty");
  }

  @Test
  public void constructor_entityOperationsNullListValue_throwsException() {
    Map<String, List<String>> invalidEntityOperations = new HashMap<>();
    invalidEntityOperations.put("key1", null);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    INTEGRATION,
                    ImmutableList.of("trigger1"),
                    CONNECTION,
                    invalidEntityOperations,
                    null,
                    null,
                    mockHttpClient,
                    mockCredentialsHelper));

    assertThat(exception).hasMessageThat().contains("Operations for entity 'key1' cannot be null");
  }

  @Test
  public void constructor_entityOperationsListWithNullString_throwsException() {
    Map<String, List<String>> invalidEntityOperations = new HashMap<>();
    List<String> values = new ArrayList<>();
    values.add("value1");
    values.add(null);
    invalidEntityOperations.put("key1", values);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    null,
                    null,
                    CONNECTION,
                    invalidEntityOperations,
                    null,
                    null,
                    mockHttpClient,
                    mockCredentialsHelper));

    assertThat(exception)
        .hasMessageThat()
        .contains("Operation for entity 'key1' cannot be null or empty");
  }

  @Test
  public void constructor_entityOperationsListWithEmptyString_throwsException() {
    ImmutableMap<String, List<String>> invalidEntityOperations =
        ImmutableMap.of("entity1", ImmutableList.of("value1", ""));

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    INTEGRATION,
                    ImmutableList.of("trigger1"),
                    CONNECTION,
                    invalidEntityOperations,
                    null,
                    null,
                    mockHttpClient,
                    mockCredentialsHelper));

    assertThat(exception)
        .hasMessageThat()
        .contains("Operation for entity 'entity1' cannot be null or empty");
  }

  @Test
  public void constructor_actionsEmpty_throwsException() {

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    INTEGRATION,
                    ImmutableList.of("trigger1"),
                    CONNECTION,
                    null,
                    ImmutableList.of(),
                    null,
                    mockHttpClient,
                    mockCredentialsHelper));

    assertThat(exception).hasMessageThat().contains("Actions list cannot be empty");
  }

  @Test
  public void constructor_actionsListWithNull_throwsException() {
    List<String> invalidActions = new ArrayList<>();
    invalidActions.add("action1");
    invalidActions.add(null);

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    INTEGRATION,
                    ImmutableList.of("trigger1"),
                    CONNECTION,
                    null,
                    invalidActions,
                    null,
                    mockHttpClient,
                    mockCredentialsHelper));

    assertThat(exception).hasMessageThat().contains("Actions list cannot contain null values");
  }

  @Test
  public void constructor_actionsListWithEmptyString_throwsException() {

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new IntegrationClient(
                    PROJECT,
                    LOCATION,
                    INTEGRATION,
                    ImmutableList.of("trigger1"),
                    CONNECTION,
                    null,
                    ImmutableList.of("action1", ""),
                    null,
                    mockHttpClient,
                    mockCredentialsHelper));

    assertThat(exception).hasMessageThat().contains("Actions list cannot contain empty strings");
  }

  @Test
  public void constructor_validActions_success() {
    ImmutableList<String> validActions = ImmutableList.of("action1", "action2");

    var unused =
        new IntegrationClient(
            PROJECT,
            LOCATION,
            INTEGRATION,
            ImmutableList.of("trigger1"),
            CONNECTION,
            null,
            validActions,
            null,
            mockHttpClient,
            mockCredentialsHelper);
  }

  @Test
  public void constructor_validEntityOperations_success() {
    ImmutableMap<String, List<String>> validEntityOperations =
        ImmutableMap.of("key1", ImmutableList.of("value1", "value2"));

    var unused =
        new IntegrationClient(
            PROJECT,
            LOCATION,
            INTEGRATION,
            ImmutableList.of("trigger1"),
            CONNECTION,
            validEntityOperations,
            null,
            null,
            mockHttpClient,
            mockCredentialsHelper);
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void generateOpenApiSpec_success() throws Exception {
    IntegrationClient client =
        new IntegrationClient(
            PROJECT,
            LOCATION,
            INTEGRATION,
            ImmutableList.of("trigger1"),
            null,
            null,
            null,
            null,
            mockHttpClient,
            mockCredentialsHelper);
    String mockResponse = "{\"openApiSpec\":\"{}\"}";

    when(mockHttpResponse.statusCode()).thenReturn(200);
    when(mockHttpResponse.body()).thenReturn(mockResponse);
    doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

    String result = client.generateOpenApiSpec();

    assertThat(result).isEqualTo(mockResponse);
  }

  @Test
  @SuppressWarnings("MockitoDoSetup")
  public void generateOpenApiSpec_httpError_throwsException() throws Exception {
    IntegrationClient client =
        new IntegrationClient(
            PROJECT,
            LOCATION,
            INTEGRATION,
            null,
            null,
            null,
            null,
            null,
            mockHttpClient,
            mockCredentialsHelper);
    when(mockHttpResponse.statusCode()).thenReturn(404);
    when(mockHttpResponse.body()).thenReturn("Not Found");
    doReturn(mockHttpResponse).when(mockHttpClient).send(any(HttpRequest.class), any());

    Exception exception = assertThrows(Exception.class, client::generateOpenApiSpec);
    assertThat(exception).hasMessageThat().contains("Error fetching OpenAPI spec. Status: 404");
  }

  @Test
  public void getOpenApiSpecForConnection_success() throws Exception {
    IntegrationClient realClient =
        new IntegrationClient(
            PROJECT,
            LOCATION,
            null,
            null,
            CONNECTION,
            ImmutableMap.of("Issue", ImmutableList.of("GET")),
            null,
            null,
            mockHttpClient,
            mockCredentialsHelper);

    IntegrationClient spiedClient = spy(realClient);

    doReturn(mockConnectionsClient).when(spiedClient).createConnectionsClient();
    EntitySchemaAndOperations fakeSchemaData = new EntitySchemaAndOperations();
    fakeSchemaData.schema = ImmutableMap.of("type", "object");
    fakeSchemaData.operations = ImmutableList.of("GET");
    when(mockConnectionsClient.getEntitySchemaAndOperations("Issue")).thenReturn(fakeSchemaData);

    ObjectNode spec = spiedClient.getOpenApiSpecForConnection("MyTool", "Instructions");

    verify(mockConnectionsClient).getEntitySchemaAndOperations("Issue");
    assertThat(spec.at("/paths").isObject()).isTrue();
    assertThat(spec.at("/paths").size()).isEqualTo(1);
    assertThat(spec.at("/components/schemas/get_issue_Request").isObject()).isTrue();
  }

  @Test
  public void getOperationIdFromPathUrl_success() throws Exception {
    IntegrationClient client =
        new IntegrationClient(
            null, null, null, null, null, null, null, null, mockHttpClient, mockCredentialsHelper);
    String openApiSpec =
        "{\"openApiSpec\":"
            + "\"{\\\"paths\\\":{\\\"/my/path\\\":{\\\"post\\\":{\\\"operationId\\\":\\\"my-op-id\\\"}}}}\"}";

    String opId = client.getOperationIdFromPathUrl(openApiSpec, "/my/path");

    assertThat(opId).isEqualTo("my-op-id");
  }

  @Test
  public void getOperationIdFromPathUrl_pathNotFound_throwsException() {
    IntegrationClient client =
        new IntegrationClient(
            null, null, null, null, null, null, null, null, mockHttpClient, mockCredentialsHelper);
    String openApiSpec =
        "{\"openApiSpec\":"
            + "\"{\\\"paths\\\":{\\\"/my/path\\\":{\\\"post\\\":{\\\"operationId\\\":\\\"my-op-id\\\"}}}}\"}";

    Exception e =
        assertThrows(
            Exception.class, () -> client.getOperationIdFromPathUrl(openApiSpec, "/not/found"));
    assertThat(e).hasMessageThat().isEqualTo("Could not find operationId for pathUrl: /not/found");
  }

  @Test
  public void getOperationIdFromPathUrl_invalidOpenApiSpec_throwsException() {
    IntegrationClient client =
        new IntegrationClient(
            null, null, null, null, null, null, null, null, mockHttpClient, mockCredentialsHelper);
    String openApiSpec = "{\"invalidKey\":\"value\"}";

    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> client.getOperationIdFromPathUrl(openApiSpec, "/my/path"));
    assertThat(e).hasMessageThat().contains("Failed to get OpenApiSpec");
  }

  @Test
  public void getOpenApiSpecForConnection_connectionsClientThrowsException_throwsException()
      throws Exception {
    IntegrationClient client =
        new IntegrationClient(
            PROJECT,
            LOCATION,
            null,
            null,
            CONNECTION,
            ImmutableMap.of("Issue", ImmutableList.of("GET")),
            null,
            null,
            mockHttpClient,
            mockCredentialsHelper);

    IntegrationClient spyClient = spy(client);
    doReturn(mockConnectionsClient).when(spyClient).createConnectionsClient();
    when(mockConnectionsClient.getEntitySchemaAndOperations(eq("Issue")))
        .thenThrow(new InterruptedException("Error getting schema"));

    IOException exception =
        assertThrows(
            IOException.class,
            () -> spyClient.getOpenApiSpecForConnection(TOOL_NAME, TOOL_INSTRUCTIONS));

    assertThat(exception)
        .hasMessageThat()
        .contains("Operation was interrupted while getting entity schema");
    assertThat(exception).hasCauseThat().isInstanceOf(InterruptedException.class);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }
}
