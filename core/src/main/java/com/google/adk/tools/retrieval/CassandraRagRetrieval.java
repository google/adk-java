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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.datastax.oss.driver.api.core.CqlSession;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A retrieval tool that fetches context from Cassandra.
 *
 * <p>This tool allows to retrieve relevant information based on a query using Cassandra.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-19
 */
public class CassandraRagRetrieval extends BaseRetrievalTool {
  private static final Logger logger = LoggerFactory.getLogger(CassandraRagRetrieval.class);
  private final CqlSession session;
  private final String keyspace;
  private final String table;

  public CassandraRagRetrieval(
      @Nonnull String name,
      @Nonnull String description,
      @Nonnull CqlSession session,
      @Nonnull String keyspace,
      @Nonnull String table) {
    super(name, description);
    this.session = session;
    this.keyspace = keyspace;
    this.table = table;
  }

  public CqlSession getSession() {
    return session;
  }

  public String getKeyspace() {
    return keyspace;
  }

  public String getTable() {
    return table;
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {

    List<Double> embedding = (List<Double>) args.get("embedding");

    int topK = (int) args.getOrDefault("top_k", 5);

    float similarityThreshold = (float) args.getOrDefault("similarity_threshold", 0.85f);

    String keyspace = (String) args.getOrDefault("keyspace", this.keyspace);

    String table = (String) args.getOrDefault("table", this.table);

    String embeddingColumn = (String) args.get("embedding_column");

    logger.info("CassandraRagRetrieval.runAsync called with embedding");

    return annSearch(
        session, keyspace, table, embeddingColumn, embedding, topK, similarityThreshold);
  }

  public Single<Map<String, Object>> annSearch(
      CqlSession session,
      String keyspace,
      String table,
      String embeddingColumn,
      List<Double> embedding,
      int topK,
      float similarityThreshold) {

    return Single.fromCallable(
        () -> {
          String cql =
              String.format(
                  "SELECT agent_name, similarity_cosine(embedding, ?) as score, data FROM %s.%s ORDER BY"
                      + " %s ANN OF ? LIMIT ?",
                  keyspace, table, embeddingColumn);
          var prepared = session.prepare(cql);
          var rows = session.execute(prepared.bind(embedding, embedding, topK));
          var contexts =
              rows.all().stream()
                  .filter(row -> row.getFloat("score") > similarityThreshold)
                  .map(
                      row ->
                          ImmutableMap.of(
                              "agent_name",
                              row.getString("agent_name"),
                              "score",
                              row.getFloat("score"),
                              "data",
                              row.getString("data")))
                  .collect(toImmutableList());

          logger.info("Returning contexts: {}", contexts);

          return ImmutableMap.of("response", contexts);
        });
  }
}
