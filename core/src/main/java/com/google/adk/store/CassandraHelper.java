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

package com.google.adk.store;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Manages the Cassandra connection and session.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public class CassandraHelper {

  private static final String KEYSPACE_NAME = "rae";
  private static CqlSession session;
  private static final ObjectMapper objectMapper = createObjectMapper();

  private static ObjectMapper createObjectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new Jdk8Module());
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(new GuavaModule());
    mapper.setSerializationInclusion(JsonInclude.Include.NON_ABSENT);
    mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    return mapper;
  }

  /**
   * Returns the shared object mapper instance.
   *
   * @return the object mapper
   */
  public static ObjectMapper getObjectMapper() {
    return objectMapper;
  }

  /**
   * Initializes the Cassandra connection and creates the keyspace and tables if they don't exist.
   *
   * @param sessionBuilder the CqlSessionBuilder to use
   */
  public static synchronized void initialize(CqlSessionBuilder sessionBuilder) {
    if (session == null) {
      session = sessionBuilder.build();
      createKeyspace();
      session.execute("USE " + KEYSPACE_NAME);
      createTables();
    }
  }

  /**
   * Returns the Cassandra session.
   *
   * @return the CqlSession
   */
  public static CqlSession getSession() {
    if (session == null) {
      throw new IllegalStateException("CassandraHelper has not been initialized.");
    }
    return session;
  }

  /** Closes the Cassandra session. */
  public static synchronized void close() {
    if (session != null) {
      session.close();
      session = null;
    }
  }

  private static void createKeyspace() {
    session.execute(
        "CREATE KEYSPACE IF NOT EXISTS "
            + KEYSPACE_NAME
            + " WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}");
  }

  private static void createTables() {
    // Create sessions table - normalized structure matching PostgresRunner
    System.out.println("Attempting to create the sessions table");
    session.execute(
        "CREATE TABLE IF NOT EXISTS sessions ("
            + "id TEXT, "
            + "app_name TEXT, "
            + "user_id TEXT, "
            + "state TEXT, "
            + "event_data TEXT, "
            + "last_update_time BIGINT, "
            + "PRIMARY KEY ((app_name, user_id), id))");
    System.out.println("Successfully created the sessions table");

    // Create events table - normalized event storage
    System.out.println("Attempting to create the events table");

    session.execute(
        "CREATE TABLE IF NOT EXISTS events ("
            + "id TEXT, "
            + "session_id TEXT, "
            + "author TEXT, "
            + "actions_state_delta TEXT, "
            + "actions_artifact_delta TEXT, "
            + "actions_requested_auth_configs TEXT, "
            + "actions_transfer_to_agent TEXT, "
            + "content_role TEXT, "
            + "timestamp BIGINT, "
            + "invocation_id TEXT, "
            + "created_at BIGINT, "
            + "PRIMARY KEY ((session_id), id))");
    System.out.println("Successfully created the events table");
    // Create event_content_parts table - normalized event content
    System.out.println("Attempting to create the event_content_parts table");
    session.execute(
        "CREATE TABLE IF NOT EXISTS event_content_parts ("
            + "event_id TEXT, "
            + "session_id TEXT, "
            + "part_type TEXT, "
            + "text_content TEXT, "
            + "function_call_id TEXT, "
            + "function_call_name TEXT, "
            + "function_call_args TEXT, "
            + "function_response_id TEXT, "
            + "function_response_name TEXT, "
            + "function_response_data TEXT, "
            + "created_at BIGINT, "
            + "PRIMARY KEY ((session_id), event_id))");
    System.out.println("Successfully created the event_content_parts table");
    // Create artifacts table
    System.out.println("Attempting to create the artifacts table");
    session.execute(
        "CREATE TABLE IF NOT EXISTS artifacts ("
            + "app_name TEXT, "
            + "user_id TEXT, "
            + "session_id TEXT, "
            + "filename TEXT, "
            + "version INT, "
            + "artifact_data BLOB, "
            + "PRIMARY KEY ((app_name, user_id, session_id), filename, version))");
    System.out.println("Successfully created the artifacts table");
    // Create rae_data table for RAG/memory
    System.out.println("Attempting to create the rae_data table");
    session.execute(
        "CREATE TABLE IF NOT EXISTS rae_data ("
            + "agent_name TEXT, "
            + "user_id TEXT, "
            + "turn_id TIMEUUID, "
            + "data TEXT, "
            + "embedding VECTOR<FLOAT, 3072>, "
            + "PRIMARY KEY ((agent_name, user_id), turn_id))");
    System.out.println("Successfully created the rae_data table");
    session.execute(
        "CREATE CUSTOM INDEX IF NOT EXISTS ON rae_data (embedding) USING"
            + " 'org.apache.cassandra.index.sai.StorageAttachedIndex' WITH OPTIONS = {'similarity_function': 'COSINE'}");
  }
}
