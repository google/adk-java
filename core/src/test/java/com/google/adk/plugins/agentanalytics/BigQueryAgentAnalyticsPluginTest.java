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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import com.google.adk.sessions.Session;
import com.google.api.core.ApiFutures;
import com.google.auth.Credentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.storage.v1.AppendRowsResponse;
import com.google.cloud.bigquery.storage.v1.BigQueryWriteClient;
import com.google.cloud.bigquery.storage.v1.StreamWriter;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class BigQueryAgentAnalyticsPluginTest {
  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock private BigQuery mockBigQuery;
  @Mock private StreamWriter mockWriter;
  @Mock private BigQueryWriteClient mockWriteClient;
  @Mock private InvocationContext mockInvocationContext;
  private BaseAgent fakeAgent;

  private BigQueryLoggerConfig config;
  private BigQueryAgentAnalyticsPlugin plugin;
  private Handler mockHandler;

  @Before
  public void setUp() throws Exception {
    fakeAgent = new FakeAgent("agent_name");
    config =
        BigQueryLoggerConfig.builder()
            .setEnabled(true)
            .setProjectId("project")
            .setDatasetId("dataset")
            .setTableName("table")
            .setBatchSize(10)
            .setBatchFlushInterval(Duration.ofSeconds(10))
            .setAutoSchemaUpgrade(false)
            .setCredentials(mock(Credentials.class))
            .setCustomTags(ImmutableMap.of("global_tag", "global_value"))
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
        };

    Session session = Session.builder("session_id").build();
    when(mockInvocationContext.session()).thenReturn(session);
    when(mockInvocationContext.invocationId()).thenReturn("invocation_id");
    when(mockInvocationContext.agent()).thenReturn(fakeAgent);
    when(mockInvocationContext.userId()).thenReturn("user_id");

    Logger logger = Logger.getLogger(BatchProcessor.class.getName());
    mockHandler = mock(Handler.class);
    logger.addHandler(mockHandler);
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
            .setProjectId("test-project")
            .setDatasetId("test-dataset")
            .setTableName("test-table")
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
      assertTrue(
          captor
              .getValue()
              .getMessage()
              .contains("Failed to check or create/upgrade BigQuery table"));
      assertEquals(Level.WARNING, captor.getValue().getLevel());
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
                    root.getVector("event_type").getObject(0).toString(), "USER_MESSAGE")) {
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
    assertEquals("EVENT", row.get("event_type"));
    assertEquals("agent_name", row.get("agent"));
    assertTrue(row.get("attributes").toString().contains("agent_author"));
    assertTrue(row.get("content").toString().contains("event content"));
  }

  @Test
  public void onModelErrorCallback_populatesCorrectFields() throws Exception {
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    when(mockCallbackContext.invocationContext()).thenReturn(mockInvocationContext);
    when(mockCallbackContext.agentName()).thenReturn("agent_in_context");
    LlmRequest.Builder mockLlmRequestBuilder = mock(LlmRequest.Builder.class);
    Throwable error = new RuntimeException("model error message");

    plugin
        .onModelErrorCallback(mockCallbackContext, mockLlmRequestBuilder, error)
        .blockingSubscribe();

    Map<String, Object> row = plugin.batchProcessor.queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("MODEL_ERROR", row.get("event_type"));
    assertEquals("agent_in_context", row.get("agent"));
    assertTrue(row.get("attributes").toString().contains("model error message"));
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
