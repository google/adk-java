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
package com.google.adk.tutorials;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.web.AdkWebServer;
import java.util.Map;

public class CodeAssistant {

  public static final BaseAgent ROOT_AGENT =
      LlmAgent.builder()
          .name("code_assistant")
          .model("gemini-2.0-flash")
          .description(
              "An AI code assistant that helps with code generation, review, debugging, and explanation.")
          .instruction(
              "You are a helpful code assistant with expertise in multiple programming languages including Java, Python, JavaScript, and more. "
                  + "You can help users with:\n"
                  + "- Writing and generating code snippets\n"
                  + "- Reviewing code and suggesting improvements\n"
                  + "- Explaining code functionality\n"
                  + "- Debugging and fixing errors\n"
                  + "- Providing best practices and security guidance\n\n"
                  + "When providing code, ensure it is syntactically correct, well-commented, and follows best practices. "
                  + "Always explain your reasoning when suggesting changes or solutions.")
          .tools(
              FunctionTool.create(CodeAssistant.class, "generateCode"),
              FunctionTool.create(CodeAssistant.class, "reviewCode"),
              FunctionTool.create(CodeAssistant.class, "explainCode"),
              FunctionTool.create(CodeAssistant.class, "debugCode"))
          .build();

  public static Map<String, String> generateCode(
      @Schema(
              name = "language",
              description =
                  "The programming language for the code (e.g., Java, Python, JavaScript)")
          String language,
      @Schema(name = "task", description = "Description of what the code should do") String task) {
    return Map.of(
        "status",
        "success",
        "language",
        language,
        "task",
        task,
        "message",
        "Code generation request received for " + language + " to: " + task);
  }

  public static Map<String, String> reviewCode(
      @Schema(name = "code", description = "The code to review") String code,
      @Schema(name = "language", description = "The programming language of the code (optional)")
          String language) {
    return Map.of(
        "status",
        "success",
        "language",
        language != null ? language : "detected",
        "message",
        "Code review request received. Analyzing code quality, best practices, and potential improvements.");
  }

  public static Map<String, String> explainCode(
      @Schema(name = "code", description = "The code to explain") String code,
      @Schema(
              name = "detail_level",
              description = "Level of detail for explanation (brief, detailed, comprehensive)")
          String detailLevel) {
    return Map.of(
        "status",
        "success",
        "detail_level",
        detailLevel != null ? detailLevel : "detailed",
        "message",
        "Code explanation request received. Will provide "
            + (detailLevel != null ? detailLevel : "detailed")
            + " explanation of the code.");
  }

  public static Map<String, String> debugCode(
      @Schema(name = "code", description = "The code with the bug") String code,
      @Schema(name = "error_message", description = "The error message or description of the issue")
          String errorMessage) {
    return Map.of(
        "status",
        "success",
        "error_message",
        errorMessage,
        "message",
        "Debugging request received. Analyzing code to identify and fix the issue: "
            + errorMessage);
  }

  public static void main(String[] args) {
    AdkWebServer.start(ROOT_AGENT);
  }
}
