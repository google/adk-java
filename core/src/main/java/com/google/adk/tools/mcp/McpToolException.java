package com.google.adk.tools.mcp;

/**
 * Base exception for all errors originating from {@code McpTool}.
 */
public class McpToolException extends RuntimeException {

    public McpToolException(String message, Throwable cause) {super(message, cause);}

    /** Exception thrown when there's an error during MCP tool schema convert(serialize or deserialize). */
    public static class McpToolSchemaException extends McpToolException {
        public McpToolSchemaException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
