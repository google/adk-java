/*
 * Copyright 2025 Google LLC
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

package com.google.adk.tools.retrieval;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link CassandraRagRetrieval}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-19
 */
@RunWith(JUnit4.class)
public final class CassandraRagRetrievalTest {

  @Mock private CqlSession session;
  @Mock private ResultSet resultSet;
  @Mock private Row row;
  @Mock private PreparedStatement preparedStatement;
  @Mock private BoundStatement boundStatement;

  private CassandraRagRetrieval tool;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    tool = new CassandraRagRetrieval("test_tool", "test_description", session, "rae", "rae_data");
    when(preparedStatement.bind(any(), any(), any())).thenReturn(boundStatement);
  }

  @Test
  public void testRunAsync() {

    // Arrange

    List<Double> embedding = ImmutableList.of(0.1, 0.2, 0.3);

    Map<String, Object> args =
        ImmutableMap.of(
            "embedding",
            embedding,
            "top_k",
            5,
            "similarity_threshold",
            0.85f,
            "keyspace",
            "test_keyspace",
            "table",
            "test_table",
            "embedding_column",
            "embedding");

    String expectedCql =
        "SELECT agent_name, similarity_cosine(embedding, ?) as score, data FROM"
            + " test_keyspace.test_table"
            + " ORDER BY embedding ANN OF ? LIMIT ?";

    String expectedId = "test-id";

    float expectedScore = 0.9f;

    String expectedData = "{\"key\":\"value\"}";

    when(row.getString("agent_name")).thenReturn(expectedId);

    when(row.getFloat("score")).thenReturn(expectedScore);

    when(row.getString("data")).thenReturn(expectedData);

    when(resultSet.all()).thenReturn(ImmutableList.of(row));

    when(session.prepare(expectedCql)).thenReturn(preparedStatement);

    when(session.execute(any(BoundStatement.class))).thenReturn(resultSet);

    // Act

    Map<String, Object> result = tool.runAsync(args, null).blockingGet();

    // Assert

    List<Map<String, Object>> response = (List<Map<String, Object>>) result.get("response");

    assertThat(response).hasSize(1);

    assertThat(response.get(0)).containsEntry("agent_name", expectedId);

    assertThat(response.get(0)).containsEntry("score", expectedScore);

    assertThat(response.get(0)).containsEntry("data", expectedData);
  }

  @Test
  public void testRunAsync_scoreBelowThreshold() {

    // Arrange

    List<Float> embedding = ImmutableList.of(0.1f, 0.2f, 0.3f);

    Map<String, Object> args =
        ImmutableMap.of(
            "embedding",
            embedding,
            "top_k",
            5,
            "similarity_threshold",
            0.95f,
            "keyspace",
            "test_keyspace",
            "table",
            "test_table",
            "embedding_column",
            "embedding");

    String expectedCql =
        "SELECT agent_name, similarity_cosine(embedding, ?) as score, data FROM"
            + " test_keyspace.test_table"
            + " ORDER BY embedding ANN OF ? LIMIT ?";

    String expectedId = "test-id";

    float expectedScore = 0.9f;

    String expectedData = "{\"key\":\"value\"}";

    when(row.getString("agent_name")).thenReturn(expectedId);

    when(row.getFloat("score")).thenReturn(expectedScore);

    when(row.getString("data")).thenReturn(expectedData);

    when(resultSet.all()).thenReturn(ImmutableList.of(row));

    when(session.prepare(expectedCql)).thenReturn(preparedStatement);

    when(session.execute(any(BoundStatement.class))).thenReturn(resultSet);

    // Act

    Map<String, Object> result = tool.runAsync(args, null).blockingGet();

    // Assert

    List<Map<String, Object>> response = (List<Map<String, Object>>) result.get("response");

    assertThat(response).isEmpty();
  }
}
