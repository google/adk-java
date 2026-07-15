package com.example;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.HashMap;
import java.util.Map;

public final class CoreCallbacks {
  private CoreCallbacks() {}

  public static final Callbacks.BeforeAgentCallback BEFORE_AGENT_CALLBACK1 =
      (CallbackContext ctx) -> {
        System.out.println("@before_agent_callback1");
        return Maybe.empty();
      };

  public static final Callbacks.BeforeAgentCallback BEFORE_AGENT_CALLBACK2 =
      (CallbackContext ctx) -> {
        System.out.println("@before_agent_callback2");
        return Maybe.empty();
      };

  public static final Callbacks.BeforeAgentCallback BEFORE_AGENT_CALLBACK3 =
      (CallbackContext ctx) -> {
        System.out.println("@before_agent_callback3");
        return Maybe.empty();
      };

  public static final Callbacks.AfterAgentCallback AFTER_AGENT_CALLBACK1 =
      (CallbackContext ctx) -> {
        System.out.println("@after_agent_callback1");
        return Maybe.empty();
      };

  public static final Callbacks.AfterAgentCallback AFTER_AGENT_CALLBACK2 =
      (CallbackContext ctx) -> {
        System.out.println("@after_agent_callback2");
        Content content =
            Content.builder()
                .role("model")
                .parts(
                    java.util.List.of(
                        Part.builder().text("(stopped) after_agent_callback2").build()))
                .build();
        return Maybe.just(content);
      };

  public static final Callbacks.AfterAgentCallback AFTER_AGENT_CALLBACK3 =
      (CallbackContext ctx) -> {
        System.out.println("@after_agent_callback3");
        return Maybe.empty();
      };

  public static final Callbacks.BeforeModelCallback BEFORE_MODEL_CALLBACK =
      (CallbackContext ctx, LlmRequest.Builder requestBuilder) -> {
        System.out.println("@before_model_callback");
        return Maybe.empty();
      };

  public static final Callbacks.AfterModelCallback AFTER_MODEL_CALLBACK =
      (CallbackContext ctx, LlmResponse response) -> {
        System.out.println("@after_model_callback");
        return Maybe.empty();
      };

  public static final Callbacks.BeforeToolCallback BEFORE_TOOL_CALLBACK1 =
      (InvocationContext invocationContext,
          BaseTool tool,
          Map<String, Object> input,
          ToolContext toolContext) -> {
        System.out.println("@before_tool_callback1");
        return Maybe.empty();
      };

  public static final Callbacks.BeforeToolCallback BEFORE_TOOL_CALLBACK2 =
      (InvocationContext invocationContext,
          BaseTool tool,
          Map<String, Object> input,
          ToolContext toolContext) -> {
        System.out.println("@before_tool_callback2");
        return Maybe.empty();
      };

  public static final Callbacks.BeforeToolCallback BEFORE_TOOL_CALLBACK3 =
      (InvocationContext invocationContext,
          BaseTool tool,
          Map<String, Object> input,
          ToolContext toolContext) -> {
        System.out.println("@before_tool_callback3");
        return Maybe.empty();
      };

  public static final Callbacks.AfterToolCallback AFTER_TOOL_CALLBACK1 =
      (InvocationContext invocationContext,
          BaseTool tool,
          Map<String, Object> input,
          ToolContext toolContext,
          Object response) -> {
        System.out.println("@after_tool_callback1");
        return Maybe.empty();
      };

  public static final Callbacks.AfterToolCallback AFTER_TOOL_CALLBACK2 =
      (InvocationContext invocationContext,
          BaseTool tool,
          Map<String, Object> input,
          ToolContext toolContext,
          Object response) -> {
        System.out.println("@after_tool_callback2");
        Map<String, Object> modified = new HashMap<>();
        modified.put("test", "after_tool_callback2");
        modified.put("response", response);
        return Maybe.just(modified);
      };

  public static final Callbacks.AfterToolCallback AFTER_TOOL_CALLBACK3 =
      (InvocationContext invocationContext,
          BaseTool tool,
          Map<String, Object> input,
          ToolContext toolContext,
          Object response) -> {
        System.out.println("@after_tool_callback3");
        return Maybe.empty();
      };

  public static final Callbacks.BeforeAgentCallback BEFORE_AGENT_CALLBACK =
      (CallbackContext ctx) -> {
        System.out.println("@before_agent_callback");
        return Maybe.empty();
      };

  public static final Callbacks.AfterAgentCallback AFTER_AGENT_CALLBACK =
      (CallbackContext ctx) -> {
        System.out.println("@after_agent_callback");
        return Maybe.empty();
      };
}
