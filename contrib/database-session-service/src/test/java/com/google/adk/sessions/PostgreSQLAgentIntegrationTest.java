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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.testing.TestDatabaseConfig;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Agents using DatabaseSessionService with real PostgreSQL 16 database.
 *
 * <p>This test suite verifies that agents work correctly with PostgreSQL-backed session
 * persistence, including: - Sequential agent execution with database persistence - State
 * propagation between agents via outputKey - Event storage and retrieval - App/user/session state
 * management with database backend - JSONB storage for complex state data
 *
 * <p>Prerequisites: Start PostgreSQL test database with:
 *
 * <pre>{@code
 * docker-compose -f scripts/docker-compose.test.yml up -d postgres-test
 * }</pre>
 *
 * <p>Configuration: - Host: localhost:5433 - Database: adk_test - User: adk_user - Password:
 * adk_password
 */
@Tag("integration")
public class PostgreSQLAgentIntegrationTest {

  private static final String TEST_DB_URL = TestDatabaseConfig.POSTGRES_JDBC_URL;
  private static final String TEST_APP_NAME = "postgres-agent-integration-test";
  private static final String TEST_USER_ID = "agent-test-user";

  private DatabaseSessionService sessionService;

  @BeforeEach
  public void setUp() {
    assumeTrue(
        TestDatabaseConfig.isPostgreSQLAvailable(),
        TestDatabaseConfig.getDatabaseNotAvailableMessage("PostgreSQL"));

    sessionService = new DatabaseSessionService(TEST_DB_URL);
  }

  @AfterEach
  public void tearDown() {
    if (sessionService != null) {
      sessionService.close();
    }
  }

  @Test
  public void testSequentialAgentWithDatabasePersistence() {
    Content agentAResponse = Content.fromParts(Part.fromText("The topic is: Machine Learning"));
    TestLlm llmA =
        new TestLlm(
            ImmutableList.of(
                LlmResponse.builder()
                    .content(agentAResponse)
                    .partial(false)
                    .turnComplete(true)
                    .build()));

    LlmAgent agentA =
        LlmAgent.builder()
            .name("AgentA")
            .model(llmA)
            .instruction("Extract topic")
            .outputKey("topic")
            .build();

    Content agentBResponse =
        Content.fromParts(Part.fromText("Summary: Machine Learning is transformative"));
    TestLlm llmB =
        new TestLlm(
            ImmutableList.of(
                LlmResponse.builder()
                    .content(agentBResponse)
                    .partial(false)
                    .turnComplete(true)
                    .build()));

    LlmAgent agentB =
        LlmAgent.builder()
            .name("AgentB")
            .model(llmB)
            .instruction("Summarize topic: ${topic}")
            .outputKey("summary")
            .build();

    SequentialAgent sequential =
        SequentialAgent.builder()
            .name("SequentialAgent")
            .subAgents(ImmutableList.of(agentA, agentB))
            .build();

    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    InvocationContext ctx =
        InvocationContext.builder()
            .sessionService(sessionService)
            .session(session)
            .agent(sequential)
            .build();

    List<Event> events =
        sequential
            .runAsync(ctx)
            .flatMap(event -> ctx.sessionService().appendEvent(ctx.session(), event).toFlowable())
            .toList()
            .blockingGet();

    assertNotNull(events);
    assertTrue(events.size() >= 2, "Expected at least 2 events from sequential agents");

    assertEquals("The topic is: Machine Learning", ctx.session().state().get("topic"));
    assertEquals(
        "Summary: Machine Learning is transformative", ctx.session().state().get("summary"));

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, session.id(), Optional.empty())
            .blockingGet();

