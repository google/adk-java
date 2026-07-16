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

import com.google.adk.events.Event;
import com.google.adk.store.RedisHelper;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class RedisSessionServiceIT {

  @Container
  private static final GenericContainer<?> redis =
      new GenericContainer<>("redis:latest").withExposedPorts(6379);

  private static RedisSessionService sessionService;

  @BeforeAll
  public static void setUp() {
    redis.start();
    String redisUri = "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379);
    RedisHelper.initialize(redisUri);
    sessionService = new RedisSessionService();
  }

  @AfterAll
  public static void tearDown() {
    RedisHelper.close();
    redis.stop();
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
}
