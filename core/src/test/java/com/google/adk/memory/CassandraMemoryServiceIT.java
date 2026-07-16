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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.adk.store.CassandraHelper;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link CassandraMemoryService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-21
 */
@Testcontainers
public class CassandraMemoryServiceIT {

  @Container
  private static final CassandraContainer<?> cassandra =
      new CassandraContainer<>("cassandra:latest");

  private static CassandraMemoryService memoryService;

  @BeforeAll
  public static void setUp() {
    cassandra.start();
    CqlSessionBuilder sessionBuilder =
        CqlSession.builder()
            .addContactPoint(
                new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042)))
            .withLocalDatacenter(cassandra.getLocalDatacenter());
    CassandraHelper.initialize(sessionBuilder);
    memoryService = new CassandraMemoryService(CassandraHelper.getSession(), "rae", "rae_data");
  }

  @AfterAll
  public static void tearDown() {
    CassandraHelper.close();
    cassandra.stop();
  }

  @Test
  public void testAddAndSearchMemory() {
    String appName = "testApp";
    String userId = "testUser";
    Session session =
        Session.builder("testSession")
            .appName(appName)
            .userId(userId)
            .events(
                List.of(
                    Event.builder()
                        .timestamp(1L)
                        .author("user")
                        .content(
                            Content.builder().parts(List.of(Part.fromText("hello world"))).build())
                        .build(),
                    Event.builder()
                        .timestamp(2L)
                        .author("model")
                        .content(
                            Content.builder()
                                .parts(List.of(Part.fromText("goodbye world")))
                                .build())
                        .build()))
            .build();

    memoryService.addSessionToMemory(session).blockingAwait();

    SearchMemoryResponse response =
        memoryService.searchMemory(appName, userId, "hello").blockingGet();
    assertThat(response.memories()).hasSize(1);
    assertThat(response.memories().get(0).content().parts().get().get(0).text().get())
        .isEqualTo("hello world");
  }

  @Test
  public void testAddAndSearchMemoryMultiTurn() {
    String appName = "testApp";
    String userId = "testUser";
    Session session1 =
        Session.builder("testSession")
            .appName(appName)
            .userId(userId)
            .events(
                List.of(
                    Event.builder()
                        .timestamp(1L)
                        .author("user")
                        .content(
                            Content.builder()
                                .parts(List.of(Part.fromText("first message")))
                                .build())
                        .build()))
            .build();
    Session session2 =
        Session.builder("testSession")
            .appName(appName)
            .userId(userId)
            .events(
                List.of(
                    Event.builder()
                        .timestamp(2L)
                        .author("model")
                        .content(
                            Content.builder()
                                .parts(List.of(Part.fromText("second message")))
                                .build())
                        .build()))
            .build();

    memoryService.addSessionToMemory(session1).blockingAwait();
    memoryService.addSessionToMemory(session2).blockingAwait();

    SearchMemoryResponse response =
        memoryService.searchMemory(appName, userId, "message").blockingGet();
    assertThat(response.memories()).hasSize(2);
    assertThat(response.memories().get(0).content().parts().get().get(0).text().get())
        .isEqualTo("first message");
    assertThat(response.memories().get(1).content().parts().get().get(0).text().get())
        .isEqualTo("second message");
  }
}
