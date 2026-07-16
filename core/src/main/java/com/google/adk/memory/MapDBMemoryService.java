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

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A memory service that uses MapDB as a vector store. */
public class MapDBMemoryService implements BaseMemoryService {

  private final MapDBVectorStore vectorStore;
  private final EmbeddingService embeddingService;

  public MapDBMemoryService(MapDBVectorStore vectorStore, EmbeddingService embeddingService) {
    this.vectorStore = vectorStore;
    this.embeddingService = embeddingService;
  }

  @Override
  public Completable addSessionToMemory(Session session) {
    return Completable.concat(
        Flowable.fromStream(session.events().stream())
            .filter(
                event -> event.content().isPresent() && !event.content().get().parts().isEmpty())
            .concatMap(
                event ->
                    Flowable.fromStream(event.content().get().parts().get().stream())
                        .filter(part -> part.text().isPresent())
                        .map(part -> processPart(session, event, part)))
            .collect(ImmutableList.toImmutableList())
            .blockingGet());
  }

  private Completable processPart(Session session, Event event, Part part) {
    return embeddingService
        .generateEmbedding(part.text().get())
        .flatMapCompletable(
            embedding ->
                Completable.fromAction(
                    () -> {
                      Map<String, Object> metadata = new HashMap<>();
                      metadata.put("appName", session.appName());
                      metadata.put("userId", session.userId());
                      metadata.put("author", event.author());
                      metadata.put("timestamp", event.timestamp());
                      metadata.put("content", part.text().get());
                      Vector vector =
                          new Vector(
                              UUID.randomUUID().toString(), part.text().get(), embedding, metadata);
                      vectorStore.insertVector(vector);
                    }));
  }

  @Override
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    return embeddingService
        .generateEmbedding(query)
        .map(
            embedding -> {
              List<Vector> similarVectors = vectorStore.searchTopNVectors(embedding, 0.8, 10);
              ImmutableList.Builder<MemoryEntry> memories = ImmutableList.builder();
              for (Vector vector : similarVectors) {
                Map<String, Object> metadata = vector.getMetadata();
                if (metadata.get("appName").equals(appName)
                    && metadata.get("userId").equals(userId)) {
                  memories.add(
                      MemoryEntry.builder()
                          .content(
                              Content.builder()
                                  .role("user")
                                  .parts(
                                      ImmutableList.of(
                                          Part.fromText((String) metadata.get("content"))))
                                  .build())
                          .author((String) metadata.get("author"))
                          .timestamp(formatTimestamp((long) metadata.get("timestamp")))
                          .build());
                }
              }
              return SearchMemoryResponse.builder().setMemories(memories.build()).build();
            });
  }

  private String formatTimestamp(long timestamp) {
    return Instant.ofEpochSecond(timestamp).toString();
  }
}
