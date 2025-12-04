/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.plugins;

import static java.util.stream.Collectors.joining;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plugin that logs important information at each callback point.
 *
 * <p>This plugin helps printing all critical events in the console.
 */
public class LoggingPlugin extends BasePlugin {
  private static final Logger logger = LoggerFactory.getLogger(LoggingPlugin.class);
  private static final int MAX_CONTENT_LENGTH = 200;
  private static final int MAX_ARGS_LENGTH = 300;

  public LoggingPlugin(String name) {
    super(name);
  }

  public LoggingPlugin() {
    super("logging_plugin");
  }

  private void log(String message) {
    logger.info("[{}] {}", name, message);
  }

  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    return Maybe.fromAction(
        () -> {
          log("🚀 USER MESSAGE RECEIVED");
          log("   Invocation ID: " + invocationContext.invocationId());
          log("   Session ID: " + invocationContext.session().id());
          log("   User ID: " + invocationContext.userId());
          log("   App Name: " + invocationContext.appName());
          log("   Root Agent: " + invocationContext.agent().name());
          log("   User Content: " + formatContent(Optional.ofNullable(userMessage)));
          invocationContext.branch().ifPresent(branch -> log("   Branch: " + branch));
        });
  }

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    return Maybe.fromAction(
        () -> {
          log("🏃 INVOCATION STARTING");
          log("   Invocation ID: " + invocationContext.invocationId());
          log("   Starting Agent: " + invocationContext.agent().name());
        });
  }

  @Override
  public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
    return Maybe.fromAction(
        () -> {
          log("📢 EVENT YIELDED");
          log("   Event ID: " + event.id());
          log("   Author: " + event.author());
          log("   Content: " + formatContent(event.content()));
          log("   Final Response: " + event.finalResponse());

          if (!event.functionCalls().isEmpty()) {
            String funcCalls =
                event.functionCalls().stream()
                    .map(fc -> fc.name().orElse("Unknown"))
                    .collect(joining(", "));
            log("   Function Calls: [" + funcCalls + "]");
          }

          if (!event.functionResponses().isEmpty()) {
            String funcResponses =
                event.functionResponses().stream()
                    .map(fr -> fr.name().orElse("Unknown"))
                    .collect(joining(", "));
            log("   Function Responses: [" + funcResponses + "]");
          }

          event
              .longRunningToolIds()
              .ifPresent(
                  ids -> {
                    if (!ids.isEmpty()) {
                      log("   Long Running Tools: " + ids);
                    }
                  });
        });
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    return Completable.fromAction(
        () -> {
          log("✅ INVOCATION COMPLETED");
          log("   Invocation ID: " + invocationContext.invocationId());
          log("   Final Agent: " + invocationContext.agent().name());
        });
  }

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.fromAction(
        () -> {
          log("🤖 AGENT STARTING");
          log("   Agent Name: " + agent.name());
          log("   Invocation ID: " + callbackContext.invocationId());
          callbackContext.branch().ifPresent(branch -> log("   Branch: " + branch));
        });
  }

  @Override
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.fromAction(
        () -> {
          log("🤖 AGENT COMPLETED");
          log("   Agent Name: " + agent.name());
          log("   Invocation ID: " + callbackContext.invocationId());
        });
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    return Maybe.fromAction(
        () -> {
          LlmRequest request = llmRequest.build();
          log("🧠 LLM REQUEST");
          log("   Model: " + request.model().orElse("default"));
          log("   Agent: " + callbackContext.agentName());

          request
              .getFirstSystemInstruction()
              .ifPresent(
                  sysInstruction -> {
                    String truncatedInstruction = sysInstruction;
                    if (truncatedInstruction.length() > MAX_CONTENT_LENGTH) {
                      truncatedInstruction =
                          truncatedInstruction.substring(0, MAX_CONTENT_LENGTH) + "...";
                    }
                    log("   System Instruction: '" + truncatedInstruction + "'");
                  });

          if (!request.tools().isEmpty()) {
            String toolNames = String.join(", ", request.tools().keySet());
            log("   Available Tools: [" + toolNames + "]");
          }
        });
  }

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    return Maybe.fromAction(
        () -> {
          log("🧠 LLM RESPONSE");
          log("   Agent: " + callbackContext.agentName());

          if (llmResponse.errorCode().isPresent()) {
            log("   ❌ ERROR - Code: " + llmResponse.errorCode().get());
            llmResponse.errorMessage().ifPresent(msg -> log("   Error Message: " + msg));
          } else {
            log("   Content: " + formatContent(llmResponse.content()));
            llmResponse.partial().ifPresent(partial -> log("   Partial: " + partial));
            llmResponse
                .turnComplete()
                .ifPresent(turnComplete -> log("   Turn Complete: " + turnComplete));
          }

          llmResponse
              .usageMetadata()
              .ifPresent(
                  usage -> {
                    log(
                        "   Token Usage - Input: "
                            + usage.promptTokenCount()
                            + ", Output: "
                            + usage.candidatesTokenCount());
                  });
        });
  }

  @Override
  public Maybe<LlmResponse> onModelErrorCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
    return Maybe.fromAction(
        () -> {
          log("🧠 LLM ERROR");
          log("   Agent: " + callbackContext.agentName());
          log("   Error: " + error.getMessage());
          logger.error("[{}] LLM Error", name, error);
        });
  }

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext.Builder toolContext) {
    return Maybe.fromAction(
        () -> {
          ToolContext tc = toolContext.build();
          log("🔧 TOOL STARTING");
          log("   Tool Name: " + tool.name());
          log("   Agent: " + tc.agentName());
          tc.functionCallId().ifPresent(id -> log("   Function Call ID: " + id));
          log("   Arguments: " + formatArgs(toolArgs));
        });
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext.Builder toolContext,
      Map<String, Object> result) {
    return Maybe.fromAction(
        () -> {
          ToolContext tc = toolContext.build();
          log("🔧 TOOL COMPLETED");
          log("   Tool Name: " + tool.name());
          log("   Agent: " + tc.agentName());
          tc.functionCallId().ifPresent(id -> log("   Function Call ID: " + id));
          log("   Result: " + formatArgs(result));
        });
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext.Builder toolContext,
      Throwable error) {
    return Maybe.fromAction(
        () -> {
          ToolContext tc = toolContext.build();
          log("🔧 TOOL ERROR");
          log("   Tool Name: " + tool.name());
          log("   Agent: " + tc.agentName());
          tc.functionCallId().ifPresent(id -> log("   Function Call ID: " + id));
          log("   Arguments: " + formatArgs(toolArgs));
          log("   Error: " + error.getMessage());
          logger.error("[{}] Tool Error", name, error);
        });
  }

  private String formatContent(Optional<Content> contentOptional) {
    if (contentOptional.isEmpty()) {
      return "None";
    }
    Content content = contentOptional.get();
    if (content.parts().isEmpty() || content.parts().get().isEmpty()) {
      return "None";
    }

    String combinedText =
        content.parts().get().stream()
            .map(part -> part.text().orElse(""))
            .collect(joining("\n"))
            .trim();

    if (combinedText.length() > MAX_CONTENT_LENGTH) {
      return combinedText.substring(0, MAX_CONTENT_LENGTH) + "...";
    }
    return combinedText;
  }

  private String formatArgs(Map<String, Object> args) {
    if (args == null || args.isEmpty()) {
      return "{}";
    }
    String argsStr =
        args.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(joining(", "));
    if (argsStr.length() > MAX_ARGS_LENGTH) {
      return "{" + argsStr.substring(0, MAX_ARGS_LENGTH) + "...}";
    }
    return "{" + argsStr + "}";
  }
}
