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

import com.google.adk.sessions.Session;
import com.google.adk.utils.Constants;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FirestoreMemoryService is an implementation of BaseMemoryService that uses Firestore to store and
 * retrieve session memory entries.
 */
public class FirestoreMemoryService implements BaseMemoryService {

  private static final Logger logger = LoggerFactory.getLogger(FirestoreMemoryService.class);
  private static final Pattern WORD_PATTERN = Constants.WORD_PATTERN;

  private final Firestore firestore;

  /** Constructor for FirestoreMemoryService */
  public FirestoreMemoryService(Firestore firestore) {
    this.firestore = firestore;
  }

  /**
   * Adds a session to memory. This is a no-op for FirestoreMemoryService since keywords are indexed
   * when events are appended in FirestoreSessionService.
   */
  @Override
  public Completable addSessionToMemory(Session session) {
    // No-op. Keywords are indexed when events are appended in
    // FirestoreSessionService.
    return Completable.complete();
  }

  /** Searches memory entries for the given appName and userId that match the query keywords. */
  @Override
  public Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");
          Objects.requireNonNull(query, "query cannot be null");

          Set<String> queryKeywords = extractKeywords(query);

          if (queryKeywords.isEmpty()) {
            return SearchMemoryResponse.builder().build();
          }

          List<String> queryKeywordsList = new ArrayList<>(queryKeywords);
          List<List<String>> chunks = Lists.partition(queryKeywordsList, 10);

          List<ApiFuture<List<QueryDocumentSnapshot>>> futures = new ArrayList<>();
          for (List<String> chunk : chunks) {
            Query eventsQuery =
                firestore
                    .collectionGroup(Constants.EVENTS_SUBCOLLECTION_NAME)
                    .whereEqualTo("appName", appName)
                    .whereEqualTo("userId", userId)
                    .whereArrayContainsAny("keywords", chunk);
            futures.add(
                ApiFutures.transform(
                    eventsQuery.get(),
                    com.google.cloud.firestore.QuerySnapshot::getDocuments,
                    MoreExecutors.directExecutor()));
          }

          Set<String> seenEventIds = new HashSet<>();
          List<MemoryEntry> matchingMemories = new ArrayList<>();

          for (QueryDocumentSnapshot eventDoc :
              ApiFutures.allAsList(futures).get().stream()
                  .flatMap(List::stream)
                  .collect(Collectors.toList())) {
            if (seenEventIds.add(eventDoc.getId())) {
              MemoryEntry entry = memoryEntryFromDoc(eventDoc);
              if (entry != null) {
                matchingMemories.add(entry);
              }
            }
          }

          return SearchMemoryResponse.builder()
              .setMemories(ImmutableList.copyOf(matchingMemories))
              .build();
        });
  }

  /**
   * Extracts keywords from the given text by splitting on non-word characters, converting to lower
   */
  private Set<String> extractKeywords(String text) {
    Set<String> keywords = new HashSet<>();
    if (text != null && !text.isEmpty()) {
      Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
      while (matcher.find()) {
        String word = matcher.group();
        if (!Constants.STOP_WORDS.contains(word)) {
          keywords.add(word);
        }
      }
    }
    return keywords;
  }

  /** Creates a MemoryEntry from a Firestore document. */
  @SuppressWarnings("unchecked")
  private MemoryEntry memoryEntryFromDoc(QueryDocumentSnapshot doc) {
    Map<String, Object> data = doc.getData();
    if (data == null) {
      return null;
    }

    try {
      String author = (String) data.get("author");
      String timestampStr = (String) data.get("timestamp");
      Map<String, Object> contentMap = (Map<String, Object>) data.get("content");

      if (author == null || timestampStr == null || contentMap == null) {
        logger.warn("Skipping malformed event data: {}", data);
        return null;
      }

      List<Map<String, Object>> partsList = (List<Map<String, Object>>) contentMap.get("parts");
      List<Part> parts = new ArrayList<>();
      if (partsList != null) {
        for (Map<String, Object> partMap : partsList) {
          if (partMap.containsKey("text")) {
            parts.add(Part.fromText((String) partMap.get("text")));
          }
        }
      }

      return MemoryEntry.builder()
          .author(author)
          .content(Content.fromParts(parts.toArray(new Part[0])))
          .timestamp(timestampStr)
          .build();
    } catch (Exception e) {
      logger.error("Failed to parse memory entry from Firestore data: " + data, e);
      return null;
    }
  }
}
