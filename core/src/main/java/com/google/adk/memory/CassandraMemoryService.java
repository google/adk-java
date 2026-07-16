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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.data.CqlVector;
import com.google.adk.sessions.Session;
import com.google.adk.tools.retrieval.CassandraRagRetrieval;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/**
 * An implementation of {@link BaseMemoryService} that uses Cassandra for storage and retrieval.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-19
 */
public class CassandraMemoryService implements BaseMemoryService {

  private final CassandraRagRetrieval cassandraRagRetrieval;
  private final EmbeddingService embeddingService;

  public CassandraMemoryService(
      @Nonnull CassandraRagRetrieval cassandraRagRetrieval,
      @Nonnull EmbeddingService embeddingService) {
    this.cassandraRagRetrieval = cassandraRagRetrieval;
    this.embeddingService = embeddingService;
  }

  public CassandraMemoryService(
      @Nonnull CqlSession session,
      @Nonnull String keyspace,
      @Nonnull String table,
      @Nonnull EmbeddingService embeddingService) {
    this(
        new CassandraRagRetrieval(
            "cassandra_rag", "Retrieves information from Cassandra", session, keyspace, table),
        embeddingService);
  }

  public CassandraMemoryService(
      @Nonnull CqlSession session, @Nonnull String keyspace, @Nonnull String table) {
    this(session, keyspace, table, new RedbusEmbeddingService("", ""));
  }

  public CassandraMemoryService(@Nonnull CqlSession session) {
    this(session, "rae", "rae_data");
  }

  @Override
  public Completable addSessionToMemory(Session session) {
    return Completable.defer(
        () -> {
          List<Completable> insertOps = new ArrayList<>();

          for (var event : session.events()) {
            if (event.content().isEmpty() || event.content().get().parts().isEmpty()) {
              continue;
            }

            String text = event.content().get().parts().get().get(0).text().orElse(null);
            if (text == null || text.trim().isEmpty()) {
              continue;
            }

            // Create a completable for each event insertion
            Completable insertOp =
                embeddingService
                    .generateEmbedding(text)
                    .flatMapCompletable(
                        embedding ->
                            Completable.fromAction(
                                () -> {
                                  // Convert double[] to CqlVector<Float> for Cassandra VECTOR type
                                  List<Float> floatList =
                                      Arrays.stream(embedding)
                                          .mapToObj(d -> (float) d)
                                          .collect(Collectors.toList());
                                  CqlVector<Float> embeddingVector =
                                      CqlVector.newInstance(floatList);

                                  cassandraRagRetrieval
                                      .getSession()
                                      .execute(
                                          "INSERT INTO "
                                              + cassandraRagRetrieval.getKeyspace()
                                              + "."
                                              + cassandraRagRetrieval.getTable()
                                              + " (agent_name, user_id, turn_id, data, embedding) VALUES (?, ?, now(), ?, ?)",
                                          session.appName(),
                                          session.id(),
                                          text,
                                          embeddingVector);
                                }));

            insertOps.add(insertOp);
          }

          // Execute all insertions sequentially
          return Completable.concat(insertOps);
        });
  }

  @Override
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    return embeddingService
        .generateEmbedding(query)
        .flatMap(
            embedding ->
                cassandraRagRetrieval.runAsync(
                    ImmutableMap.of(
                        "embedding",
                        Arrays.stream(embedding)
                            .mapToObj(d -> (float) d)
                            .collect(Collectors.toList())),
                    null))
        .map(
            result -> {
              // The response is a List of Maps, each containing client_id, score, and data
              @SuppressWarnings("unchecked")
              List<ImmutableMap<String, Object>> contexts =
                  (List<ImmutableMap<String, Object>>) result.get("response");

              ImmutableList<MemoryEntry> memories =
                  contexts.stream()
                      .map(
                          contextMap -> {
                            // Extract the "data" field which contains the actual text
                            String data = (String) contextMap.get("data");
                            return MemoryEntry.builder()
                                .content(
                                    Content.builder()
                                        .parts(ImmutableList.of(Part.fromText(data)))
                                        .build())
                                .build();
                          })
                      .collect(toImmutableList());
              return SearchMemoryResponse.builder().setMemories(memories).build();
            });
  }
}
