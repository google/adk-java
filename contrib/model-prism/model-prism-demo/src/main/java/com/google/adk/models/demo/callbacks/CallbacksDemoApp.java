package com.google.adk.models.demo.callbacks;

import static com.google.adk.models.demo.DemoRunner.run;
import static com.google.adk.models.demo.DemoRunner.showAgent;
import static com.google.adk.models.demo.DemoRunner.showProviders;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import java.util.List;

public final class CallbacksDemoApp {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);
    LlmAgent agent = new CallbacksDemoAgent(MODEL).get();
    // -- Run 1: normal flow -------------------------------------
    String prompt1 = "What are the current prices of AAPL and MSFT?";
    showAgent(agent, prompt1);
    run(agent, prompt1);

    // -- Run 2: guardrail blocks the tool call -------------------------------------
    String prompt2 = "What is the price of HACK?";
    showAgent(agent, prompt2);
    run(agent, prompt2);
  }
}
