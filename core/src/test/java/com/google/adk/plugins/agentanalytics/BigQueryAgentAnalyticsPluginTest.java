/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.plugins.agentanalytics;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.Session;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.adk.utils.AgentEnums.AgentOrigin;
import com.google.api.core.ApiFutures;
import com.google.auth.Credentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class BigQueryAgentAnalyticsPluginTest {
  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();
  @Rule public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();

  @Mock private BigQuery mockBigQuery;
  @Mock private StreamWriter mockWriter;
  @Mock private BigQueryWriteClient mockWriteClient;
  @Mock private InvocationContext mockInvocationContext;
  @Captor private ArgumentCaptor<Map<String, String>> labelsCaptor;
  private BaseAgent fakeAgent;

  private BigQueryLoggerConfig config;
  private BigQueryAgentAnalyticsPlugin plugin;
  private Handler mockHandler;
  private Tracer tracer;

  @Before
  public void setUp() throws Exception {
    tracer = openTelemetryRule.getOpenTelemetry().getTracer("test-plugin");
    fakeAgent = new FakeAgent("agent_name");
    config =
        BigQueryLoggerConfig.builder()
            .enabled(true)
            .projectId("project")
            .datasetId("dataset")
            .tableName("table")
            .batchSize(10)
            .batchFlushInterval(Duration.ofSeconds(10))
            .autoSchemaUpgrade(false)
            .credentials(mock(Credentials.class))
            .customTags(ImmutableMap.of("global_tag", "global_value"))
            .build();

    when(mockBigQuery.getOptions())
        .thenReturn(BigQueryOptions.newBuilder().setProjectId("test-project").build());
    when(mockBigQuery.getTable(any(TableId.class))).thenReturn(mock(Table.class));
    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenReturn(ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance()));

    plugin =
        new BigQueryAgentAnalyticsPlugin(config, mockBigQuery) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter(BigQueryLoggerConfig config) {
            return mockWriter;
          }

          @Override
          protected TraceManager createTraceManager() {
            return new TraceManager(tracer);
          }
        };

    Session session = Session.builder("session_id").appName("test_app").userId("test_user").build();
    when(mockInvocationContext.session()).thenReturn(session);
    when(mockInvocationContext.invocationId()).thenReturn("invocation_id");
    when(mockInvocationContext.agent()).thenReturn(fakeAgent);
    when(mockInvocationContext.callbackContextData()).thenReturn(new ConcurrentHashMap<>());
    when(mockInvocationContext.userId()).thenReturn("user_id");

    Logger logger = Logger.getLogger(BatchProcessor.class.getName());
    mockHandler = mock(Handler.class);
    logger.addHandler(mockHandler);
  }

  @After
  public void tearDown() {
    Logger logger = Logger.getLogger(BatchProcessor.class.getName());
    if (mockHandler != null) {
      logger.removeHandler(mockHandler);
    }
  }

  @Test
  public void onUserMessageCallback_appendsToWriter() throws Exception {
    Content content = Content.builder().build();

    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();
    plugin.batchProcessor.flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void beforeRunCallback_appendsToWriter() throws Exception {
    plugin.beforeRunCallback(mockInvocationContext).blockingSubscribe();
    plugin.batchProcessor.flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void afterRunCallback_flushesAndAppends() throws Exception {
    plugin.afterRunCallback(mockInvocationContext).blockingSubscribe();
    plugin.batchProcessor.flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void getStreamName_returnsCorrectFormat() {
    BigQueryLoggerConfig config =
        BigQueryLoggerConfig.builder()
            .projectId("test-project")
            .datasetId("test-dataset")
            .tableName("test-table")
            .build();

    String streamName = plugin.getStreamName(config);

    assertEquals(
        "projects/test-project/datasets/test-dataset/tables/test-table/streams/_default",
        streamName);
  }

  @Test
  public void formatContentParts_populatesCorrectFields() {
    Content content = Content.fromParts(Part.fromText("hello"));
    ArrayNode nodes = JsonFormatter.formatContentParts(Optional.of(content), 100);
    assertEquals(1, nodes.size());
    ObjectNode node = (ObjectNode) nodes.get(0);
    assertEquals(0, node.get("part_index").asInt());
    assertEquals("INLINE", node.get("storage_mode").asText());
    assertEquals("hello", node.get("text").asText());
    assertEquals("text/plain", node.get("mime_type").asText());
  }

  @Test
  public void arrowSchema_hasJsonMetadata() {
    Schema schema = BigQuerySchema.getArrowSchema();
    Field contentField = schema.findField("content");
    assertNotNull(contentField);
    assertEquals("google:sqlType:json", contentField.getMetadata().get("ARROW:extension:name"));
  }

  @Test
  public void onUserMessageCallback_handlesTableCreationFailure() throws Exception {
    Logger logger = Logger.getLogger(BigQueryAgentAnalyticsPlugin.class.getName());
    Handler mockHandler = mock(Handler.class);
    logger.addHandler(mockHandler);
    try {
      when(mockBigQuery.getTable(any(TableId.class)))
          .thenThrow(new RuntimeException("Table check failed"));
      Content content = Content.builder().build();

      // Should not throw exception
      plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

      plugin.batchProcessor.flush();

      ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
      verify(mockHandler, atLeastOnce()).publish(captor.capture());
      boolean found =
          captor.getAllValues().stream()
              .anyMatch(
                  record ->
                      record
                              .getMessage()
                              .contains("Failed to check or create/upgrade BigQuery table")
                          && Objects.equals(record.getLevel(), Level.WARNING));
      assertTrue("Should have logged table creation failure warning", found);
    } finally {
      logger.removeHandler(mockHandler);
    }
  }

  @Test
  public void onUserMessageCallback_handlesAppendFailure() throws Exception {
    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenReturn(ApiFutures.immediateFailedFuture(new RuntimeException("Append failed")));
    Content content = Content.builder().build();

    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    // Flush should handle the failed future from writer.append()
    plugin.batchProcessor.flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
    ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
    verify(mockHandler, atLeastOnce()).publish(captor.capture());
    assertTrue(captor.getValue().getMessage().contains("Failed to write batch to BigQuery"));
    assertEquals(Level.SEVERE, captor.getValue().getLevel());
  }

  @Test
  public void ensureTableExists_calledOnlyOnce() throws Exception {
    Content content = Content.builder().build();

    // Multiple calls to logEvent via different callbacks
    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();
    plugin.beforeRunCallback(mockInvocationContext).blockingSubscribe();
    plugin.afterRunCallback(mockInvocationContext).blockingSubscribe();

    // Verify getting table was only done once. Using fully qualified name to avoid ambiguity.
    verify(mockBigQuery).getTable(any(TableId.class));
  }

  @Test
  public void arrowSchema_handlesNestedFields() {
    Schema schema = BigQuerySchema.getArrowSchema();
    Field contentPartsField = schema.findField("content_parts");
    assertNotNull(contentPartsField);
    // Repeated struct becomes a List of Structs
    assertTrue(contentPartsField.getType() instanceof ArrowType.List);

    Field element = contentPartsField.getChildren().get(0);
    assertEquals("element", element.getName());

    // Check object_ref which is a nested STRUCT
    Field objectRef =
        element.getChildren().stream()
            .filter(f -> f.getName().equals("object_ref"))
            .findFirst()
            .orElse(null);
    assertNotNull(objectRef);
    assertTrue(objectRef.getType() instanceof ArrowType.Struct);
    assertFalse(objectRef.getChildren().isEmpty());
  }

  @Test
  public void arrowSchema_handlesFieldNullability() {
    Schema schema = BigQuerySchema.getArrowSchema();

    // timestamp is REQUIRED in BigQuerySchema.getEventsSchema()
    Field timestampField = schema.findField("timestamp");
    assertNotNull(timestampField);
    assertFalse(timestampField.isNullable());

    // event_type is NULLABLE in BigQuerySchema.getEventsSchema()
    Field eventTypeField = schema.findField("event_type");
    assertNotNull(eventTypeField);
    assertTrue(eventTypeField.isNullable());
  }

  @Test
  public void logEvent_populatesCommonFields() throws Exception {
    final boolean[] checksPassed = {false};
    final String[] failureMessage = {null};

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenAnswer(
            invocation -> {
              ArrowRecordBatch recordedBatch = invocation.getArgument(0);
              Schema schema = BigQuerySchema.getArrowSchema();
              try (VectorSchemaRoot root =
                  VectorSchemaRoot.create(schema, plugin.batchProcessor.allocator)) {
                VectorLoader loader = new VectorLoader(root);
                loader.load(recordedBatch);

                if (root.getRowCount() != 1) {
                  failureMessage[0] = "Expected 1 row, got " + root.getRowCount();
                } else if (!Objects.equals(
                    root.getVector("event_type").getObject(0).toString(),
                    "USER_MESSAGE_RECEIVED")) {
                  failureMessage[0] =
                      "Wrong event_type: " + root.getVector("event_type").getObject(0);
                } else if (!root.getVector("agent").getObject(0).toString().equals("agent_name")) {
                  failureMessage[0] = "Wrong agent: " + root.getVector("agent").getObject(0);
                } else if (!root.getVector("session_id")
                    .getObject(0)
                    .toString()
                    .equals("session_id")) {
                  failureMessage[0] =
                      "Wrong session_id: " + root.getVector("session_id").getObject(0);
                } else if (!root.getVector("invocation_id")
                    .getObject(0)
                    .toString()
                    .equals("invocation_id")) {
                  failureMessage[0] =
                      "Wrong invocation_id: " + root.getVector("invocation_id").getObject(0);
                } else if (!root.getVector("user_id").getObject(0).toString().equals("user_id")) {
                  failureMessage[0] = "Wrong user_id: " + root.getVector("user_id").getObject(0);
                } else if (((TimeStampMicroTZVector) root.getVector("timestamp")).get(0) <= 0) {
                  failureMessage[0] = "Timestamp not populated";
                } else if (!Objects.equals(root.getVector("is_truncated").getObject(0), false)) {
                  failureMessage[0] =
                      "Wrong is_truncated: " + root.getVector("is_truncated").getObject(0);
                } else {
                  // Check content and content_parts
                  String contentJson = root.getVector("content").getObject(0).toString();
                  if (!contentJson.contains("test message")) {
                    failureMessage[0] = "Wrong content: " + contentJson;
                  } else {
                    ListVector contentPartsVector = (ListVector) root.getVector("content_parts");
                    if (((List<?>) contentPartsVector.getObject(0)).isEmpty()) {
                      failureMessage[0] = "content_parts is empty";
                    } else {
                      // Check attributes
                      String attributesJson = root.getVector("attributes").getObject(0).toString();
                      if (!attributesJson.contains("global_tag")
                          || !attributesJson.contains("global_value")) {
                        failureMessage[0] = "Wrong attributes: " + attributesJson;
                      } else {
                        checksPassed[0] = true;
                      }
                    }
                  }
                }
              } catch (RuntimeException e) {
                failureMessage[0] = "Exception during inspection: " + e.getMessage();
              }
              return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
            });

    Content content = Content.fromParts(Part.fromText("test message"));
    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();
    plugin.batchProcessor.flush();

    assertTrue(failureMessage[0], checksPassed[0]);
  }

  @Test
  public void logEvent_populatesTraceDetails() throws Exception {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String spanId = "00f067aa0ba902b7";

    SpanContext mockSpanContext = mock(SpanContext.class);
    when(mockSpanContext.isValid()).thenReturn(true);
    when(mockSpanContext.getTraceId()).thenReturn(traceId);
    when(mockSpanContext.getSpanId()).thenReturn(spanId);

    Span mockSpan = Span.wrap(mockSpanContext);

    try (Scope scope = mockSpan.makeCurrent()) {
      plugin.traceManager.attachCurrentSpan();

      Content content = Content.builder().build();
      plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

      Map<String, Object> row = plugin.batchProcessor.queue.poll();
      assertNotNull("Row not found in queue", row);
      assertEquals(traceId, row.get("trace_id"));
      assertEquals(spanId, row.get("span_id"));
    }
  }

  @Test
  public void complexType_appendsToWriter() throws Exception {
    Part part = Part.fromText("test text");
    Content content = Content.fromParts(part);
    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    plugin.batchProcessor.flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void onEventCallback_populatesCorrectFields() throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .content(Content.fromParts(Part.fromText("event content")))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();

    Map<String, Object> row = plugin.batchProcessor.queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("STATE_DELTA", row.get("event_type"));
    assertEquals("agent_name", row.get("agent"));
    ObjectNode attributes = (ObjectNode) row.get("attributes");
    assertEquals("agent_author", attributes.get("author").asText());
    assertTrue(row.get("content").toString().contains("event content"));
    assertEquals(false, row.get("is_truncated"));
  }

  @Test
  public void onModelErrorCallback_populatesCorrectFields() throws Exception {
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    when(mockCallbackContext.invocationContext()).thenReturn(mockInvocationContext);
    LlmRequest.Builder mockLlmRequestBuilder = mock(LlmRequest.Builder.class);
    Throwable error = new RuntimeException("model error message");

    plugin.traceManager.pushSpan("llm_request");
    plugin
        .onModelErrorCallback(mockCallbackContext, mockLlmRequestBuilder, error)
        .blockingSubscribe();

    Map<String, Object> row = plugin.batchProcessor.queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("LLM_ERROR", row.get("event_type"));
    assertEquals("agent_name", row.get("agent"));
    assertEquals("ERROR", row.get("status"));
    assertEquals("model error message", row.get("error_message"));
    assertNotNull(row.get("latency_ms"));
    assertEquals(false, row.get("is_truncated"));
  }

  @Test
  public void afterModelCallback_populatesCorrectFields() throws Exception {
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    when(mockCallbackContext.invocationContext()).thenReturn(mockInvocationContext);

    GenerateContentResponseUsageMetadata usage =
        GenerateContentResponseUsageMetadata.builder()
            .promptTokenCount(10)
            .candidatesTokenCount(20)
            .totalTokenCount(30)
            .build();

    GenerateContentResponse response =
        GenerateContentResponse.builder()
            .modelVersion("v1")
            .usageMetadata(usage)
            .candidates(
                ImmutableList.of(
                    Candidate.builder()
                        .content(Content.fromParts(Part.fromText("llm response")))
                        .build()))
            .build();

    LlmResponse adkResponse = LlmResponse.create(response);

    Span parentSpan = tracer.spanBuilder("parent_request").startSpan();
    Span ambientSpan =
        tracer.spanBuilder("ambient").setParent(Context.current().with(parentSpan)).startSpan();
    // Set valid ambient span context
    try (Scope scope = ambientSpan.makeCurrent()) {
      plugin.traceManager.pushSpan("parent_request");
      plugin.traceManager.pushSpan("llm_request");
      plugin.afterModelCallback(mockCallbackContext, adkResponse).blockingSubscribe();
    } finally {
      ambientSpan.end();
    }
    Map<String, Object> row = plugin.batchProcessor.queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("LLM_RESPONSE", row.get("event_type"));
    ObjectNode contentMap = (ObjectNode) row.get("content");
    assertNotNull(contentMap.get("response"));
    ObjectNode usageMap = (ObjectNode) contentMap.get("usage");
    assertEquals(10, usageMap.get("prompt").asInt());

    ObjectNode attributes = (ObjectNode) row.get("attributes");
    assertEquals("v1", attributes.get("model_version").asText());
    ObjectNode usageAttr = (ObjectNode) attributes.get("usage_metadata");
    assertEquals(10, usageAttr.get("prompt").asInt());
    assertEquals(false, row.get("is_truncated"));
    assertNotNull(row.get("parent_span_id"));
    ObjectNode latencyMs = (ObjectNode) row.get("latency_ms");
    assertNotNull("latency_ms should not be null", latencyMs);
    assertTrue(
        "latency_ms should contain time_to_first_token_ms",
        latencyMs.has("time_to_first_token_ms"));
  }

  @Test
  public void afterToolCallback_populatesCorrectFields() throws Exception {
    ToolContext mockToolContext = mock(ToolContext.class);
    when(mockToolContext.invocationContext()).thenReturn(mockInvocationContext);

    BaseTool mockTool = mock(BaseTool.class);
    when(mockTool.name()).thenReturn("test_tool");

    ImmutableMap<String, Object> toolArgs = ImmutableMap.of("arg1", "value1");
    ImmutableMap<String, Object> result = ImmutableMap.of("res1", "value2");

    plugin.traceManager.pushSpan("tool_request");
    plugin.afterToolCallback(mockTool, toolArgs, mockToolContext, result).blockingSubscribe();

    Map<String, Object> row = plugin.batchProcessor.queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("TOOL_COMPLETED", row.get("event_type"));
    assertEquals("agent_name", row.get("agent"));
    ObjectNode contentMap = (ObjectNode) row.get("content");
    assertEquals("test_tool", contentMap.get("tool").asText());
    assertNotNull(contentMap.get("result"));
    assertEquals("UNKNOWN", contentMap.get("tool_origin").asText());
    assertEquals(false, row.get("is_truncated"));
    assertNotNull(row.get("latency_ms"));
  }

  @Test
  public void afterToolCallback_identifiesA2AOrigin() throws Exception {
    ToolContext mockToolContext = mock(ToolContext.class);
    when(mockToolContext.invocationContext()).thenReturn(mockInvocationContext);

    BaseAgent a2aAgent =
        new FakeAgent("a2a_agent") {
          @Override
          public AgentOrigin toolOrigin() {
            return AgentOrigin.A2A;
          }
        };

    AgentTool a2aTool = AgentTool.create(a2aAgent);

    plugin.traceManager.pushSpan("tool_request");
    plugin
        .afterToolCallback(a2aTool, ImmutableMap.of(), mockToolContext, ImmutableMap.of())
        .blockingSubscribe();

    Map<String, Object> row = plugin.batchProcessor.queue.poll();
    assertNotNull(row);
    ObjectNode contentMap = (ObjectNode) row.get("content");
    assertEquals("A2A", contentMap.get("tool_origin").asText());
  }

  @Test
  public void logEvent_includesSessionMetadata_whenEnabled() throws Exception {
    // Config default has logSessionMetadata(true)
    Content content = Content.fromParts(Part.fromText("test message"));
    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    Map<String, Object> row = plugin.batchProcessor.queue.poll();
    assertNotNull(row);
    ObjectNode attributes = (ObjectNode) row.get("attributes");
    assertTrue("attributes should contain session_metadata", attributes.has("session_metadata"));
    ObjectNode sessionMeta = (ObjectNode) attributes.get("session_metadata");
    assertEquals("session_id", sessionMeta.get("session_id").asText());
    assertEquals("test_user", sessionMeta.get("user_id").asText());
    assertEquals("test_app", sessionMeta.get("app_name").asText());
  }

  @Test
  public void logEvent_excludesSessionMetadata_whenDisabled() throws Exception {
    BigQueryLoggerConfig disabledConfig = config.toBuilder().logSessionMetadata(false).build();
    BigQueryAgentAnalyticsPlugin disabledPlugin =
        new BigQueryAgentAnalyticsPlugin(disabledConfig, mockBigQuery) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter(BigQueryLoggerConfig config) {
            return mockWriter;
          }

          @Override
          protected TraceManager createTraceManager() {
            return new TraceManager(GlobalOpenTelemetry.getTracer("test-plugin-disabled"));
          }
        };

    Content content = Content.fromParts(Part.fromText("test message"));
    disabledPlugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    Map<String, Object> row = disabledPlugin.batchProcessor.queue.poll();
    assertNotNull(row);
    ObjectNode attributes = (ObjectNode) row.get("attributes");
    assertFalse(
        "attributes should not contain session_metadata", attributes.has("session_metadata"));
  }

  @Test
  public void maybeUpgradeSchema_addsNewTopLevelField() throws Exception {
    Table mockTable = mock(Table.class);
    when(mockTable.getTableId()).thenReturn(TableId.of("project", "dataset", "table"));
    when(mockTable.getLabels()).thenReturn(ImmutableMap.of());

    // Initial schema missing one field, e.g., 'is_truncated'
    ImmutableList<com.google.cloud.bigquery.Field> initialFields =
        BigQuerySchema.getEventsSchema().getFields().stream()
            .filter(f -> !f.getName().equals("is_truncated"))
            .collect(toImmutableList());
    StandardTableDefinition tableDefinition =
        StandardTableDefinition.newBuilder()
            .setSchema(com.google.cloud.bigquery.Schema.of(initialFields))
            .build();
    when(mockTable.getDefinition()).thenReturn(tableDefinition);

    Table.Builder mockTableBuilder = mock(Table.Builder.class);
    when(mockTable.toBuilder()).thenReturn(mockTableBuilder);
    when(mockTableBuilder.setDefinition(any(TableDefinition.class))).thenReturn(mockTableBuilder);
    when(mockTableBuilder.setLabels(anyMap())).thenReturn(mockTableBuilder);
    when(mockTableBuilder.build()).thenReturn(mockTable);

    BigQueryUtils.maybeUpgradeSchema(mockBigQuery, mockTable);

    ArgumentCaptor<StandardTableDefinition> definitionCaptor =
        ArgumentCaptor.forClass(StandardTableDefinition.class);
    verify(mockTableBuilder).setDefinition(definitionCaptor.capture());
    com.google.cloud.bigquery.Schema updatedSchema = definitionCaptor.getValue().getSchema();
    assertNotNull(updatedSchema.getFields().get("is_truncated"));

    verify(mockTableBuilder).setLabels(labelsCaptor.capture());
    assertEquals(
        BigQuerySchema.SCHEMA_VERSION,
        labelsCaptor.getValue().get(BigQuerySchema.SCHEMA_VERSION_LABEL_KEY));

    verify(mockBigQuery).update(any(Table.class));
  }

  @Test
  public void maybeUpgradeSchema_addsNewNestedField() throws Exception {
    Table mockTable = mock(Table.class);
    when(mockTable.getTableId()).thenReturn(TableId.of("project", "dataset", "table"));
    when(mockTable.getLabels()).thenReturn(ImmutableMap.of());

    // Initial schema missing 'storage_mode' in 'content_parts'
    ImmutableList<com.google.cloud.bigquery.Field> initialFields =
        BigQuerySchema.getEventsSchema().getFields().stream()
            .map(
                f -> {
                  if (f.getName().equals("content_parts")) {
                    ImmutableList<com.google.cloud.bigquery.Field> subFields =
                        f.getSubFields().stream()
                            .filter(sf -> !sf.getName().equals("storage_mode"))
                            .collect(toImmutableList());
                    return f.toBuilder()
                        .setType(StandardSQLTypeName.STRUCT, FieldList.of(subFields))
                        .build();
                  }
                  return f;
                })
            .collect(toImmutableList());

    StandardTableDefinition tableDefinition =
        StandardTableDefinition.newBuilder()
            .setSchema(com.google.cloud.bigquery.Schema.of(initialFields))
            .build();
    when(mockTable.getDefinition()).thenReturn(tableDefinition);

    Table.Builder mockTableBuilder = mock(Table.Builder.class);
    when(mockTable.toBuilder()).thenReturn(mockTableBuilder);
    when(mockTableBuilder.setDefinition(any(TableDefinition.class))).thenReturn(mockTableBuilder);
    when(mockTableBuilder.setLabels(anyMap())).thenReturn(mockTableBuilder);
    when(mockTableBuilder.build()).thenReturn(mockTable);

    BigQueryUtils.maybeUpgradeSchema(mockBigQuery, mockTable);

    ArgumentCaptor<StandardTableDefinition> definitionCaptor =
        ArgumentCaptor.forClass(StandardTableDefinition.class);
    verify(mockTableBuilder).setDefinition(definitionCaptor.capture());
    com.google.cloud.bigquery.Field contentParts =
        definitionCaptor.getValue().getSchema().getFields().get("content_parts");
    assertNotNull(contentParts.getSubFields().get("storage_mode"));

    verify(mockBigQuery).update(any(Table.class));
  }

  @Test
  public void createAnalyticsViews_executesQueries() throws Exception {
    BigQueryUtils.createAnalyticsViews(mockBigQuery, config);

    // Verify a few specific views are created
    verify(mockBigQuery, atLeastOnce()).query(any(QueryJobConfiguration.class));

    ArgumentCaptor<QueryJobConfiguration> captor =
        ArgumentCaptor.forClass(QueryJobConfiguration.class);
    verify(mockBigQuery, atLeastOnce()).query(captor.capture());

    ImmutableList<String> queries =
        captor.getAllValues().stream()
            .map(QueryJobConfiguration::getQuery)
            .collect(toImmutableList());

    assertTrue(
        queries.stream()
            .anyMatch(
                q ->
                    q.contains(
                        "CREATE OR REPLACE VIEW `project.dataset.v_user_message_received`")));
    assertTrue(
        queries.stream()
            .anyMatch(q -> q.contains("CREATE OR REPLACE VIEW `project.dataset.v_llm_request`")));
    assertTrue(
        queries.stream()
            .anyMatch(q -> q.contains("CREATE OR REPLACE VIEW `project.dataset.v_llm_response`")));
  }

  private static class FakeAgent extends BaseAgent {
    FakeAgent(String name) {
      super(name, "description", null, null, null);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
      return Flowable.empty();
    }
  }
}
