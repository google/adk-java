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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
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
import com.google.cloud.bigquery.Field.Mode;
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
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
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
  private PluginState state;
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

    state =
        new PluginState(config) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }
        };

    plugin = new BigQueryAgentAnalyticsPlugin(config, mockBigQuery, state);

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
    state.getBatchProcessor("invocation_id").flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void onUserMessageCallback_ensuresInvocationSpan() throws Exception {
    Content content = Content.builder().build();

    // Verify initial state
    assertTrue(
        state.getTraceManager("invocation_id").getCurrentSpanId(mockInvocationContext).isEmpty());

    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    // Verify that ensureInvocationSpan was called and created a span
    assertTrue(
        state.getTraceManager("invocation_id").getCurrentSpanId(mockInvocationContext).isPresent());
  }

  @Test
  public void beforeRunCallback_appendsToWriter() throws Exception {
    plugin.beforeRunCallback(mockInvocationContext).blockingSubscribe();
    state.getBatchProcessor("invocation_id").flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void beforeRunCallback_ensuresInvocationSpan() throws Exception {
    // Verify initial state
    assertTrue(
        state.getTraceManager("invocation_id").getCurrentSpanId(mockInvocationContext).isEmpty());

    plugin.beforeRunCallback(mockInvocationContext).blockingSubscribe();

    // Verify that ensureInvocationSpan was called and created a span
    assertTrue(
        state.getTraceManager("invocation_id").getCurrentSpanId(mockInvocationContext).isPresent());
  }

  @Test
  public void beforeRunCallback_addPendingTask() throws Exception {
    final boolean[] addPendingTaskCalled = {false};
    PluginState customState =
        new PluginState(config) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }

          @Override
          void addPendingTask(String invocationId, CompletableFuture<Void> task) {
            super.addPendingTask(invocationId, task);
            addPendingTaskCalled[0] = true;
          }
        };
    BigQueryAgentAnalyticsPlugin customPlugin =
        new BigQueryAgentAnalyticsPlugin(config, mockBigQuery, customState);

    customPlugin.beforeRunCallback(mockInvocationContext).blockingSubscribe();

    assertTrue("addPendingTask should have been called", addPendingTaskCalled[0]);
  }

  @Test
  public void afterRunCallback_waitsForPendingTasks() throws Exception {
    CompletableFuture<Void> pendingTask = new CompletableFuture<>();
    String invocationId = "invocation_id";

    // Manually add a pending task to the state
    state.addPendingTask(invocationId, pendingTask);

    // Complete the task after a short delay
    var unused =
        Executors.newSingleThreadScheduledExecutor()
            .schedule(() -> pendingTask.complete(null), 100, MILLISECONDS);

    // afterRunCallback should wait for the pending task
    plugin.afterRunCallback(mockInvocationContext).blockingSubscribe();

    assertTrue("Pending task should be completed after afterRunCallback", pendingTask.isDone());
  }

  @Test
  public void afterRunCallback_flushesAndAppends() throws Exception {
    plugin.beforeRunCallback(mockInvocationContext).blockingSubscribe();
    plugin.afterRunCallback(mockInvocationContext).blockingSubscribe();

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

    String streamName = state.getStreamName(config);

    assertEquals(
        "projects/test-project/datasets/test-dataset/tables/test-table/streams/_default",
        streamName);
  }

  @Test
  public void formatContentParts_populatesCorrectFields() {
    Content content = Content.fromParts(Part.fromText("hello"));
    ArrayNode nodes = state.getParser().formatContentParts(Optional.of(content));

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

      state.getBatchProcessor("invocation_id").flush();

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
    state.getBatchProcessor("invocation_id").flush();

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
  public void ensureTableExists_retriesAfterFailure() throws Exception {
    when(mockBigQuery.getTable(any(TableId.class)))
        .thenThrow(new RuntimeException("Table check failed"));
    Content content = Content.builder().build();

    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();
    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    // A failed bootstrap must leave the table un-ensured so it is retried on the next event, rather
    // than being masked as ready. With retry, getTable is invoked once per event.
    verify(mockBigQuery, times(2)).getTable(any(TableId.class));
  }

  @Test
  public void afterAgentCallback_stampsInternalExecutionTreeSpanIds() throws Exception {
    CallbackContext callbackContext = mock(CallbackContext.class);
    when(callbackContext.invocationContext()).thenReturn(mockInvocationContext);

    // Establish the invocation-level span, then push a child agent span.
    plugin
        .onUserMessageCallback(mockInvocationContext, Content.builder().build())
        .blockingSubscribe();
    plugin.beforeAgentCallback(fakeAgent, callbackContext).blockingSubscribe();

    TraceManager.SpanIds current =
        state.getTraceManager("invocation_id").getCurrentSpanAndParent(mockInvocationContext);
    String agentSpanId = current.spanId().orElseThrow();
    String invocationSpanId = current.parentSpanId().orElseThrow();

    // Completing the agent pops the agent span and must stamp the row from the internal execution
    // tree: span_id = the popped agent span, parent_span_id = the enclosing invocation span.
    plugin.afterAgentCallback(fakeAgent, callbackContext).blockingSubscribe();

    Map<String, Object> completedRow = null;
    Map<String, Object> row;
    while ((row = state.getBatchProcessor("invocation_id").queue.poll()) != null) {
      if (Objects.equals(row.get("event_type"), "AGENT_COMPLETED")) {
        completedRow = row;
      }
    }
    assertNotNull("AGENT_COMPLETED row not found", completedRow);
    assertEquals(agentSpanId, completedRow.get("span_id"));
    assertEquals(invocationSpanId, completedRow.get("parent_span_id"));
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
                  VectorSchemaRoot.create(
                      schema, state.getBatchProcessor("invocation_id").allocator)) {
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
    state.getBatchProcessor("invocation_id").flush();

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
      state.getTraceManager("invocation_id").attachCurrentSpan(mockInvocationContext);

      Content content = Content.builder().build();
      plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

      Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
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

    state.getBatchProcessor("invocation_id").flush();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void onEventCallback_populatesCorrectFields() throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .actions(EventActions.builder().stateDelta(ImmutableMap.of("key", "new_value")).build())
            .content(Content.fromParts(Part.fromText("event content")))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();

    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("STATE_DELTA", row.get("event_type"));
    assertEquals("agent_name", row.get("agent"));
    ObjectNode attributes = (ObjectNode) row.get("attributes");
    assertEquals("agent_author", attributes.get("author").asText());
    assertEquals("new_value", attributes.get("state_delta").get("key").asText());
    assertTrue(row.get("content").toString().contains("event content"));
    assertEquals(false, row.get("is_truncated"));
  }

  @Test
  public void onEventCallback_noCurrentAgent_fallsBackToEventAuthor() throws Exception {
    // Workflow-driven callbacks may have no current agent; the "agent" column must fall back to the
    // event author rather than the "unknown" sentinel.
    when(mockInvocationContext.agent()).thenReturn(null);
    Event event =
        Event.builder()
            .author("agent_author")
            .actions(EventActions.builder().stateDelta(ImmutableMap.of("key", "new_value")).build())
            .content(Content.fromParts(Part.fromText("event content")))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();

    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("STATE_DELTA", row.get("event_type"));
    assertEquals("agent_author", row.get("agent"));
  }

  @Test
  public void onEventCallback_emptyAuthorNoCurrentAgent_fallsBackToUnknownSentinel()
      throws Exception {
    // An empty author is not a usable fallback: withFallbackAgent guards on
    // `author != null && !author.isEmpty()`, so fallbackAgentName stays unset and resolveAgentName
    // yields the "unknown" sentinel rather than an empty agent name. Pins the `&&` against a
    // `||`-mutation (go/mutation-testing), which would stamp "" as the agent for empty-author
    // events.
    when(mockInvocationContext.agent()).thenReturn(null);
    Event event =
        Event.builder()
            .author("")
            .actions(EventActions.builder().stateDelta(ImmutableMap.of("key", "new_value")).build())
            .content(Content.fromParts(Part.fromText("event content")))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();

    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("STATE_DELTA", row.get("event_type"));
    assertEquals("unknown", row.get("agent"));
  }

  @Test
  public void onEventCallback_nullAuthorNoCurrentAgent_fallsBackToUnknownSentinelWithoutNpe()
      throws Exception {
    // A null author must be short-circuited by the `author != null` half of withFallbackAgent's
    // guard so `author.isEmpty()` is never dereferenced. Exercised via the AGENT_RESPONSE path
    // (whose extraAttributes tolerate a null author, unlike the STATE_DELTA map). Pins the `&&`
    // against a `||`-mutation (go/mutation-testing), which would NPE on null authors.
    when(mockInvocationContext.agent()).thenReturn(null);
    Event event =
        Event.builder()
            .id("evt-id")
            .content(Content.fromParts(Part.fromText("agent final answer")))
            .build();
    assertNull("Precondition: author must be null for this test", event.author());

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    CompletableFuture.allOf(
            state
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();

    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("AGENT_RESPONSE row not found in queue", row);
    assertEquals("AGENT_RESPONSE", row.get("event_type"));
    assertEquals("unknown", row.get("agent"));
  }

  @Test
  public void onEventCallback_emptyStateDelta_doesNotEmitStateDelta() throws Exception {
    Event event = Event.builder().author("agent_author").build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();

    assertNull(
        "No STATE_DELTA row should be emitted for an empty state delta",
        state.getBatchProcessor("invocation_id").queue.poll());
  }

  @Test
  public void onEventCallback_withA2AMetadata_emitsA2AInteraction() throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .customMetadata(
                ImmutableList.of(
                    CustomMetadata.builder().key("a2a:task_id").stringValue("task-123").build(),
                    CustomMetadata.builder()
                        .key("a2a:response")
                        .stringValue("a2a_payload")
                        .build()))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    CompletableFuture.allOf(
            state
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();

    Map<String, Object> a2aRow = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("A2A_INTERACTION row not found in queue", a2aRow);
    assertEquals("A2A_INTERACTION", a2aRow.get("event_type"));
    assertEquals("agent_name", a2aRow.get("agent"));

    // Assert the stored content is a scalar containing the A2A response
    JsonNode contentNode = (JsonNode) a2aRow.get("content");
    assertNotNull("A2A response content should not be null", contentNode);
    assertTrue(contentNode.isTextual());
    assertEquals("a2a_payload", contentNode.asText());

    ObjectNode attributes = (ObjectNode) a2aRow.get("attributes");
    ObjectNode a2aMetadata = (ObjectNode) attributes.get("a2a_metadata");

    // Assert keys present and absent in a2a_metadata
    assertNotNull("a2a_metadata should not be null", a2aMetadata);
    assertEquals("task-123", a2aMetadata.get("a2a:task_id").asText());
    assertFalse(
        "a2a:response should be excluded from a2a_metadata to avoid duplication",
        a2aMetadata.has("a2a:response"));
  }

  @Test
  public void onEventCallback_agentResponse_emitsAgentResponse() throws Exception {
    Event event =
        Event.builder()
            .id("evt-id")
            .author("agent_author")
            .branch("branch-val")
            .content(Content.fromParts(Part.fromText("agent final answer")))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    CompletableFuture.allOf(
            state
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();

    Map<String, Object> agentResponseRow = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("AGENT_RESPONSE row not found in queue", agentResponseRow);
    assertEquals("AGENT_RESPONSE", agentResponseRow.get("event_type"));
    assertEquals("agent_name", agentResponseRow.get("agent"));

    // Assert that the stored content actually has a scalar at $.text_summary
    JsonNode contentNode = (JsonNode) agentResponseRow.get("content");
    assertTrue("content should contain 'text_summary'", contentNode.has("text_summary"));
    assertEquals("agent final answer", contentNode.get("text_summary").asText());

    ObjectNode attributes = (ObjectNode) agentResponseRow.get("attributes");
    assertEquals("evt-id", attributes.get("source_event_id").asText());
    assertEquals("agent_author", attributes.get("source_event_author").asText());
    assertEquals("branch-val", attributes.get("source_event_branch").asText());
  }

  @Test
  public void onEventCallback_skipSummarizationAndFunctionCall_doesNotEmitAgentResponse()
      throws Exception {
    Event event =
        Event.builder()
            .id("evt-id")
            .author("agent_author")
            .branch("branch-val")
            .actions(EventActions.builder().skipSummarization(true).build())
            .content(
                Content.builder()
                    .parts(
                        Part.fromText("agent final answer"),
                        Part.builder()
                            .functionCall(FunctionCall.builder().name("my_tool").build())
                            .build())
                    .build())
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    CompletableFuture.allOf(
            state
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();

    Map<String, Object> nextRow = state.getBatchProcessor("invocation_id").queue.poll();
    assertNull("No AGENT_RESPONSE row should be emitted", nextRow);
  }

  @Test
  public void onEventCallback_longRunningToolIdsPresent_doesNotEmitAgentResponse()
      throws Exception {
    Event event =
        Event.builder()
            .id("evt-id")
            .author("agent_author")
            .branch("branch-val")
            .actions(EventActions.builder().skipSummarization(true).build())
            .longRunningToolIds(ImmutableSet.of("long_running_tool_id"))
            .content(Content.builder().parts(Part.fromText("agent final answer")).build())
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    CompletableFuture.allOf(
            state
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();

    Map<String, Object> nextRow = state.getBatchProcessor("invocation_id").queue.poll();
    assertNull("No AGENT_RESPONSE row should be emitted", nextRow);
  }

  @Test
  public void onEventCallback_withA2ARequestOnlyMetadata_emitsA2AInteraction() throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .customMetadata(
                ImmutableList.of(
                    CustomMetadata.builder().key("a2a:task_id").stringValue("task-456").build(),
                    CustomMetadata.builder().key("a2a:context_id").stringValue("ctx-789").build(),
                    CustomMetadata.builder().key("a2a:request").stringValue("req_payload").build()))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    CompletableFuture.allOf(
            state
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();

    Map<String, Object> a2aRow = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("A2A_INTERACTION row not found in queue", a2aRow);
    assertEquals("A2A_INTERACTION", a2aRow.get("event_type"));
    assertEquals("agent_name", a2aRow.get("agent"));
    assertFalse(
        "Content should not contain a2a_response payload since it was absent",
        a2aRow.containsKey("content"));
    ObjectNode attributes = (ObjectNode) a2aRow.get("attributes");
    ObjectNode a2aMetadata = (ObjectNode) attributes.get("a2a_metadata");

    // Assert keys present and absent in a2a_metadata
    assertEquals("task-456", a2aMetadata.get("a2a:task_id").asText());
    assertEquals("ctx-789", a2aMetadata.get("a2a:context_id").asText());
    assertEquals("req_payload", a2aMetadata.get("a2a:request").asText());
    assertFalse(a2aMetadata.has("a2a:response"));
  }

  @Test
  public void onEventCallback_agentResponse_filtersThoughtAndAppliesTruncation() throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .content(
                Content.builder()
                    .parts(
                        Part.builder().text("internal reasoning process").thought(true).build(),
                        Part.fromText("this text is very long and will exceed the limit"))
                    .build())
            .build();

    BigQueryLoggerConfig customConfig = config.toBuilder().maxContentLength(20).build();
    PluginState customState =
        new PluginState(customConfig) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }
        };
    BigQueryAgentAnalyticsPlugin customPlugin =
        new BigQueryAgentAnalyticsPlugin(customConfig, mockBigQuery, customState);

    customPlugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    CompletableFuture.allOf(
            customState
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();

    // Get AGENT_RESPONSE
    Map<String, Object> agentResponseRow =
        customState.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("AGENT_RESPONSE row not found in queue", agentResponseRow);
    assertEquals("AGENT_RESPONSE", agentResponseRow.get("event_type"));

    // Check content and truncation behavior on the parsed JSON object
    JsonNode contentNode = (JsonNode) agentResponseRow.get("content");
    assertTrue("content should contain 'text_summary'", contentNode.has("text_summary"));
    String textSummary = contentNode.get("text_summary").asText();

    assertTrue("Content should be marked as truncated", textSummary.contains("truncated"));
    assertFalse("Thought part should be filtered out", textSummary.contains("reasoning"));
    assertEquals(true, agentResponseRow.get("is_truncated"));
  }

  @Test
  public void onModelErrorCallback_populatesCorrectFields() throws Exception {
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    when(mockCallbackContext.invocationContext()).thenReturn(mockInvocationContext);
    LlmRequest.Builder mockLlmRequestBuilder = mock(LlmRequest.Builder.class);
    Throwable error = new RuntimeException("model error message");

    state.getTraceManager("invocation_id").pushSpan(mockInvocationContext, "llm_request");
    plugin
        .onModelErrorCallback(mockCallbackContext, mockLlmRequestBuilder, error)
        .blockingSubscribe();

    Map<String, Object> row = plugin.getState().getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("LLM_ERROR", row.get("event_type"));
    assertEquals("agent_name", row.get("agent"));
    assertEquals("ERROR", row.get("status"));
    assertEquals("model error message", row.get("error_message"));
    assertNotNull(row.get("latency_ms"));
    assertFalse("Row should not contain content when it is null", row.containsKey("content"));
    assertFalse(
        "Row should not contain content_parts when it is null", row.containsKey("content_parts"));
    assertFalse(
        "Row should not contain is_truncated when content is null",
        row.containsKey("is_truncated"));
  }

  @Test
  public void onModelErrorCallback_stampsPoppedSpanId() throws Exception {
    CallbackContext mockCallbackContext = mock(CallbackContext.class);
    when(mockCallbackContext.invocationContext()).thenReturn(mockInvocationContext);
    LlmRequest.Builder mockLlmRequestBuilder = mock(LlmRequest.Builder.class);

    String llmSpanId =
        state.getTraceManager("invocation_id").pushSpan(mockInvocationContext, "llm_request");
    plugin
        .onModelErrorCallback(
            mockCallbackContext, mockLlmRequestBuilder, new RuntimeException("boom"))
        .blockingSubscribe();

    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("LLM_ERROR", row.get("event_type"));
    // The error row's span_id must come from the popped internal span, not the post-pop stack.
    assertEquals(llmSpanId, row.get("span_id"));
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
            .cachedContentTokenCount(5)
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
      state.getTraceManager("invocation_id").pushSpan(mockInvocationContext, "parent_request");
      state.getTraceManager("invocation_id").pushSpan(mockInvocationContext, "llm_request");
      plugin.afterModelCallback(mockCallbackContext, adkResponse).blockingSubscribe();
    } finally {
      ambientSpan.end();
    }
    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
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
    assertEquals(5, usageAttr.get("cached_content_token_count").asInt());

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

    // Mirror the production flow: beforeToolCallback pushes the tool span with the SAME
    // operation identity (from the ToolContext) that afterToolCallback pops with.
    state.getTraceManager("invocation_id").ensureInvocationSpan(mockInvocationContext);
    plugin.beforeToolCallback(mockTool, toolArgs, mockToolContext).blockingSubscribe();
    plugin.afterToolCallback(mockTool, toolArgs, mockToolContext, result).blockingSubscribe();

    CompletableFuture.allOf(
            state
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();
    Map<String, Object> row;
    do {
      row = state.getBatchProcessor("invocation_id").queue.poll();
      assertNotNull("TOOL_COMPLETED row not found in queue", row);
    } while (!Objects.equals(row.get("event_type"), "TOOL_COMPLETED"));
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

    state.getTraceManager("invocation_id").pushSpan(mockInvocationContext, "tool_request");
    plugin
        .afterToolCallback(a2aTool, ImmutableMap.of(), mockToolContext, ImmutableMap.of())
        .blockingSubscribe();

    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull(row);
    ObjectNode contentMap = (ObjectNode) row.get("content");
    assertEquals("A2A", contentMap.get("tool_origin").asText());
  }

  @Test
  public void afterToolCallback_stampsPoppedToolSpanId() throws Exception {
    ToolContext mockToolContext = mock(ToolContext.class);
    when(mockToolContext.invocationContext()).thenReturn(mockInvocationContext);
    BaseTool mockTool = mock(BaseTool.class);
    when(mockTool.name()).thenReturn("test_tool");

    // Establish the invocation span first (so afterTool's ensureInvocationSpan keeps the stack),
    // then push the tool span that afterTool must pop and stamp onto the row.
    plugin
        .onUserMessageCallback(mockInvocationContext, Content.builder().build())
        .blockingSubscribe();
    String toolSpanId =
        state.getTraceManager("invocation_id").pushSpan(mockInvocationContext, "tool");
    // After the tool span is pushed, the enclosing span is the invocation span; afterTool must
    // stamp
    // it as the row's parent_span_id once the tool span is popped.
    String invocationSpanId =
        state
            .getTraceManager("invocation_id")
            .getCurrentSpanAndParent(mockInvocationContext)
            .parentSpanId()
            .orElseThrow();

    plugin
        .afterToolCallback(mockTool, ImmutableMap.of(), mockToolContext, ImmutableMap.of("r", "v"))
        .blockingSubscribe();

    Map<String, Object> completedRow = null;
    Map<String, Object> row;
    while ((row = state.getBatchProcessor("invocation_id").queue.poll()) != null) {
      if (Objects.equals(row.get("event_type"), "TOOL_COMPLETED")) {
        completedRow = row;
      }
    }
    assertNotNull("TOOL_COMPLETED row not found", completedRow);
    // span_id must be the popped tool span, not the enclosing invocation span left on the stack.
    assertEquals(toolSpanId, completedRow.get("span_id"));
    // parent_span_id must reference the enclosing invocation span from the post-pop stack top.
    assertEquals(invocationSpanId, completedRow.get("parent_span_id"));
  }

  @Test
  public void beforeToolCallback_concurrentTool_stampsEnclosingParentNotSibling() throws Exception {
    // Two tools run concurrently in one branch. The second tool's TOOL_STARTING parent must be its
    // PUSH-TIME parent (the enclosing invocation span), not the current stack top (its sibling tool
    // A). beforeTool stamps that push-time parent via an override; dropping the override would
    // misparent the row to the sibling.
    ToolContext ctxA = mock(ToolContext.class);
    when(ctxA.invocationContext()).thenReturn(mockInvocationContext);
    when(ctxA.functionCallId()).thenReturn(Optional.of("fc-A"));
    ToolContext ctxB = mock(ToolContext.class);
    when(ctxB.invocationContext()).thenReturn(mockInvocationContext);
    when(ctxB.functionCallId()).thenReturn(Optional.of("fc-B"));
    BaseTool toolA = mock(BaseTool.class);
    when(toolA.name()).thenReturn("tool_a");
    BaseTool toolB = mock(BaseTool.class);
    when(toolB.name()).thenReturn("tool_b");

    plugin
        .onUserMessageCallback(mockInvocationContext, Content.builder().build())
        .blockingSubscribe();
    String invocationSpanId =
        state
            .getTraceManager("invocation_id")
            .getCurrentSpanId(mockInvocationContext)
            .orElseThrow();

    // Tool A starts first (its span stays on the stack), then tool B starts concurrently.
    plugin.beforeToolCallback(toolA, ImmutableMap.of(), ctxA).blockingSubscribe();
    plugin.beforeToolCallback(toolB, ImmutableMap.of(), ctxB).blockingSubscribe();

    Map<String, Object> startingB = null;
    Map<String, Object> row;
    while ((row = state.getBatchProcessor("invocation_id").queue.poll()) != null) {
      if (Objects.equals(row.get("event_type"), "TOOL_STARTING")
          && "tool_b".equals(((ObjectNode) row.get("content")).get("tool").asText())) {
        startingB = row;
      }
    }
    assertNotNull("TOOL_STARTING row for tool_b not found", startingB);
    // Push-time parent is the enclosing invocation span, NOT sibling tool A's span.
    assertEquals(invocationSpanId, startingB.get("parent_span_id"));
  }

  @Test
  public void afterToolCallback_stampsParentFromPoppedRecord() throws Exception {
    // With a matching operation identity on both sides, afterTool pops the record beforeTool pushed
    // and must stamp TOOL_COMPLETED's parent from that POPPED record (captured at push time). After
    // the pop the stack top is the invocation span (whose own parent is null), so dropping the
    // override would leave the row with no parent link.
    ToolContext mockToolContext = mock(ToolContext.class);
    when(mockToolContext.invocationContext()).thenReturn(mockInvocationContext);
    when(mockToolContext.functionCallId()).thenReturn(Optional.of("fc-complete"));
    BaseTool mockTool = mock(BaseTool.class);
    when(mockTool.name()).thenReturn("test_tool");

    plugin
        .onUserMessageCallback(mockInvocationContext, Content.builder().build())
        .blockingSubscribe();
    String invocationSpanId =
        state
            .getTraceManager("invocation_id")
            .getCurrentSpanId(mockInvocationContext)
            .orElseThrow();

    plugin.beforeToolCallback(mockTool, ImmutableMap.of(), mockToolContext).blockingSubscribe();
    plugin
        .afterToolCallback(mockTool, ImmutableMap.of(), mockToolContext, ImmutableMap.of("r", "v"))
        .blockingSubscribe();

    Map<String, Object> completedRow = drainRowsByEventType().get("TOOL_COMPLETED");
    assertNotNull("TOOL_COMPLETED row not found", completedRow);
    assertEquals(invocationSpanId, completedRow.get("parent_span_id"));
  }

  @Test
  public void onToolErrorCallback_popsToolSpanAndStampsParent() throws Exception {
    // onToolError must pop the tool span pushed by beforeTool and stamp its push-time parent (the
    // enclosing invocation span) onto the TOOL_ERROR row.
    ToolContext mockToolContext = mock(ToolContext.class);
    when(mockToolContext.invocationContext()).thenReturn(mockInvocationContext);
    when(mockToolContext.functionCallId()).thenReturn(Optional.of("fc-error"));
    BaseTool mockTool = mock(BaseTool.class);
    when(mockTool.name()).thenReturn("failing_tool");

    plugin
        .onUserMessageCallback(mockInvocationContext, Content.builder().build())
        .blockingSubscribe();
    String invocationSpanId =
        state
            .getTraceManager("invocation_id")
            .getCurrentSpanId(mockInvocationContext)
            .orElseThrow();

    plugin
        .beforeToolCallback(mockTool, ImmutableMap.of("a", "b"), mockToolContext)
        .blockingSubscribe();
    plugin
        .onToolErrorCallback(
            mockTool, ImmutableMap.of("a", "b"), mockToolContext, new RuntimeException("boom"))
        .blockingSubscribe();

    Map<String, Object> errorRow = drainRowsByEventType().get("TOOL_ERROR");
    assertNotNull("TOOL_ERROR row not found", errorRow);
    // A present parent proves the tool span was popped (empty pop would leave no parent to stamp).
    assertEquals(invocationSpanId, errorRow.get("parent_span_id"));
  }

  @Test
  public void logEvent_includesSessionMetadata_whenEnabled() throws Exception {
    // Config default has logSessionMetadata(true)
    Content content = Content.fromParts(Part.fromText("test message"));
    plugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
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
    PluginState disabledState =
        new PluginState(disabledConfig) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }
        };
    BigQueryAgentAnalyticsPlugin disabledPlugin =
        new BigQueryAgentAnalyticsPlugin(disabledConfig, mockBigQuery, disabledState);

    Content content = Content.fromParts(Part.fromText("test message"));
    disabledPlugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    Map<String, Object> row = disabledState.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull(row);
    ObjectNode attributes = (ObjectNode) row.get("attributes");
    assertFalse(
        "attributes should not contain session_metadata", attributes.has("session_metadata"));
  }

  @Test
  public void logEvent_usesContentFormatter_whenConfigured() throws Exception {
    BiFunction<Object, String, Object> formatter =
        (content, eventType) -> {
          if (Objects.equals(eventType, "USER_MESSAGE_RECEIVED") && content instanceof Content) {
            return "Formatted: " + content;
          }
          return content;
        };

    BigQueryLoggerConfig formattedConfig = config.toBuilder().contentFormatter(formatter).build();
    PluginState formattedState =
        new PluginState(formattedConfig) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }
        };
    BigQueryAgentAnalyticsPlugin formattedPlugin =
        new BigQueryAgentAnalyticsPlugin(formattedConfig, mockBigQuery, formattedState);

    Content content = Content.fromParts(Part.fromText("test message"));
    formattedPlugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    Map<String, Object> row = formattedState.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull(row);
    assertTrue(row.get("content").toString().contains("Formatted: "));
  }

  @Test
  public void logEvent_handlesNullContentFromFormatter() throws Exception {
    BiFunction<Object, String, Object> formatter = (content, eventType) -> null;

    BigQueryLoggerConfig formattedConfig = config.toBuilder().contentFormatter(formatter).build();
    PluginState formattedState =
        new PluginState(formattedConfig) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }
        };
    BigQueryAgentAnalyticsPlugin formattedPlugin =
        new BigQueryAgentAnalyticsPlugin(formattedConfig, mockBigQuery, formattedState);

    Content content = Content.fromParts(Part.fromText("test message"));
    formattedPlugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    Map<String, Object> row = formattedState.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull(row);
    assertFalse(
        "Row should not contain content when formatter returns null", row.containsKey("content"));
    assertFalse(
        "Row should not contain content_parts when formatter returns null",
        row.containsKey("content_parts"));
  }

  @Test
  public void logEvent_handlesExceptionFromFormatter() throws Exception {
    BiFunction<Object, String, Object> formatter =
        (content, eventType) -> {
          throw new RuntimeException("Formatter error");
        };

    BigQueryLoggerConfig formattedConfig = config.toBuilder().contentFormatter(formatter).build();
    PluginState formattedState =
        new PluginState(formattedConfig) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }
        };
    BigQueryAgentAnalyticsPlugin formattedPlugin =
        new BigQueryAgentAnalyticsPlugin(formattedConfig, mockBigQuery, formattedState);

    Content content = Content.fromParts(Part.fromText("test message"));
    formattedPlugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    Map<String, Object> row = formattedState.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull(row);
    assertFalse(
        "Row should not contain content when formatter throws exception",
        row.containsKey("content"));
    assertFalse(
        "Row should not contain content_parts when formatter throws exception",
        row.containsKey("content_parts"));
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

    boolean upgraded = BigQueryUtils.maybeUpgradeSchema(mockBigQuery, mockTable);

    // A successful upgrade must report the table as ready.
    assertTrue(upgraded);
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

    var unused = BigQueryUtils.maybeUpgradeSchema(mockBigQuery, mockTable);

    ArgumentCaptor<StandardTableDefinition> definitionCaptor =
        ArgumentCaptor.forClass(StandardTableDefinition.class);
    verify(mockTableBuilder).setDefinition(definitionCaptor.capture());
    com.google.cloud.bigquery.Field contentParts =
        definitionCaptor.getValue().getSchema().getFields().get("content_parts");
    assertNotNull(contentParts.getSubFields().get("storage_mode"));

    verify(mockBigQuery).update(any(Table.class));
  }

  @Test
  public void maybeUpgradeSchema_warnsOnStructModeDrift() throws Exception {
    Table mockTable = mock(Table.class);
    when(mockTable.getTableId()).thenReturn(TableId.of("project", "dataset", "table"));
    when(mockTable.getLabels()).thenReturn(ImmutableMap.of());

    // Existing table has 'content_parts' as a NULLABLE STRUCT instead of the expected REPEATED
    ImmutableList<com.google.cloud.bigquery.Field> initialFields =
        BigQuerySchema.getEventsSchema().getFields().stream()
            .map(
                f ->
                    f.getName().equals("content_parts")
                        ? f.toBuilder().setMode(Mode.NULLABLE).build()
                        : f)
            .collect(toImmutableList());

    StandardTableDefinition tableDefinition =
        StandardTableDefinition.newBuilder()
            .setSchema(com.google.cloud.bigquery.Schema.of(initialFields))
            .build();
    when(mockTable.getDefinition()).thenReturn(tableDefinition);

    Logger logger = Logger.getLogger(BigQueryUtils.class.getName());
    Handler mockLogHandler = mock(Handler.class);
    logger.addHandler(mockLogHandler);
    try {
      var unused = BigQueryUtils.maybeUpgradeSchema(mockBigQuery, mockTable);
    } finally {
      logger.removeHandler(mockLogHandler);
    }

    ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
    verify(mockLogHandler, atLeastOnce()).publish(captor.capture());
    assertTrue(
        "Should have warned about STRUCT mode drift on content_parts",
        captor.getAllValues().stream()
            .anyMatch(
                record ->
                    Objects.equals(record.getLevel(), Level.WARNING)
                        && record
                            .getMessage()
                            .contains("Incompatible schema drift on column 'content_parts'")));

    // Mode drift alone is not auto-upgradeable, so no table update should be attempted.
    verify(mockBigQuery, never()).update(any(Table.class));
  }

  @Test
  public void maybeUpgradeSchema_noChanges_returnsTrueWithoutUpdateOrDriftWarning()
      throws Exception {
    Table mockTable = mock(Table.class);
    when(mockTable.getTableId()).thenReturn(TableId.of("project", "dataset", "table"));
    StandardTableDefinition tableDefinition =
        StandardTableDefinition.newBuilder().setSchema(BigQuerySchema.getEventsSchema()).build();
    when(mockTable.getDefinition()).thenReturn(tableDefinition);

    Logger logger = Logger.getLogger(BigQueryUtils.class.getName());
    Handler mockLogHandler = mock(Handler.class);
    logger.addHandler(mockLogHandler);
    boolean upgraded;
    try {
      upgraded = BigQueryUtils.maybeUpgradeSchema(mockBigQuery, mockTable);
    } finally {
      logger.removeHandler(mockLogHandler);
    }

    // When the existing schema already matches, the table is ready and no update is attempted.
    assertTrue(upgraded);
    verify(mockBigQuery, never()).update(any(Table.class));

    // Every matching field has equal modes, so no incompatible-drift warning must be emitted.
    ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
    verify(mockLogHandler, atLeast(0)).publish(captor.capture());
    assertFalse(
        "No drift warning should be logged when the schema already matches",
        captor.getAllValues().stream()
            .anyMatch(record -> record.getMessage().contains("Incompatible schema drift")));
  }

  @Test
  public void maybeUpgradeSchema_treatsNullModeAsNullable_noDriftWarning() throws Exception {
    Table mockTable = mock(Table.class);
    when(mockTable.getTableId()).thenReturn(TableId.of("project", "dataset", "table"));
    when(mockTable.getLabels()).thenReturn(ImmutableMap.of());

    // BigQuery reports getMode() == null for NULLABLE columns. Represent the NULLABLE 'event_type'
    // column with an unset (null) mode so its comparison against the NULLABLE desired field
    // exercises normalizeMode's null -> NULLABLE path. Build the field WITHOUT setMode: the OSS
    // BigQuery client stores toBuilder().setMode(null) as an empty mode string and throws on
    // getMode(), whereas an unset mode is genuinely null. Every other field matches exactly.
    com.google.cloud.bigquery.Field nullModeEventType =
        com.google.cloud.bigquery.Field.newBuilder("event_type", StandardSQLTypeName.STRING)
            .build();
    ImmutableList<com.google.cloud.bigquery.Field> initialFields =
        BigQuerySchema.getEventsSchema().getFields().stream()
            .map(f -> f.getName().equals("event_type") ? nullModeEventType : f)
            .collect(toImmutableList());
    StandardTableDefinition tableDefinition =
        StandardTableDefinition.newBuilder()
            .setSchema(com.google.cloud.bigquery.Schema.of(initialFields))
            .build();
    when(mockTable.getDefinition()).thenReturn(tableDefinition);

    Logger logger = Logger.getLogger(BigQueryUtils.class.getName());
    Handler mockLogHandler = mock(Handler.class);
    logger.addHandler(mockLogHandler);
    boolean upgraded;
    try {
      upgraded = BigQueryUtils.maybeUpgradeSchema(mockBigQuery, mockTable);
    } finally {
      logger.removeHandler(mockLogHandler);
    }

    // A null (unset) mode is semantically NULLABLE, so the column already matches: the table is
    // ready, no update is attempted, and no incompatible-drift warning must be emitted for it.
    assertTrue(upgraded);
    verify(mockBigQuery, never()).update(any(Table.class));

    ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
    verify(mockLogHandler, atLeast(0)).publish(captor.capture());
    assertFalse(
        "A null mode must be normalized to NULLABLE, so no drift warning should be logged for"
            + " 'event_type'",
        captor.getAllValues().stream()
            .anyMatch(
                record ->
                    record
                        .getMessage()
                        .contains("Incompatible schema drift on column 'event_type'")));
  }

  @Test
  public void maybeUpgradeSchema_warnsOnTypeDrift() throws Exception {
    Table mockTable = mock(Table.class);
    when(mockTable.getTableId()).thenReturn(TableId.of("project", "dataset", "table"));
    when(mockTable.getLabels()).thenReturn(ImmutableMap.of());

    // Existing 'timestamp' column has type STRING instead of the expected TIMESTAMP (same mode), so
    // only the type-drift branch should fire.
    ImmutableList<com.google.cloud.bigquery.Field> initialFields =
        BigQuerySchema.getEventsSchema().getFields().stream()
            .map(
                f ->
                    f.getName().equals("timestamp")
                        ? f.toBuilder().setType(StandardSQLTypeName.STRING).build()
                        : f)
            .collect(toImmutableList());
    StandardTableDefinition tableDefinition =
        StandardTableDefinition.newBuilder()
            .setSchema(com.google.cloud.bigquery.Schema.of(initialFields))
            .build();
    when(mockTable.getDefinition()).thenReturn(tableDefinition);

    Logger logger = Logger.getLogger(BigQueryUtils.class.getName());
    Handler mockLogHandler = mock(Handler.class);
    logger.addHandler(mockLogHandler);
    try {
      var unused = BigQueryUtils.maybeUpgradeSchema(mockBigQuery, mockTable);
    } finally {
      logger.removeHandler(mockLogHandler);
    }

    ArgumentCaptor<LogRecord> captor = ArgumentCaptor.forClass(LogRecord.class);
    verify(mockLogHandler, atLeastOnce()).publish(captor.capture());
    assertTrue(
        "Should have warned about type drift on the timestamp column",
        captor.getAllValues().stream()
            .anyMatch(
                record ->
                    Objects.equals(record.getLevel(), Level.WARNING)
                        && record
                            .getMessage()
                            .contains("Incompatible schema drift on column 'timestamp'")));
  }

  @Test
  public void isSafeIdentifier_nullIsRejectedWithoutThrowing() throws Exception {
    // A null identifier must be rejected by the explicit guard. Without it, Pattern.matcher(null)
    // would throw an NPE instead of returning false, so the DDL-safety check must short-circuit.
    assertFalse(BigQueryUtils.isSafeIdentifier(null));
    // Sanity: well-formed identifiers pass and unsafe characters are rejected.
    assertTrue(BigQueryUtils.isSafeIdentifier("project_123-abc"));
    assertFalse(BigQueryUtils.isSafeIdentifier("bad;drop table"));
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
    assertTrue(
        queries.stream()
            .anyMatch(
                q -> q.contains("CREATE OR REPLACE VIEW `project.dataset.v_a2a_interaction`")));
    assertTrue(
        queries.stream()
            .anyMatch(
                q -> q.contains("CREATE OR REPLACE VIEW `project.dataset.v_agent_response`")));
  }

  @Test
  public void multipleInvocations_logsCorrectly() throws Exception {
    BigQueryLoggerConfig testConfig = config.toBuilder().batchSize(10).build();
    PluginState testState =
        new PluginState(testConfig) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }
        };
    BigQueryAgentAnalyticsPlugin testPlugin =
        new BigQueryAgentAnalyticsPlugin(testConfig, mockBigQuery, testState);

    InvocationContext context1 = mock(InvocationContext.class);
    when(context1.invocationId()).thenReturn("inv-1");
    when(context1.agent()).thenReturn(fakeAgent);
    when(context1.session()).thenReturn(Session.builder("s1").build());

    InvocationContext context2 = mock(InvocationContext.class);
    when(context2.invocationId()).thenReturn("inv-2");
    when(context2.agent()).thenReturn(fakeAgent);
    when(context2.session()).thenReturn(Session.builder("s2").build());

    var unused1 = testPlugin.beforeRunCallback(context1).blockingGet();
    var unused2 =
        testPlugin
            .onUserMessageCallback(context1, Content.fromParts(Part.fromText("msg1")))
            .blockingGet();

    var unused3 = testPlugin.beforeRunCallback(context2).blockingGet();
    var unused4 =
        testPlugin
            .onUserMessageCallback(context2, Content.fromParts(Part.fromText("msg2")))
            .blockingGet();

    // Verify processors are created and have correct data in their queues
    BatchProcessor p1 = testState.getBatchProcessor("inv-1");
    BatchProcessor p2 = testState.getBatchProcessor("inv-2");

    assertNotNull("Processor for inv-1 should exist", p1);
    assertNotNull("Processor for inv-2 should exist", p2);
    assertFalse("Queue for inv-1 should not be empty", p1.queue.isEmpty());
    assertFalse("Queue for inv-2 should not be empty", p2.queue.isEmpty());

    assertTrue(
        "All logs for inv-1 should have correct invocation_id",
        p1.queue.stream().allMatch(row -> row.get("invocation_id").equals("inv-1")));
    assertTrue(
        "All logs for inv-2 should have correct invocation_id",
        p2.queue.stream().allMatch(row -> row.get("invocation_id").equals("inv-2")));

    // Now flush and verify writer was called
    testPlugin.afterRunCallback(context1).blockingAwait();
    testPlugin.afterRunCallback(context2).blockingAwait();

    verify(mockWriter, atLeastOnce()).append(any(ArrowRecordBatch.class));
  }

  @Test
  public void logEvent_createsUniqueProcessorPerInvocation() throws Exception {
    int numInvocations = 5;
    ExecutorService testExecutor = Executors.newFixedThreadPool(numInvocations);
    Set<BatchProcessor> processors = ConcurrentHashMap.newKeySet();
    CountDownLatch latch = new CountDownLatch(numInvocations);

    for (int i = 0; i < numInvocations; i++) {
      final String invocationId = "inv-" + i;
      testExecutor.execute(
          () -> {
            try {
              InvocationContext context = mock(InvocationContext.class);
              when(context.invocationId()).thenReturn(invocationId);
              when(context.agent()).thenReturn(fakeAgent);
              Session session = Session.builder("s").build();
              when(context.session()).thenReturn(session);

              plugin.beforeRunCallback(context).blockingSubscribe();
              processors.add(state.getBatchProcessor(invocationId));
            } finally {
              latch.countDown();
            }
          });
    }

    latch.await();
    assertEquals(numInvocations, processors.size());
    testExecutor.shutdown();
  }

  @Test
  public void logEvent_offloadsToGcs_whenLargeContent() throws Exception {
    GcsOffloader mockOffloader = mock(GcsOffloader.class);
    when(mockOffloader.uploadContent(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture("gs://test-bucket/large.txt"));

    BigQueryLoggerConfig gcsConfig = config.toBuilder().gcsBucketName("test-bucket").build();
    PluginState gcsState =
        new PluginState(gcsConfig) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }

          @Override
          protected GcsOffloader getGcsOffloader(BigQueryLoggerConfig config) {
            return mockOffloader;
          }
        };
    BigQueryAgentAnalyticsPlugin gcsPlugin =
        new BigQueryAgentAnalyticsPlugin(gcsConfig, mockBigQuery, gcsState);

    // Large text (> 32KB default threshold)
    String largeText = "a".repeat(40000);
    Content content = Content.fromParts(Part.fromText(largeText));
    gcsPlugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    verify(mockOffloader, atLeastOnce()).uploadContent(anyString(), anyString(), anyString());

    Map<String, Object> row = gcsState.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull(row);
    @SuppressWarnings("unchecked") // Test only
    List<JsonNode> contentParts = (List<JsonNode>) row.get("content_parts");
    assertEquals("GCS_REFERENCE", contentParts.get(0).get("storage_mode").asText());
    assertEquals("gs://test-bucket/large.txt", contentParts.get(0).get("uri").asText());
  }

  @Test
  public void logEvent_offloadsToGcs_whenMultimodalContent() throws Exception {
    GcsOffloader mockOffloader = mock(GcsOffloader.class);
    when(mockOffloader.uploadContent(any(byte[].class), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture("gs://test-bucket/image.png"));

    BigQueryLoggerConfig gcsConfig = config.toBuilder().gcsBucketName("test-bucket").build();
    PluginState gcsState =
        new PluginState(gcsConfig) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }

          @Override
          protected GcsOffloader getGcsOffloader(BigQueryLoggerConfig config) {
            return mockOffloader;
          }
        };
    BigQueryAgentAnalyticsPlugin gcsPlugin =
        new BigQueryAgentAnalyticsPlugin(gcsConfig, mockBigQuery, gcsState);

    Content content = Content.fromParts(Part.fromBytes("test-data".getBytes(UTF_8), "image/png"));
    gcsPlugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    verify(mockOffloader, atLeastOnce()).uploadContent(any(byte[].class), anyString(), anyString());

    Map<String, Object> row = gcsState.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull(row);
    @SuppressWarnings("unchecked") // Test only
    List<JsonNode> contentParts = (List<JsonNode>) row.get("content_parts");
    assertEquals("GCS_REFERENCE", contentParts.get(0).get("storage_mode").asText());
    assertEquals("gs://test-bucket/image.png", contentParts.get(0).get("uri").asText());
  }

  @Test
  public void logEvent_integrationWithRealGcsOffloader_whenLargeContent() throws Exception {
    Storage mockStorage = mock(Storage.class);

    BigQueryLoggerConfig gcsConfig = config.toBuilder().gcsBucketName("test-bucket").build();
    PluginState gcsState =
        new PluginState(gcsConfig) {
          @Override
          protected BigQueryWriteClient createWriteClient(BigQueryLoggerConfig config) {
            return mockWriteClient;
          }

          @Override
          protected StreamWriter createWriter() {
            return mockWriter;
          }

          @Override
          protected GcsOffloader getGcsOffloader(BigQueryLoggerConfig config) {
            return new GcsOffloader(
                config.projectId(),
                config.gcsBucketName(),
                Runnable::run, // Use direct executor for synchronous execution
                config.credentials(),
                mockStorage);
          }
        };
    BigQueryAgentAnalyticsPlugin gcsPlugin =
        new BigQueryAgentAnalyticsPlugin(gcsConfig, mockBigQuery, gcsState);

    // Large text (> 32KB default threshold)
    String largeText = "a".repeat(40000);
    Content content = Content.fromParts(Part.fromText(largeText));
    gcsPlugin.onUserMessageCallback(mockInvocationContext, content).blockingSubscribe();

    verify(mockStorage, atLeastOnce()).create(any(BlobInfo.class), any(byte[].class));

    Map<String, Object> row = gcsState.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull(row);
    @SuppressWarnings("unchecked") // Test only
    List<JsonNode> contentParts = (List<JsonNode>) row.get("content_parts");
    assertEquals("GCS_REFERENCE", contentParts.get(0).get("storage_mode").asText());
    assertTrue(contentParts.get(0).get("uri").asText().startsWith("gs://test-bucket/"));
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

  private Map<String, Map<String, Object>> drainRowsByEventType() {
    CompletableFuture.allOf(
            state
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();
    Map<String, Map<String, Object>> rowsByType = new HashMap<>();
    Map<String, Object> row;
    while ((row = state.getBatchProcessor("invocation_id").queue.poll()) != null) {
      rowsByType.put((String) row.get("event_type"), row);
    }
    return rowsByType;
  }

  @Test
  public void logEvent_redactsSensitiveKeysAtFinalAttributesBoundary() throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .actions(
                EventActions.builder()
                    .stateDelta(
                        ImmutableMap.of(
                            "access_token",
                            "super-secret",
                            "nested",
                            ImmutableMap.of("api_key", "k-123"),
                            "safe",
                            "visible"))
                    .build())
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();

    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("Row not found in queue", row);
    assertEquals("STATE_DELTA", row.get("event_type"));
    JsonNode attributes = (JsonNode) row.get("attributes");
    // state_delta enters attributes directly (not via the content formatter); the final
    // output-boundary pass must still redact sensitive keys, including nested ones.
    assertEquals("[REDACTED]", attributes.get("state_delta").get("access_token").asText());
    assertEquals("[REDACTED]", attributes.get("state_delta").get("nested").get("api_key").asText());
    assertEquals("visible", attributes.get("state_delta").get("safe").asText());
  }

  @Test
  public void onEventCallback_hitlFunctionCall_emitsRequestNotCompleted() throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .name("adk_request_confirmation")
                                .id("fc-1")
                                .args(ImmutableMap.of("prompt", "approve?"))
                                .build())
                        .build()))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    Map<String, Map<String, Object>> rows = drainRowsByEventType();

    // The synthetic function CALL is the HITL request (the pause side), not a completion.
    assertTrue(
        "Expected HITL_CONFIRMATION_REQUEST, got: " + rows.keySet(),
        rows.containsKey("HITL_CONFIRMATION_REQUEST"));
    assertFalse(rows.containsKey("HITL_CONFIRMATION_REQUEST_COMPLETED"));
  }

  @Test
  public void onEventCallback_longRunningHitlCall_emitsPairedToolPaused() throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .longRunningToolIds(ImmutableSet.of("fc-1"))
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .name("adk_request_credential")
                                .id("fc-1")
                                .args(ImmutableMap.of("scope", "email"))
                                .build())
                        .build()))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    Map<String, Map<String, Object>> rows = drainRowsByEventType();

    assertTrue(rows.containsKey("HITL_CREDENTIAL_REQUEST"));
    Map<String, Object> paused = rows.get("TOOL_PAUSED");
    assertNotNull("TOOL_PAUSED row not found, got: " + rows.keySet(), paused);
    JsonNode attributes = (JsonNode) paused.get("attributes");
    assertEquals("hitl_credential", attributes.get("pause_kind").asText());
    assertEquals("fc-1", attributes.get("function_call_id").asText());
  }

  @Test
  public void onEventCallback_longRunningOrdinaryCall_emitsToolPausedWithToolKind()
      throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .longRunningToolIds(ImmutableSet.of("fc-2"))
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionCall(
                            FunctionCall.builder()
                                .name("my_long_tool")
                                .id("fc-2")
                                .args(ImmutableMap.of("job", "batch-7"))
                                .build())
                        .build()))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    Map<String, Map<String, Object>> rows = drainRowsByEventType();

    Map<String, Object> paused = rows.get("TOOL_PAUSED");
    assertNotNull("TOOL_PAUSED row not found, got: " + rows.keySet(), paused);
    JsonNode attributes = (JsonNode) paused.get("attributes");
    assertEquals("tool", attributes.get("pause_kind").asText());
    assertEquals("fc-2", attributes.get("function_call_id").asText());
    // An ordinary long-running call is not a HITL request.
    assertFalse(rows.keySet().stream().anyMatch(k -> k.startsWith("HITL_")));
  }

  @Test
  public void onUserMessageCallback_hitlFunctionResponse_emitsCompleted() throws Exception {
    Content userMessage =
        Content.fromParts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .name("adk_request_input")
                        .id("fc-3")
                        .response(ImmutableMap.of("value", "user typed this"))
                        .build())
                .build());

    plugin.onUserMessageCallback(mockInvocationContext, userMessage).blockingSubscribe();
    Map<String, Map<String, Object>> rows = drainRowsByEventType();

    assertTrue(rows.containsKey("USER_MESSAGE_RECEIVED"));
    // The resumed HITL input arrives as a FunctionResponse and completes the HITL pair; it must
    // not also emit TOOL_COMPLETED.
    Map<String, Object> completed = rows.get("HITL_INPUT_REQUEST_COMPLETED");
    assertNotNull("Expected HITL_INPUT_REQUEST_COMPLETED, got: " + rows.keySet(), completed);
    assertFalse(rows.containsKey("TOOL_COMPLETED"));
    // The completion carries the pause pair keys so it joins its HITL_*_REQUEST / TOOL_PAUSED
    // rows even when multiple HITL requests share an invocation.
    JsonNode completedAttributes = (JsonNode) completed.get("attributes");
    assertEquals("hitl_input", completedAttributes.get("pause_kind").asText());
    assertEquals("fc-3", completedAttributes.get("function_call_id").asText());
  }

  @Test
  public void onUserMessageCallback_nonHitlFunctionResponse_emitsToolCompletedWithPairKeys()
      throws Exception {
    Content userMessage =
        Content.fromParts(
            Part.builder()
                .functionResponse(
                    FunctionResponse.builder()
                        .name("my_long_tool")
                        .id("fc-4")
                        .response(ImmutableMap.of("status", "done"))
                        .build())
                .build());

    plugin.onUserMessageCallback(mockInvocationContext, userMessage).blockingSubscribe();
    Map<String, Map<String, Object>> rows = drainRowsByEventType();

    // A non-HITL FunctionResponse in a user message is the resume side of a paused long-running
    // tool; it emits TOOL_COMPLETED carrying the pause pair keys for the BigQuery join.
    Map<String, Object> completed = rows.get("TOOL_COMPLETED");
    assertNotNull("TOOL_COMPLETED row not found, got: " + rows.keySet(), completed);
    JsonNode attributes = (JsonNode) completed.get("attributes");
    assertEquals("tool", attributes.get("pause_kind").asText());
    assertEquals("fc-4", attributes.get("function_call_id").asText());
    JsonNode content = (JsonNode) completed.get("content");
    assertEquals("my_long_tool", content.get("tool").asText());
    assertNotNull(content.get("result"));
  }

  @Test
  public void onEventCallback_hitlFunctionResponse_completionCarriesPairKeys() throws Exception {
    Event event =
        Event.builder()
            .author("agent_author")
            .content(
                Content.fromParts(
                    Part.builder()
                        .functionResponse(
                            FunctionResponse.builder()
                                .name("adk_request_confirmation")
                                .id("fc-7")
                                .response(ImmutableMap.of("confirmed", true))
                                .build())
                        .build()))
            .build();

    plugin.onEventCallback(mockInvocationContext, event).blockingSubscribe();
    Map<String, Map<String, Object>> rows = drainRowsByEventType();

    Map<String, Object> completed = rows.get("HITL_CONFIRMATION_REQUEST_COMPLETED");
    assertNotNull(
        "HITL_CONFIRMATION_REQUEST_COMPLETED row not found, got: " + rows.keySet(), completed);
    JsonNode attributes = (JsonNode) completed.get("attributes");
    assertEquals("hitl_confirmation", attributes.get("pause_kind").asText());
    assertEquals("fc-7", attributes.get("function_call_id").asText());
    // Content key parity: both HITL completion producer paths (event and user-message) use
    // "result", matching the Python plugin, so one event type has one queryable content shape.
    JsonNode content = (JsonNode) completed.get("content");
    assertNotNull("content.result must be present on the event path", content.get("result"));
  }

  @Test
  public void concurrentIdLessTools_keepSpanOwnership() throws Exception {
    // The framework materializes an absent function-call ID as "" — two concurrent id-less calls
    // must not collide on it and cross-pop each other's spans.
    BaseTool toolA = mock(BaseTool.class);
    when(toolA.name()).thenReturn("tool_a");
    BaseTool toolB = mock(BaseTool.class);
    when(toolB.name()).thenReturn("tool_b");
    ToolContext contextA = mock(ToolContext.class);
    when(contextA.invocationContext()).thenReturn(mockInvocationContext);
    when(contextA.functionCallId()).thenReturn(Optional.of(""));
    ToolContext contextB = mock(ToolContext.class);
    when(contextB.invocationContext()).thenReturn(mockInvocationContext);
    when(contextB.functionCallId()).thenReturn(Optional.of(""));

    state.getTraceManager("invocation_id").ensureInvocationSpan(mockInvocationContext);
    plugin.beforeToolCallback(toolA, ImmutableMap.of(), contextA).blockingSubscribe();
    plugin.beforeToolCallback(toolB, ImmutableMap.of(), contextB).blockingSubscribe();
    // A completes FIRST even though B's record sits above it.
    plugin
        .afterToolCallback(toolA, ImmutableMap.of(), contextA, ImmutableMap.of())
        .blockingSubscribe();
    plugin
        .afterToolCallback(toolB, ImmutableMap.of(), contextB, ImmutableMap.of())
        .blockingSubscribe();

    CompletableFuture.allOf(
            state
                .getPendingTasksForInvocation("invocation_id")
                .toArray(new CompletableFuture<?>[0]))
        .join();
    Map<String, String> startingSpanByTool = new HashMap<>();
    Map<String, String> completedSpanByTool = new HashMap<>();
    Map<String, Object> row;
    while ((row = state.getBatchProcessor("invocation_id").queue.poll()) != null) {
      String tool = ((JsonNode) row.get("content")).get("tool").asText();
      if (Objects.equals(row.get("event_type"), "TOOL_STARTING")) {
        startingSpanByTool.put(tool, (String) row.get("span_id"));
      } else if (Objects.equals(row.get("event_type"), "TOOL_COMPLETED")) {
        completedSpanByTool.put(tool, (String) row.get("span_id"));
      }
    }

    // Each tool's completion row references ITS OWN starting span, not the sibling's.
    assertEquals(startingSpanByTool.get("tool_a"), completedSpanByTool.get("tool_a"));
    assertEquals(startingSpanByTool.get("tool_b"), completedSpanByTool.get("tool_b"));
    assertFalse(
        "sibling id-less tools must not share a span",
        startingSpanByTool.get("tool_a").equals(startingSpanByTool.get("tool_b")));
  }

  @Test
  public void logEvent_sessionState_redactedBeforeTruncationFallback() throws Exception {
    // One unserializable session-state value must not stringify the whole state map (which would
    // put the sibling secret beyond the reach of key redaction); state is redacted BEFORE
    // truncation, per leaf.
    Session sessionWithState =
        Session.builder("session_id").appName("test_app").userId("test_user").build();
    when(mockInvocationContext.session()).thenReturn(sessionWithState);
    sessionWithState.state().put("api_key", "super-secret");
    sessionWithState.state().put("bad", new Object());
    sessionWithState.state().put("ok", "visible");

    plugin.beforeRunCallback(mockInvocationContext).blockingSubscribe();

    Map<String, Object> row = state.getBatchProcessor("invocation_id").queue.poll();
    assertNotNull("Row not found in queue", row);
    JsonNode stateNode = ((JsonNode) row.get("attributes")).get("session_metadata").get("state");
    assertTrue("session state must remain structured, not stringified", stateNode.isObject());
    assertEquals("[REDACTED]", stateNode.get("api_key").asText());
    assertEquals("[UNSERIALIZABLE]", stateNode.get("bad").asText());
    assertEquals("visible", stateNode.get("ok").asText());
  }
}
