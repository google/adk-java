package com.example.a2a_basic;

import com.google.adk.a2a.RemoteA2AAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.a2a.client.Client;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.http.JdkA2AHttpClient;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.client.transport.jsonrpc.JSONRPCTransportConfig;
import io.a2a.spec.AgentCard;
import java.util.ArrayList;
import java.util.Random;

/** Provides local roll logic plus a remote A2A agent for the demo. */
public final class A2AAgent {

  private static final Random RANDOM = new Random();

  @SuppressWarnings("unchecked")
  public static ImmutableMap<String, Object> rollDie(int sides, ToolContext toolContext) {
    ArrayList<Integer> rolls =
        (ArrayList<Integer>) toolContext.state().computeIfAbsent("rolls", k -> new ArrayList<>());
    int result = RANDOM.nextInt(Math.max(sides, 1)) + 1;
    rolls.add(result);
    return ImmutableMap.of("result", result);
  }

  public static final LlmAgent ROLL_AGENT =
      LlmAgent.builder()
          .name("roll_agent")
          .model("gemini-2.0-flash")
          .description("Handles rolling dice of different sizes.")
          .instruction(
              """
                When asked to roll a die, always call the roll_die tool with the requested number of
                sides (default to 6 if unspecified). Do not fabricate results.
              """)
          .tools(ImmutableList.of(FunctionTool.create(A2AAgent.class, "rollDie")))
          .build();

  public static LlmAgent createRootAgent(String primeAgentBaseUrl) {
    BaseAgent primeAgent = createRemoteAgent(primeAgentBaseUrl);
    return LlmAgent.builder()
        .name("root_agent")
        .model("gemini-2.0-flash")
        .instruction(
            """
              You can roll dice locally and delegate prime-checking to the remote prime_agent.
              1. When the user asks to roll a die, route the request to roll_agent.
              2. When the user asks to check primes, delegate to prime_agent.
              3. If the user asks to roll and then check, roll_agent first, then prime_agent with the result.
              Always recap the die result before discussing primality.
            """)
        .subAgents(ImmutableList.of(ROLL_AGENT, primeAgent))
        .build();
  }

  private static BaseAgent createRemoteAgent(String primeAgentBaseUrl) {
    String agentCardUrl = primeAgentBaseUrl + "/.well-known/agent-card.json";
    AgentCard publicAgentCard =
        new A2ACardResolver(new JdkA2AHttpClient(), primeAgentBaseUrl, agentCardUrl).getAgentCard();

    Client a2aClient =
        Client.builder(publicAgentCard)
            .withTransport(JSONRPCTransport.class, new JSONRPCTransportConfig())
            .clientConfig(
                new ClientConfig.Builder()
                    .setStreaming(publicAgentCard.capabilities().streaming())
                    .build())
            .build();

    return RemoteA2AAgent.builder()
        .name(publicAgentCard.name())
        .a2aClient(a2aClient)
        .agentCard(publicAgentCard)
        .build();
  }

  private A2AAgent() {}
}
