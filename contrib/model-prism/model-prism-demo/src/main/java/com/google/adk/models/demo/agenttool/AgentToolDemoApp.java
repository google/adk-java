package com.google.adk.models.demo.agenttool;

import static com.google.adk.models.demo.DemoRunner.run;
import static com.google.adk.models.demo.DemoRunner.showAgent;
import static com.google.adk.models.demo.DemoRunner.showProviders;
import static org.apache.commons.lang3.StringUtils.isBlank;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import com.google.adk.tools.GoogleSearchAgentTool;
import java.util.List;

/**
 * Demonstrates {@link GoogleSearchAgentTool} - an {@link com.google.adk.tools.AgentTool} that wraps
 * a Gemini-powered search sub-agent and exposes it as a callable tool to an outer agent running on
 * a completely different (non-Google) model.
 *
 * <p>The inner Gemini agent is invoked transparently via ADK's tool-dispatch mechanism - the outer
 * Groq agent simply sees a function called {@code google_search_agent} in its tool declarations.
 */
public class AgentToolDemoApp {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";

  private static final String PROMPT =
      """
        What are the most significant large language model
        releases from major AI labs so far in 2026? Give me
        a brief summary of each with their key capabilities?
      """;

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);
    LlmAgent agent = new AgentProvider(MODEL).get();
    showAgent(agent, PROMPT);
    run(agent, PROMPT, AgentToolDemoApp::printEvent);
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
      String label =
          switch (author) {
            case "google_search_agent" -> "[Search agent - results]";
            case "research-analyst" -> "[Research analyst - final answer]";
            default -> "[" + author + "]";
          };

      System.out.println(label);
      System.out.println(content);
      System.out.printf("[%s - final response]%n", author);
      System.out.println(content);
    } else {
      System.out.printf("[%s] %s%n", author, content);
    }
  }
}
