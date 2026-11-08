/*
 * Copyright 2026 Google LLC
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

package com.example.mcpfullresult;

import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.List;

/** Console runner that exercises {@link McpFullResultAgent}. */
public final class McpFullResultRun {
  private static final String APP_NAME = "mcp-full-result-app";
  private static final String USER_ID = "mcp-full-result-user";
  private static final String PROMPT =
      "List three dataset ids in the bigquery-public-data project.";

  private McpFullResultRun() {}

  /**
   * Entry point for the sample runner.
   *
   * @param args Unused.
   */
  public static void main(String[] args) {
    InMemorySessionService sessionService = new InMemorySessionService();
    Runner runner =
        Runner.builder()
            .agent(
                McpFullResultAgent.create(
                    requireEnv("MCP_ACCESS_TOKEN"), requireEnv("GOOGLE_CLOUD_PROJECT")))
            .appName(APP_NAME)
            .sessionService(sessionService)
            .build();
    try {
      Session session = sessionService.createSession(APP_NAME, USER_ID).blockingGet();
      System.out.println("You> " + PROMPT);
      List<Event> events =
          runner
              .runAsync(USER_ID, session.id(), Content.fromParts(Part.fromText(PROMPT)))
              .toList()
              .blockingGet();
      for (Event event : events) {
        for (FunctionResponse response : event.functionResponses()) {
          System.out.println(
              "Tool response sent to the model> " + response.response().orElseThrow());
        }
      }
      System.out.println("Agent> " + events.get(events.size() - 1).stringifyContent().strip());
    } finally {
      runner.close().blockingAwait();
    }
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException("Set the " + name + " environment variable.");
    }
    return value;
  }
}
