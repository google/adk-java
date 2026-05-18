package com.google.adk.models.demo.mcp;

import static com.google.adk.models.demo.DemoRunner.run;
import static com.google.adk.models.demo.DemoRunner.showAgent;
import static com.google.adk.models.demo.DemoRunner.showProviders;
import static java.lang.System.getProperty;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.spi.ModelProvider;
import com.google.adk.models.spi.ModelProviderRegistry;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioServerParameters;
import java.util.List;

public final class McpStdioDemoApp {

  // to be wired via application.properties, e.g.
  // "ollama/llama3", "groq/llama-3.1-8b-instant", "gemini-2.5-flash", "openrouter/auto"
  private static final String MODEL = "groq/llama-3.1-8b-instant";

  private static final String INSTRUCTION =
      """
      You are a helpful assistant with
      access to filesystem tools.
      Use them to answer questions accurately.
      """;

  private static final String PROMPT =
      """
      List the items in the root directory.
      How many files vs directories are there?
      """;

  public static void main(String[] args) {
    // One call - discovers and registers ALL providers on the classpath
    List<ModelProvider> registered = ModelProviderRegistry.registerAll();
    showProviders(registered);
    String root = args.length > 0 ? args[0] : System.getProperty("user.home");
    System.out.println("MCP Demo - filesystem root: " + root);
    McpToolset mcp = mcp(root);
    LlmAgent agent = demoAgent(mcp);
    showAgent(agent, PROMPT);
    run(agent, PROMPT);
  }

  private static McpToolset mcp(String root) {
    String npx = getProperty("os.name").toLowerCase().contains("win") ? "npx.cmd" : "npx";
    var params =
        StdioServerParameters.builder() //
            .command(npx) //
            .args(List.of("-y", "@modelcontextprotocol/server-filesystem", root)) //
            .build() //
            .toServerParameters();
    return new McpToolset(params);
  }

  private static LlmAgent demoAgent(McpToolset mcp) {
    return LlmAgent.builder()
        .name("mcp-demo-agent") //
        .description("Helpful Assistant agent") //
        .model(MODEL) //
        .instruction(INSTRUCTION) //
        .tools(mcp) //
        .build();
  }
}
