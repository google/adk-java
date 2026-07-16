/// *
// * Copyright 2025 Google LLC
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
// package com.google.adk.memory;
//
// import static com.google.common.truth.Truth.assertThat;
// import static org.mockito.ArgumentMatchers.anyString;
// import static org.mockito.Mockito.when;
//
// import com.google.adk.events.Event;
// import com.google.adk.sessions.Session;
// import com.google.genai.types.Content;
// import com.google.genai.types.Part;
// import io.reactivex.rxjava3.core.Single;
// import java.io.File;
// import java.io.IOException;
// import java.util.List;
// import org.junit.After;
// import org.junit.Before;
// import org.junit.Test;
// import org.mockito.Mock;
//
// public class MapDBMemoryServiceTest {
//
//  @Mock private EmbeddingService embeddingService;
//
//  private MapDBVectorStore vectorStore;
//  private MapDBMemoryService memoryService;
//  private File tempDbFile;
//
//  @Before
//  public void setUp() throws IOException {
//    // MockitoAnnotations.openMocks(this);
//    tempDbFile = File.createTempFile("test-", ".db");
//    vectorStore = new MapDBVectorStore(tempDbFile.getAbsolutePath(), "test-collection");
//    memoryService = new MapDBMemoryService(vectorStore, embeddingService);
//  }
//
//  @After
//  public void tearDown() {
//    vectorStore.close();
//    tempDbFile.delete();
//  }
//
//  @Test
//  public void testAddAndSearchMemory() {
//    // Arrange
//    when(embeddingService.generateEmbedding(anyString()))
//        .thenReturn(Single.just(new double[] {1.0, 1.0}));
//
//    String appName = "testApp";
//    String userId = "testUser";
//    Session session =
//        Session.builder("testSession")
//            .appName(appName)
//            .userId(userId)
//            .events(
//                List.of(
//                    Event.builder()
//                        .timestamp(1L)
//                        .author("user")
//                        .content(
//                            Content.builder().parts(List.of(Part.fromText("hello
// world"))).build())
//                        .build(),
//                    Event.builder()
//                        .timestamp(2L)
//                        .author("model")
//                        .content(
//                            Content.builder()
//                                .parts(List.of(Part.fromText("goodbye world")))
//                                .build())
//                        .build()))
//            .build();
//
//    // Act
//    memoryService.addSessionToMemory(session).blockingAwait();
//    SearchMemoryResponse response =
//        memoryService.searchMemory("test-app", "test-user", "hello").blockingGet();
//
//    // Assert
//    assertThat(response.memories()).hasSize(1);
//    MemoryEntry memory = response.memories().get(0);
//    assertThat(response.memories().get(0).content().parts().get().get(0).text().get())
//        .isEqualTo("hello world");
//    assertThat(memory.author()).isEqualTo("user");
//  }
// }
