package com.google.adk.models.demo;

import static com.google.adk.models.demo.DemoRunner.showAgent;
import static com.google.adk.models.demo.DemoRunner.showProviders;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import java.util.List;

// mvn exec:java -Dexec.mainClass=com.google.adk.models.demo.DemoApp

public final class DemoApp {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);

    LlmAgent agent = demoAgent();
    String prompt = "Reply in one sentence: what is the Java ServiceLoader pattern?";
    // prompt = "What is your name and what can you do?";
    showAgent(agent, prompt);
    DemoRunner.run(agent, prompt);
  }

  private static LlmAgent demoAgent() {
    return LlmAgent.builder()
        .name("demo-agent") //
        .description("Helpful Assistant agent") //
        .model(MODEL)
        .instruction("You are a helpful assistant") //
        .build();
  }
}