    assertNotNull(retrievedSession);
    assertEquals(session.id(), retrievedSession.id());
    assertEquals("The topic is: Machine Learning", retrievedSession.state().get("topic"));
    assertEquals(
        "Summary: Machine Learning is transformative", retrievedSession.state().get("summary"));
    assertTrue(
        retrievedSession.events().size() >= 2, "Expected at least 2 events persisted in database");
  }

  @Test
  public void testAgentWithAppAndUserStatePersistence() {
    Content configResponse =
        Content.fromParts(
            Part.fromText("{\"version\": \"2.0\", \"feature_flags\": {\"new_ui\": true}}"));
    TestLlm llmA =
        new TestLlm(
            ImmutableList.of(
                LlmResponse.builder()
                    .content(configResponse)
                    .partial(false)
                    .turnComplete(true)
                    .build()));

    LlmAgent agentA =
        LlmAgent.builder()
            .name("ConfigAgent")
            .model(llmA)
            .instruction("Return config")
            .outputKey("app:config")
            .build();

    Content prefResponse = Content.fromParts(Part.fromText("light"));
    TestLlm llmB =
        new TestLlm(
            ImmutableList.of(
                LlmResponse.builder()
                    .content(prefResponse)
                    .partial(false)
                    .turnComplete(true)
                    .build()));

    LlmAgent agentB =
        LlmAgent.builder()
            .name("PreferenceAgent")
            .model(llmB)
            .instruction("Use config: ${app:config}")
            .outputKey("user:theme")
            .build();

    SequentialAgent sequential =
        SequentialAgent.builder()
            .name("Sequential")
            .subAgents(ImmutableList.of(agentA, agentB))
            .build();

    Session session =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    InvocationContext ctx =
        InvocationContext.builder()
            .sessionService(sessionService)
            .session(session)
            .agent(sequential)
            .build();

    sequential
        .runAsync(ctx)
        .flatMap(event -> ctx.sessionService().appendEvent(ctx.session(), event).toFlowable())
        .toList()
        .blockingGet();

    assertEquals(
        "{\"version\": \"2.0\", \"feature_flags\": {\"new_ui\": true}}",
        ctx.session().state().get("app:config"));
    assertEquals("light", ctx.session().state().get("user:theme"));

    Session retrievedSession =
        sessionService
            .getSession(TEST_APP_NAME, TEST_USER_ID, session.id(), Optional.empty())
            .blockingGet();

    assertNotNull(retrievedSession);
    assertEquals(
        "{\"version\": \"2.0\", \"feature_flags\": {\"new_ui\": true}}",
        retrievedSession.state().get("app:config"));
    assertEquals("light", retrievedSession.state().get("user:theme"));
  }

  @Test
  public void testAgentStatePersistedAcrossSessions() {
    Content response1 = Content.fromParts(Part.fromText("User preference stored"));
    TestLlm llm1 =
        new TestLlm(
            ImmutableList.of(
                LlmResponse.builder()
                    .content(response1)
                    .partial(false)
                    .turnComplete(true)
                    .build()));

    LlmAgent agent1 =
        LlmAgent.builder()
            .name("PreferenceAgent")
            .model(llm1)
            .instruction("Store preference")
            .outputKey("user:language")
            .build();

    Session session1 =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    InvocationContext ctx1 =
        InvocationContext.builder()
            .sessionService(sessionService)
            .session(session1)
            .agent(agent1)
            .build();

    ConcurrentHashMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("user:language", "French");

    Event event =
        Event.builder()
            .id(java.util.UUID.randomUUID().toString())
            .author(agent1.name())
            .content(response1)
            .actions(com.google.adk.events.EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session1, event).blockingGet();

    Session session2 =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    assertNotNull(session2);
    assertEquals("French", session2.state().get("user:language"));
  }

  @Test
  public void testRegularStateIsolatedBetweenSessions() {
    Session session1 =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    ConcurrentHashMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("session_data", "session1_value");

    Event event =
        Event.builder()
            .id(java.util.UUID.randomUUID().toString())
            .author("test-agent")
            .content(Content.fromParts(Part.fromText("Session 1 data")))
            .actions(com.google.adk.events.EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session1, event).blockingGet();

    assertEquals("session1_value", session1.state().get("session_data"));

    Session session2 =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    assertNotNull(session2);
    assertNull(
        session2.state().get("session_data"), "Regular state should not persist across sessions");
  }

  @Test
  public void testAppStatePersistedAcrossSessions() {
    Session session1 =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    ConcurrentHashMap<String, Object> stateDelta = new ConcurrentHashMap<>();
    stateDelta.put("app:api_key", "key-67890");

    Event event =
        Event.builder()
            .id(java.util.UUID.randomUUID().toString())
            .author("test-agent")
            .content(Content.fromParts(Part.fromText("App config stored")))
            .actions(com.google.adk.events.EventActions.builder().stateDelta(stateDelta).build())
            .build();

    sessionService.appendEvent(session1, event).blockingGet();

    Session session2 =
        sessionService
            .createSession(TEST_APP_NAME, TEST_USER_ID, new ConcurrentHashMap<>(), null)
            .blockingGet();

    assertNotNull(session2);
    assertEquals("key-67890", session2.state().get("app:api_key"));
  }
}
