package com.google.adk.models.demo.structured;

import static com.google.adk.models.demo.DemoRunner.showAgent;
import static com.google.adk.models.demo.DemoRunner.showProviders;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.demo.DemoRunner;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import com.google.genai.types.Schema;
import java.util.List;

// mvn exec:java -Dexec.mainClass=com.google.adk.models.demo.structured.StructuredDemoApp

public final class StructuredOutputDemoApp {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";

  private static final String PROMPT =
      """
      Inception, released in 2010 and directed by Christoper Nolan,
      is a mind-bending science-fiction thriller about a skilled
      thief who steals secrets from people's dreams and is offered
      a chance to have his criminal record erased in exchange for
      planting an idea in a target's mind.
      """;

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);

    Schema schema = new MovieSchemaProvider().get();
    LlmAgent agent = new MovieExtractorProvider(MODEL, schema).get();
    System.out.println("Structured Output Demo - extract typed facts from free text");
    showAgent(agent, PROMPT);
    System.out.println("Expected output: JSON with title, director, year, genre, summary");
    DemoRunner.run(agent, PROMPT);
  }
}
