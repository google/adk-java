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
