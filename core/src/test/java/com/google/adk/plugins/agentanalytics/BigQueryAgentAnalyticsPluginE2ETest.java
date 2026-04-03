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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
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
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BigQueryAgentAnalyticsPluginE2ETest {
  private BigQuery mockBigQuery;
  private StreamWriter mockWriter;
  private BigQueryWriteClient mockWriteClient;
  private BigQueryLoggerConfig config;
  private BigQueryAgentAnalyticsPlugin plugin;
  private Runner runner;
  private BaseAgent fakeAgent;
  private final List<Map<String, Object>> capturedRows =
      Collections.synchronizedList(new ArrayList<>());

  @Before
  public void setUp() throws Exception {
    mockBigQuery = mock(BigQuery.class);
    mockWriter = mock(StreamWriter.class);
    mockWriteClient = mock(BigQueryWriteClient.class);

    config =
        BigQueryLoggerConfig.builder()
            .enabled(true)
            .projectId("project")
            .datasetId("dataset")
            .tableName("table")
            .batchSize(10)
            .batchFlushInterval(Duration.ofSeconds(10))
            .credentials(mock(Credentials.class))
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

    when(mockWriter.append(any(ArrowRecordBatch.class)))
        .thenAnswer(
            invocation -> {
              ArrowRecordBatch recordedBatch = invocation.getArgument(0);
              try (VectorSchemaRoot root =
                  VectorSchemaRoot.create(
                      BigQuerySchema.getArrowSchema(), plugin.batchProcessor.allocator)) {
                VectorLoader loader = new VectorLoader(root);
                loader.load(recordedBatch);
                for (int i = 0; i < root.getRowCount(); i++) {
                  Map<String, Object> row = new HashMap<>();
                  row.put("event_type", String.valueOf(root.getVector("event_type").getObject(i)));
                  row.put("agent", String.valueOf(root.getVector("agent").getObject(i)));
                  row.put("session_id", String.valueOf(root.getVector("session_id").getObject(i)));
                  row.put(
                      "invocation_id",
                      String.valueOf(root.getVector("invocation_id").getObject(i)));
                  row.put("user_id", String.valueOf(root.getVector("user_id").getObject(i)));
                  row.put(
                      "timestamp", ((TimeStampMicroTZVector) root.getVector("timestamp")).get(i));
                  row.put("is_truncated", root.getVector("is_truncated").getObject(i));
                  row.put("content", String.valueOf(root.getVector("content").getObject(i)));
                  capturedRows.add(row);
                }
              } catch (RuntimeException e) {
                throw new RuntimeException("Error in thenAnswer", e);
              }
              return ApiFutures.immediateFuture(AppendRowsResponse.getDefaultInstance());
            });

    fakeAgent = new FakeAgent("test_agent");
    runner = Runner.builder().agent(fakeAgent).appName("test_app").plugins(plugin).build();
  }

  @Test
  public void runAgent_logsAgentStartingAndCompleted() throws Exception {
    Session session = runner.sessionService().createSession("test_app", "user").blockingGet();
    String sessionId = session.id();

    runner
        .runAsync("user", sessionId, Content.fromParts(Part.fromText("hello")))
        .blockingSubscribe();

    // Ensure everything is flushed. The BatchProcessor flushes asynchronously sometimes,
    // but the direct flush() call should help. We wait up to 2 seconds for all 5 expected events.
    for (int i = 0; i < 20 && capturedRows.size() < 5; i++) {
      plugin.batchProcessor.flush();
      if (capturedRows.size() < 5) {
        Thread.sleep(100);
      }
    }

    // Verify presence of expected events
    List<String> eventTypes =
        capturedRows.stream().map(row -> (String) row.get("event_type")).toList();

    assertFalse("capturedRows should not be empty", capturedRows.isEmpty());
    assertTrue(
        "Events should contain AGENT_STARTING. Actual: " + eventTypes,
        eventTypes.contains("AGENT_STARTING"));
    assertTrue(
        "Events should contain AGENT_COMPLETED. Actual: " + eventTypes,
        eventTypes.contains("AGENT_COMPLETED"));
    assertTrue(
        "Events should contain USER_MESSAGE_RECEIVED. Actual: " + eventTypes,
        eventTypes.contains("USER_MESSAGE_RECEIVED"));
    assertTrue(
        "Events should contain INVOCATION_STARTING. Actual: " + eventTypes,
        eventTypes.contains("INVOCATION_STARTING"));
    assertTrue(
        "Events should contain INVOCATION_COMPLETED. Actual: " + eventTypes,
        eventTypes.contains("INVOCATION_COMPLETED"));

    // Verify common fields for one of the rows
    Map<String, Object> agentStartingRow =
        capturedRows.stream()
            .filter(row -> Objects.equals(row.get("event_type"), "AGENT_STARTING"))
            .findFirst()
            .orElseThrow();

    assertEquals("test_agent", agentStartingRow.get("agent"));
    assertEquals(sessionId, agentStartingRow.get("session_id"));
    assertEquals("user", agentStartingRow.get("user_id"));
    assertNotNull("invocation_id should be populated", agentStartingRow.get("invocation_id"));
    assertTrue("timestamp should be positive", (Long) agentStartingRow.get("timestamp") > 0);
    assertEquals(false, agentStartingRow.get("is_truncated"));

    // Verify content for USER_MESSAGE_RECEIVED
    Map<String, Object> userMessageRow =
        capturedRows.stream()
            .filter(row -> Objects.equals(row.get("event_type"), "USER_MESSAGE_RECEIVED"))
            .findFirst()
            .orElseThrow();
    String contentJson = (String) userMessageRow.get("content");
    assertTrue("Content should contain 'hello'", contentJson.contains("hello"));

    // Verify order
    int userMessageIdx = eventTypes.indexOf("USER_MESSAGE_RECEIVED");
    int invocationStartIdx = eventTypes.indexOf("INVOCATION_STARTING");
    int agentStartIdx = eventTypes.indexOf("AGENT_STARTING");
    int agentCompletedIdx = eventTypes.indexOf("AGENT_COMPLETED");
    int invocationCompletedIdx = eventTypes.indexOf("INVOCATION_COMPLETED");

    assertTrue(
        "USER_MESSAGE_RECEIVED should be first by Runner implementation",
        userMessageIdx < invocationStartIdx);
    assertTrue(
        "INVOCATION_STARTING should be before AGENT_STARTING", invocationStartIdx < agentStartIdx);
    assertTrue(
        "AGENT_STARTING should be before AGENT_COMPLETED", agentStartIdx < agentCompletedIdx);
    assertTrue(
        "AGENT_COMPLETED should be before INVOCATION_COMPLETED",
        agentCompletedIdx < invocationCompletedIdx);
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
