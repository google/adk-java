package com.google.adk.models.demo.parallel;

import static com.google.adk.models.demo.DemoRunner.printEvent;
import static com.google.adk.models.demo.DemoRunner.run;
import static com.google.adk.models.demo.DemoRunner.showProviders;
import static java.lang.Boolean.parseBoolean;
import static java.lang.System.getProperty;

import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import java.util.List;

public final class ParallelAgentDemo {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";
  private static final String TOPIC = "the global transition to renewable energy";
  private static final boolean doFilter = parseBoolean(getProperty("filter.event", "false"));

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);

    SequentialAgent agent = new ReseachPipelineProvider(MODEL).get();
    String prompt = "Write a briefing on this topic: %s".formatted(TOPIC);
    show(prompt);

    if (doFilter) {
      run(agent, prompt, e -> filter(e));
    } else {
      run(agent, prompt, e -> printEvent(e));
    }
  }

  private static void filter(Event e) {
    if ("synthesizer".equals(e.author())) {
      printEvent(e);
    }
  }

  public static void show(String prompt) {
    System.out.println("Multi-agent system Demo - filtered output (only synthesizer output shown)");
    System.out.println("Topic: " + TOPIC);
    System.out.println("-".repeat(60));
  }
}
