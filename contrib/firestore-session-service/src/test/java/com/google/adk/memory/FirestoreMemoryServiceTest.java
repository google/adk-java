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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionGroup;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.observers.TestObserver;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Test for {@link FirestoreMemoryService}. */
@ExtendWith(MockitoExtension.class)
public class FirestoreMemoryServiceTest {

  @Mock private Firestore mockDb;
  @Mock private CollectionGroup mockCollectionGroup;
  @Mock private Query mockQuery;
  @Mock private QuerySnapshot mockQuerySnapshot;
  @Mock private QueryDocumentSnapshot mockDoc1;

  private FirestoreMemoryService memoryService;

  /** Sets up the FirestoreMemoryService and common mock behaviors before each test. */
  @BeforeEach
  public void setup() {
    memoryService = new FirestoreMemoryService(mockDb);

    lenient().when(mockDb.collectionGroup(anyString())).thenReturn(mockCollectionGroup);
    lenient().when(mockCollectionGroup.whereEqualTo(anyString(), any())).thenReturn(mockQuery);
    lenient().when(mockQuery.whereEqualTo(anyString(), any())).thenReturn(mockQuery);
    lenient().when(mockQuery.whereArrayContainsAny(anyString(), anyList())).thenReturn(mockQuery);
    lenient().when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));
  }

  /** Tests that searchMemory returns memory entries matching the search keywords. */
  @Test
  void searchMemory_withMatchingKeywords_returnsMemoryEntries() {
    // Arrange
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockDoc1));
    when(mockDoc1.getId()).thenReturn("doc1");
    when(mockDoc1.getData())
        .thenReturn(
            ImmutableMap.of(
                "author", "test-user",
                "timestamp", Instant.now().toString(),
                "content",
                    ImmutableMap.of(
                        "parts",
                        ImmutableList.of(ImmutableMap.of("text", "this is a test memory")))));

    // Act
    TestObserver<SearchMemoryResponse> testObserver =
        memoryService.searchMemory("test-app", "test-user", "search for memory").test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(
        response -> {
          assertThat(response.memories()).hasSize(1);
          assertThat(response.memories().get(0).author()).isEqualTo("test-user");
          return true;
        });
  }

  /** Tests that searchMemory returns an empty response when no matching keywords are found. */
  @Test
  void searchMemory_withNoMatchingKeywords_returnsEmptyResponse() {
    // Arrange
    when(mockQuerySnapshot.getDocuments()).thenReturn(Collections.emptyList());

    // Act
    TestObserver<SearchMemoryResponse> testObserver =
        memoryService.searchMemory("test-app", "test-user", "search for nothing").test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(response -> response.memories().isEmpty());
  }

  /** Tests that searchMemory returns an empty response when the query is empty. */
  @Test
  void searchMemory_withEmptyQuery_returnsEmptyResponse() {
    // Act
    TestObserver<SearchMemoryResponse> testObserver =
        memoryService.searchMemory("test-app", "test-user", "").test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(response -> response.memories().isEmpty());
  }

  /** Tests that searchMemory handles malformed document data gracefully. */
  @Test
  void searchMemory_withMalformedDoc_returnsEmptyMemories() {
    // Arrange
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockDoc1));
    when(mockDoc1.getId()).thenReturn("doc1");
    when(mockDoc1.getData()).thenReturn(ImmutableMap.of("author", "test-user")); // Missing fields

    // Act
    TestObserver<SearchMemoryResponse> testObserver =
        memoryService.searchMemory("test-app", "test-user", "search for memory").test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(response -> response.memories().isEmpty());
  }

  /** Tests that searchMemory handles documents with no data gracefully. */
  @Test
  void searchMemory_withDocWithNoData_returnsEmptyMemories() {
    // Arrange
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockDoc1));
    when(mockDoc1.getId()).thenReturn("doc1");
    when(mockDoc1.getData()).thenReturn(null);

    // Act
    TestObserver<SearchMemoryResponse> testObserver =
        memoryService.searchMemory("test-app", "test-user", "search for memory").test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(response -> response.memories().isEmpty());
  }

  /** Tests that searchMemory handles documents with no content parts gracefully. */
  @Test
  void searchMemory_withDocWithNoParts_returnsMemoryWithEmptyContent() {
    // Arrange
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockDoc1));
    when(mockDoc1.getId()).thenReturn("doc1");

    Map<String, Object> contentMap = new java.util.HashMap<>();
    contentMap.put("parts", null); // Explicitly set parts to null
    Map<String, Object> docData = new java.util.HashMap<>();
    docData.put("author", "test-user");
    docData.put("timestamp", Instant.now().toString());
    docData.put("content", contentMap);
    when(mockDoc1.getData()).thenReturn(docData);

    // Act
    TestObserver<SearchMemoryResponse> testObserver =
        memoryService.searchMemory("test-app", "test-user", "search for memory").test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(
        response -> {
          assertThat(response.memories()).hasSize(1);
          assertThat(response.memories().get(0).content().parts().get().isEmpty());
          return true;
        });
  }

  /** Tests that searchMemory handles documents with non-text content parts gracefully. */
  @Test
  void searchMemory_withDocWithNonTextPart_returnsMemoryWithEmptyContent() {
    // Arrange
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockDoc1));
    when(mockDoc1.getId()).thenReturn("doc1");
    when(mockDoc1.getData())
        .thenReturn(
            ImmutableMap.of(
                "author", "test-user",
                "timestamp", Instant.now().toString(),
                "content", ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of()))));

    // Act
    TestObserver<SearchMemoryResponse> testObserver =
        memoryService.searchMemory("test-app", "test-user", "search for memory").test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(
        response -> response.memories().get(0).content().parts().get().isEmpty());
  }
}
