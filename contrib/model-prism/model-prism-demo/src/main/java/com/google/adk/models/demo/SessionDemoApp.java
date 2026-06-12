package com.google.adk.models.demo;

import static com.google.adk.models.demo.DemoRunner.showAgent;
import static com.google.adk.models.demo.DemoRunner.showProviders;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import com.google.adk.runner.InMemoryRunner;
import java.util.List;
import java.util.UUID;

public final class SessionDemoApp {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";

  private static final String INSTRUCTION =
      """
      You are a helpful travel planning assistant.
      Remember everything the user tells you about their trip.
      """;

  private static final String PROMPT1 =
      """
      Hi! My name is Alice. I'm planning a 2-week trip to Italy
      in October. I'm especially interested in history and old-master art,
      museums, galleries, cathedrals, castles and good Italian food.
      """;

  private static final String PROMPT2 =
      """
      Given what I told you, which cities should I prioritise and
      what's the weather like there during my travel month?
      """;

  private static final String PROMPT3 =
      """
      Can you give me a quick summary of everything we've discussed
      about my trip so far?
      """;

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);

    LlmAgent agent = demoAgent();
    showAgent(agent, PROMPT1);

    var runner = new InMemoryRunner(agent);
    var session = UUID.randomUUID().toString();
    var config = RunConfig.builder().autoCreateSession(true).build();

    // --- Turn 1------------------------------------------------------
    DemoRunner.runTurn(runner, config, session, PROMPT1, 1);

    // --- Turn 2------------------------------------------------------
    DemoRunner.runTurn(runner, config, session, PROMPT2, 2);

    // --- Turn 3------------------------------------------------------
    DemoRunner.runTurn(runner, config, session, PROMPT3, 3);
  }

  private static LlmAgent demoAgent() {
    return LlmAgent.builder()
        .name("travel-assistant") //
        .description("Travel Assistant agent") //
        .model(MODEL) //
        .instruction(INSTRUCTION) //
        .build();
  }
}
