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

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class InMemoryMemoryServiceTimestampTest {

  private final InMemoryMemoryService memoryService = new InMemoryMemoryService();

  /** Memory timestamps are rendered from epoch milliseconds, matching {@link Event#timestamp()}. */
  @Test
  public void searchMemory_formatsTimestampAsEpochMillis() {
    long epochMillis = 1_700_000_000_000L;
    Session session =
        Session.builder("session-1")
            .appName("app")
            .userId("user")
            .state(new ConcurrentHashMap<>())
            .events(
                ImmutableList.of(
                    Event.builder()
                        .author("user")
                        .content(
                            Content.builder()
                                .role("user")
                                .parts(Part.fromText("My name is James"))
                                .build())
                        .timestamp(epochMillis)
                        .build()))
            .build();

    memoryService.addSessionToMemory(session).blockingAwait();

    SearchMemoryResponse response = memoryService.searchMemory("app", "user", "name").blockingGet();

    assertThat(response.memories()).hasSize(1);
    assertThat(response.memories().get(0).timestamp())
        .isEqualTo(Instant.ofEpochMilli(epochMillis).toString());
  }
}
