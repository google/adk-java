package com.google.adk.models.demo.multiagent;

import static com.google.adk.models.demo.DemoRunner.run;
import static com.google.adk.models.demo.DemoRunner.showProviders;
import static org.apache.commons.lang3.StringUtils.isBlank;

import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import java.util.List;

public final class MultiAgentDemoApp {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";
  private static final String TOPIC = "the future of renewable energy";

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);

    SequentialAgent agent = new ContentPipelineProvider(MODEL).get();
    String prompt = "Write a briefing on this topic: %s".formatted(TOPIC);
    show(prompt);
    run(agent, prompt, e -> printEvent(e));
  }

  public static void show(String prompt) {
    System.out.println("Multi-agent system Demo - researcher -> (writer <-> critic) x 2");
    System.out.println("-".repeat(70));
    System.out.println("Topic: " + TOPIC);
    System.out.println("-".repeat(70));
  }

  private static void printEvent(Event event) {
    String author = event.author();
    String content = event.stringifyContent();
    boolean isPartial = event.partial().orElse(false);
    boolean isTurnComplete = event.turnComplete().orElse(false);
    if (isBlank(content)) {
      return;
    }
    if (isPartial) {
      System.out.println(content);
    } else if (isTurnComplete) {
      System.out.println();
      System.out.println("-".repeat(60));
      String label =
          switch (author) {
            case "researcher" -> "[Researcher - notes complete]";
            case "writer" -> "[Writer - draft complete]";
            case "critic" -> "[Critic - feedback]";
            default -> "[" + author + " - complete]";
          };
      System.out.println(label);
      System.out.println(content);
      System.out.println("-".repeat(60));
    } else {
      System.out.printf("[%s] %s%n", author, content);
    }
  }
}
