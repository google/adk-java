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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * Manages the Redis connection.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public class RedisHelper {

  private static RedisClient redisClient;
  private static StatefulRedisConnection<String, String> connection;
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
   * Initializes the Redis connection.
   *
   * @param redisUri the URI of the Redis server
   */
  public static synchronized void initialize(String redisUri) {
    if (redisClient == null) {
      redisClient = RedisClient.create(redisUri);
      connection = redisClient.connect();
    }
  }

  /**
   * Returns the Redis connection.
   *
   * @return the StatefulRedisConnection
   */
  public static StatefulRedisConnection<String, String> getConnection() {
    if (connection == null) {
      throw new IllegalStateException("RedisHelper has not been initialized.");
    }
    return connection;
  }

  /** Closes the Redis connection. */
  public static synchronized void close() {
    if (connection != null) {
      connection.close();
      connection = null;
    }
    if (redisClient != null) {
      redisClient.shutdown();
      redisClient = null;
    }
  }
}
