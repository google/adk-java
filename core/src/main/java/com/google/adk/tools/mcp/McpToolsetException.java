package com.google.adk.tools.mcp;

/** Base exception for all errors originating from {@code McpToolset}. */
public class McpToolsetException extends RuntimeException {

  public McpToolsetException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Exception thrown when there's an error during MCP session initialization. */
  public static class McpInitializationException extends McpToolsetException {
    public McpInitializationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Exception thrown when there's an error during loading tools from the MCP server. */
  public static class McpToolLoadingException extends McpToolsetException {
    public McpToolLoadingException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
