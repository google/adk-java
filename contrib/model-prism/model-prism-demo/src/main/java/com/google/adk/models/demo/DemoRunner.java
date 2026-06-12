package com.google.adk.models.demo;

import static org.apache.commons.lang3.StringUtils.isBlank;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class DemoRunner {

  private static final String PAT = " %-20s pattern: %s%n";

  private DemoRunner() {}

  public static void run(BaseAgent agent, String prompt) {
    run(agent, prompt, DemoRunner::printEvent);
  }

  public static void runTurn(
      InMemoryRunner runner, RunConfig config, String sessionId, String prompt, int turn) {
    System.out.println("%nTurn %d - User: %s%n".formatted(turn, prompt));
    System.out.println("-".repeat(70));
    var content = Content.fromParts(Part.fromText(prompt));
    runner
        .runAsync("demo-user", sessionId, content, config)
        .blockingForEach(DemoRunner::printEvent);
  }

  public static void run(BaseAgent agent, String prompt, Consumer<Event> handler) {
    var runner = new InMemoryRunner(agent);
    var session = UUID.randomUUID().toString();
    var content = Content.fromParts(Part.fromText(prompt));
    var config = RunConfig.builder().autoCreateSession(true).build();
    runner.runAsync("demo-user", session, content, config).blockingForEach(handler::accept);
  }

  public static void printEvent(Event event) {
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
      System.out.println("-".repeat(70));
      System.out.printf("[%s - final response]%n", author);
      System.out.println(content);
    } else {
      System.out.printf("[%s] %s%n", author, content);
    }
  }

  public static void runStreaming(BaseAgent agent, String prompt, Consumer<Event> handler) {
    var runner = new InMemoryRunner(agent);
    var session = UUID.randomUUID().toString();
    var content = Content.fromParts(Part.fromText(prompt));
    var config =
        RunConfig.builder()
            .autoCreateSession(true)
            .streamingMode(RunConfig.StreamingMode.SSE)
            .build();
    runner.runAsync("demo-user", session, content, config).blockingForEach(handler::accept);
  }

  public static void printStreamingEvent(Event event, AtomicInteger count) {
    boolean isPartial = event.partial().orElse(false);
    boolean isTurnComplete = event.turnComplete().orElse(false);
    String content = event.stringifyContent();

    if (isPartial && content != null && !content.isEmpty()) {
      System.out.print(content);
      if (content.contains("\n") || content.matches(".*[.!?,;:-]$")) {
        System.out.println();
        System.out.flush();
      }
      count.incrementAndGet();
    } else if (isTurnComplete) {
      // end of streaming turn
      System.out.flush();
      System.out.println();
      System.out.println();
      System.out.println("-".repeat(70));
      System.out.printf("[Turn complete - %d partial event received]%n", count.get());
      // token usage metadata (present when the provider includes usage in the final SSE chunk)
      event.usageMetadata().ifPresent(meta -> System.out.println("[Usage metadata] " + meta));
    }
  }

  public static void showAgent(LlmAgent agent, String prompt) {
    System.out.println("\nAgent created: " + agent.name());
    System.out.println("Model: " + agent.model().map(Object::toString).orElse("none"));
    System.out.println("\nUser: " + prompt);
    System.out.println("-".repeat(60));
  }

  public static void showProviders(List<ModelProvider> registered) {
    System.out.println("Registered providers:");
    registered.forEach(p -> System.out.printf(PAT, p.getClass().getSimpleName(), p.modelPattern()));
  }
}
