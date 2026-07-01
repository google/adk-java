package com.google.adk.models.demo;

import static com.google.adk.models.demo.DemoRunner.printStreamingEvent;
import static com.google.adk.models.demo.DemoRunner.runStreaming;
import static com.google.adk.models.demo.DemoRunner.showAgent;
import static com.google.adk.models.demo.DemoRunner.showProviders;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class StreamingDemoApp {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";

  private static final String INSTRUCTION =
      """
      You are a creative writing assistant.
      Be concise;
      """;

  private static final String PROMPT =
      """
      Write a short poem (4-6 lines) about the joy
      of asynchronous programming.
      """;

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);
    LlmAgent agent = demoAgent();
    showAgent(agent, PROMPT);
    System.out.println("Streaming response (each token printed as it arrives):");
    var count = new AtomicInteger(0);
    runStreaming(agent, PROMPT, event -> printStreamingEvent(event, count));
  }

  private static LlmAgent demoAgent() {
    return LlmAgent.builder()
        .name("streaming-demo-agent") //
        .description("Writing Assistant agent") //
        .model(MODEL)
        .instruction(INSTRUCTION) //
        .build();
  }
}
