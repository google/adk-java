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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

/**
 * Base contract for memory services.
 *
 * <p>The service provides functionalities to ingest sessions into memory so that the memory can be
 * used for user queries.
 */
public interface BaseMemoryService {

  /**
   * Adds a session to the memory service.
   *
   * <p>A session may be added multiple times during its lifetime.
   *
   * @param session The session to add.
   */
  Completable addSessionToMemory(Session session);

  /**
   * Searches for sessions that match the query asynchronously.
   *
   * @param appName The name of the application.
   * @param userId The id of the user.
   * @param query The query to search for.
   * @return A {@link SearchMemoryResponse} containing the matching memories.
   */
  Single<SearchMemoryResponse> searchMemory(String appName, String userId, String query);
}
