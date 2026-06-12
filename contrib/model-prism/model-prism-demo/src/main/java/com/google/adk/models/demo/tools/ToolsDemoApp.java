package com.google.adk.models.demo.tools;

import static com.google.adk.models.demo.DemoRunner.run;
import static com.google.adk.models.demo.DemoRunner.showAgent;
import static com.google.adk.models.demo.DemoRunner.showProviders;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import java.util.ArrayList;
import java.util.List;

public final class ToolsDemoApp {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";

  private static final String PROMPT =
      """
      What time is right now?
      Also, what's the weather like in Phoenix?
      And finally, what is 42 multiplied by 137?
      """;

  private static final String INSTRUCTION =
      """
      You are a helpful assistant.
      Use the available tools to answer questions accurately.
      """;

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);
    LlmAgent agent = demoAgent();
    showAgent(agent, PROMPT);
    run(agent, PROMPT);
  }

  private static LlmAgent demoAgent() {
    return LlmAgent.builder()
        .name("tools-demo-agent") //
        .description("Helpful Assistant agent with tools") //
        .model(MODEL) //
        .instruction(INSTRUCTION) //
        .tools(tools()) //
        .build();
  }

  private static List<BaseTool> tools() {
    List<BaseTool> tools = new ArrayList<>();
    tools.add(FunctionTool.create(DemoTools.class, "getCurrentTime"));
    tools.add(FunctionTool.create(DemoTools.class, "getWeather"));
    tools.add(FunctionTool.create(DemoTools.class, "calculate"));
    return tools;
  }
}
