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

package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.google.adk.events.Event;
import com.google.adk.store.CassandraHelper;
import io.reactivex.rxjava3.core.Maybe;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link CassandraSessionService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-21
 */
@Testcontainers
public class CassandraSessionServiceIT {

  @Container
  private static final CassandraContainer<?> cassandra =
      new CassandraContainer<>("cassandra:latest");

  private static CassandraSessionService sessionService;

  @BeforeAll
  public static void setUp() {
    cassandra.start();
    CqlSessionBuilder sessionBuilder =
        CqlSession.builder()
            .addContactPoint(
                new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042)))
            .withLocalDatacenter(cassandra.getLocalDatacenter());
    CassandraHelper.initialize(sessionBuilder);
    sessionService = new CassandraSessionService();
  }

  @AfterAll
  public static void tearDown() {
    CassandraHelper.close();
    cassandra.stop();
  }

  @Test
  public void testCreateAndGetSession() {
    String appName = "testApp";
    String userId = "testUser";

    Session createdSession =
        sessionService.createSession(appName, userId, null, null).blockingGet();
    assertThat(createdSession).isNotNull();
    assertThat(createdSession.appName()).isEqualTo(appName);
    assertThat(createdSession.userId()).isEqualTo(userId);

    Session retrievedSession =
        sessionService
            .getSession(appName, userId, createdSession.id(), Optional.empty())
            .blockingGet();
    assertThat(retrievedSession).isNotNull();
    assertThat(retrievedSession.id()).isEqualTo(createdSession.id());
  }

  @Test
  public void testAppendEvent() {
    String appName = "testApp";
    String userId = "testUserEvent";

    Session session = sessionService.createSession(appName, userId, null, null).blockingGet();
    Event event = Event.builder().timestamp(12345L).author("user").build();

    sessionService.appendEvent(session, event).blockingGet();

    Session retrievedSession =
        sessionService.getSession(appName, userId, session.id(), Optional.empty()).blockingGet();
    assertThat(retrievedSession.events()).hasSize(1);
    assertThat(retrievedSession.events().get(0).author()).isEqualTo("user");
  }

  @Test
  public void testDeleteSession() {
    String appName = "testApp";
    String userId = "testUserDelete";

    Session createdSession =
        sessionService.createSession(appName, userId, null, null).blockingGet();
    assertThat(createdSession).isNotNull();

    sessionService.deleteSession(appName, userId, createdSession.id()).blockingAwait();

    Maybe<Session> retrievedSession =
        sessionService.getSession(appName, userId, createdSession.id(), Optional.empty());
    assertThat(retrievedSession.blockingGet()).isNull();
  }

  @Test
  public void testListSessions() {
    String appName = "testApp";
    String userId = "testUserList";

    sessionService.createSession(appName, userId, null, null).blockingGet();
    sessionService.createSession(appName, userId, null, null).blockingGet();

    List<Session> sessions = sessionService.listSessions(appName, userId).blockingGet().sessions();
    assertThat(sessions).hasSize(2);
  }

  @Test
  public void testListEvents() {
    String appName = "testApp";
    String userId = "testUserListEvents";

    Session session = sessionService.createSession(appName, userId, null, null).blockingGet();
    Event event1 = Event.builder().timestamp(12345L).author("user").build();
    Event event2 = Event.builder().timestamp(67890L).author("assistant").build();

    sessionService.appendEvent(session, event1).blockingGet();
    sessionService.appendEvent(session, event2).blockingGet();

    List<Event> events =
        sessionService.listEvents(appName, userId, session.id()).blockingGet().events();
    assertThat(events).hasSize(2);
    assertThat(events.get(0).author()).isEqualTo("user");
    assertThat(events.get(1).author()).isEqualTo("assistant");
  }
}
