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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.google.adk.sessions.Session;
import com.google.adk.tools.retrieval.CassandraRagRetrieval;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * An implementation of {@link BaseMemoryService} that uses Cassandra for storage and retrieval.
 *
 * @author Pawan Kumar
 * @since 2026-01-05
 */
public class SessionLevelMemoryService implements BaseMemoryService {

  private final CassandraRagRetrieval cassandraRagRetrieval;
  private final EmbeddingService embeddingService;

  public SessionLevelMemoryService(
      @Nonnull CassandraRagRetrieval cassandraRagRetrieval,
      @Nonnull EmbeddingService embeddingService) {
    this.cassandraRagRetrieval = cassandraRagRetrieval;
    this.embeddingService = embeddingService;
  }

  public SessionLevelMemoryService(
      @Nonnull CqlSession session,
      @Nonnull String keyspace,
      @Nonnull String table,
      @Nonnull EmbeddingService embeddingService) {
    this(
        new CassandraRagRetrieval(
            "cassandra_rag", "Retrieves information from Cassandra", session, keyspace, table),
        embeddingService);
  }

  public SessionLevelMemoryService(
      @Nonnull CqlSession session, @Nonnull String keyspace, @Nonnull String table) {
    this(session, keyspace, table, new RedbusEmbeddingService("", ""));
  }

  public SessionLevelMemoryService(@Nonnull CqlSession session) {
    this(session, "rae", "rae_data");
  }

  /**
   * Add session to memory is happening asynchronously in a separate kafka pipeline which is much
   * more context aware.
   *
   * @param session The session to add to memory.
   * @return A completable that emits when the session is added to memory.
   */
  @Override
  public Completable addSessionToMemory(Session session) {
    // This method is handled by a separate pipeline
    // No implementation needed here
    return Completable.complete();
  }

  @Override
  @SuppressWarnings("nullness")
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    return embeddingService
        .generateEmbedding(query)
        .flatMap(
            queryEmbedding ->
                Single.fromCallable(
                    () -> {
                      // Convert query embedding to list of floats for Cassandra vector search
                      // Cassandra vector search requires vector<float> type, not list<double>
                      List<Float> embeddingList = new ArrayList<>(queryEmbedding.length);
                      for (double d : queryEmbedding) {
                        embeddingList.add((float) d);
                      }
                      // IMPORTANT: Cassandra's `vector<float, N>` is not the same as `list<float>`.
                      // Bind a native vector value, otherwise Cassandra reports "extraneous bytes".
                      CqlVector<Float> embeddingVector = CqlVector.newInstance(embeddingList);

                      // Query longterm_recall_memo table with vector similarity search
                      // Note: This uses ORDER BY with ANN (Approximate Nearest Neighbor) for
                      // vector search
                      String cql =
                          "SELECT app_id, user_id, conversation_id, created_at, helpfulness_reason, "
                              + "session_id, summary, embedding "
                              + "FROM "
                              + cassandraRagRetrieval.getKeyspace()
                              + "."
                              + cassandraRagRetrieval.getTable()
                              + " WHERE app_id = ? AND user_id = ? "
                              + "ORDER BY embedding ANN OF ? LIMIT 2";

                      var resultSet =
                          cassandraRagRetrieval
                              .getSession()
                              .execute(
                                  SimpleStatement.builder(cql)
                                      // SAI ANN is experimental and only supports ONE/LOCAL_ONE.
                                      .setConsistencyLevel(ConsistencyLevel.LOCAL_ONE)
                                      // Avoid paging (ANN doesn't support paging); LIMIT 10 fits in
                                      // one page.
                                      .setPageSize(2)
                                      .addPositionalValues(appName, userId, embeddingVector)
                                      .build());

                      // Build memory entries from results
                      ImmutableList<MemoryEntry> memories =
                          resultSet.all().stream()
                              .map(
                                  row -> {
                                    String summary = row.getString("summary");
                                    String helpfulnessReason = row.getString("helpfulness_reason");
                                    String sessionId = row.getString("session_id");

                                    // Combine summary and helpfulness reason for context
                                    String memoryText = summary;
                                    if (helpfulnessReason != null
                                        && !helpfulnessReason.trim().isEmpty()) {
                                      memoryText += "\nReason: " + helpfulnessReason;
                                    }
                                    if (sessionId != null && !sessionId.trim().isEmpty()) {
                                      memoryText += "\nSession ID: " + sessionId;
                                    }

                                    @Nonnull
                                    Part memoryPart = checkNotNull(Part.fromText(memoryText));

                                    return MemoryEntry.builder()
                                        .content(
                                            Content.builder()
                                                .parts(ImmutableList.of(memoryPart))
                                                .build())
                                        .build();
                                  })
                              .collect(toImmutableList());

                      return SearchMemoryResponse.builder().setMemories(memories).build();
                    }));
  }
}
