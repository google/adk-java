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

package com.google.adk.samples.a2aagent;

import io.a2a.server.PublicAgentCard;
import io.a2a.spec.AgentCard;
import io.a2a.util.Utils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Produces the {@link AgentCard} from the bundled JSON resources. */
@ApplicationScoped
public class AgentCardProducer {

  @Produces
  @PublicAgentCard
  public AgentCard agentCard() {
    try (InputStream is = getClass().getResourceAsStream("/agent/agent.json")) {
      if (is == null) {
        throw new RuntimeException("agent.json not found in resources");
      }

      // Read the JSON file content
      String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);

      // Use the SDK's built-in mapper to convert JSON string to AgentCard record
      return Utils.OBJECT_MAPPER.readValue(json, AgentCard.class);

    } catch (Exception e) {
      throw new RuntimeException("Failed to load AgentCard from JSON", e);
    }
  }
}
