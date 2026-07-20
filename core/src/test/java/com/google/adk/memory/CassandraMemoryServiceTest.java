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

package com.google.adk.memory;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.adk.tools.retrieval.CassandraRagRetrieval;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CassandraMemoryService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-21
 */
public class CassandraMemoryServiceTest {

  private CassandraRagRetrieval mockCassandraRagRetrieval;
  private EmbeddingService mockEmbeddingService;
  private CassandraMemoryService memoryService;

  @BeforeEach
  public void setUp() {
    mockCassandraRagRetrieval = mock(CassandraRagRetrieval.class);
    mockEmbeddingService = mock(EmbeddingService.class);
    memoryService = new CassandraMemoryService(mockCassandraRagRetrieval, mockEmbeddingService);
  }

  @Test
  public void testAddSessionToMemory() {
    Session session =
        Session.builder("testSession")
            .appName("testApp")
            .userId("testUser")
            .events(
                List.of(
                    Event.builder()
                        .timestamp(1L)
                        .author("user")
                        .content(
                            Content.builder().parts(List.of(Part.fromText("hello world"))).build())
                        .build()))
            .build();
    double[] embedding = new double[] {1.0, 2.0, 3.0};
    CqlSession mockCqlSession = mock(CqlSession.class);

    when(mockEmbeddingService.generateEmbedding("hello world")).thenReturn(Single.just(embedding));
    when(mockCassandraRagRetrieval.getSession()).thenReturn(mockCqlSession);
    when(mockCassandraRagRetrieval.getKeyspace()).thenReturn("testKeyspace");
    when(mockCassandraRagRetrieval.getTable()).thenReturn("testTable");

    memoryService.addSessionToMemory(session).blockingAwait();

    // Create the expected CqlVector for verification
    List<Float> floatList =
        Arrays.stream(embedding).mapToObj(d -> (float) d).collect(Collectors.toList());
    CqlVector<Float> expectedVector = CqlVector.newInstance(floatList);

    verify(mockCqlSession)
        .execute(
            "INSERT INTO testKeyspace.testTable (agent_name, user_id, turn_id, data, embedding) VALUES (?, ?, now(), ?, ?)",
            "testApp",
            "testSession",
            "hello world",
            expectedVector);
  }

  @Test
  public void testSearchMemory() {
    String appName = "testApp";
    String userId = "testUser";
    String query = "hello";
    double[] embedding = new double[] {1.0, 2.0, 3.0};
    List<ImmutableMap<String, Object>> contexts =
        List.of(ImmutableMap.of("agent_name", "testApp", "score", 0.9f, "data", "hello world"));

    when(mockEmbeddingService.generateEmbedding(query)).thenReturn(Single.just(embedding));
    when(mockCassandraRagRetrieval.runAsync(any(), any()))
        .thenReturn(Single.just(ImmutableMap.of("response", contexts)));

    SearchMemoryResponse response =
        memoryService.searchMemory(appName, userId, query).blockingGet();

    assertThat(response.memories()).hasSize(1);
    assertThat(response.memories().get(0).content().parts().get().get(0).text().get())
        .isEqualTo("hello world");
  }
}
