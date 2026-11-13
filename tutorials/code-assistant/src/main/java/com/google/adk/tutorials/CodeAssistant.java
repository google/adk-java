/*
 * Copyright 2026 Google LLC
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public class CodeAssistant {

  private static final Pattern PUBLIC_TYPE_PATTERN =
      Pattern.compile(
          "\\bpublic\\s+(?:abstract\\s+|final\\s+|sealed\\s+)*"
              + "(?:class|interface|enum|record|@interface)\\s+(\\w+)");

  private static final LlmAgent CODE_WRITER =
      LlmAgent.builder()
          .name("code_writer")
          .model("gemini-3.8-flash")
          .description("Writes and explains code snippets in Java, Python, and JavaScript.")
          .instruction(
              "You are a code writer. Produce correct, idiomatic code for the user's request, "
                  + "with brief explanations of the key decisions. When the user provides Java "
                  + "code, call compileJava to verify it actually compiles, and fix any "
                  + "diagnostics the compiler reports before answering. Keep answers focused on "
                  + "the code itself.")
          .tools(FunctionTool.create(CodeAssistant.class, "compileJava"))
          .build();

  private static final LlmAgent CODE_REVIEWER =
      LlmAgent.builder()
          .name("code_reviewer")
          .model("gemini-3.8-flash")
          .description("Reviews code for correctness, style, and best practices.")
          .instruction(
              "You are a code reviewer. When reviewing Java code, call compileJava first and "
                  + "base your findings on the real compiler diagnostics (line, column, "
                  + "message) together with your own analysis of style and best practices. "
                  + "Report concrete issues with line references, ordered by severity, and "
                  + "suggest fixes. If the code is not Java, review it directly without the "
                  + "compile tool.")
          .tools(FunctionTool.create(CodeAssistant.class, "compileJava"))
          .build();

  private static final LlmAgent DEBUGGER =
      LlmAgent.builder()
          .name("debugger")
          .model("gemini-3.8-flash")
          .description("Diagnoses compile errors, runtime exceptions, and logic bugs.")
          .instruction(
              "You are a debugger. When the user reports a Java compile or runtime problem, "
                  + "call compileJava on the code to reproduce the compiler diagnostics, then "
                  + "explain the root cause and the minimal fix. For runtime errors, reason "
                  + "from the stack trace and the code; call compileJava when a snippet is "
                  + "provided to check it still parses. Be concrete: name the line, the cause, "
                  + "and the fix.")
          .tools(FunctionTool.create(CodeAssistant.class, "compileJava"))
          .build();

  public static final BaseAgent ROOT_AGENT =
      LlmAgent.builder()
          .name("code_assistant")
          .model("gemini-3.8-flash")
          .description(
              "A multi-agent code assistant that writes, reviews, and debugs code by delegating"
                  + " to specialized sub-agents.")
          .instruction(
              "You are a code assistant coordinator. Delegate every request to the right"
                  + " sub-agent instead of answering directly:\n"
                  + "- code_writer: writing, generating, or explaining code\n"
                  + "- code_reviewer: reviewing code, finding issues, suggesting improvements\n"
                  + "- debugger: diagnosing compile errors, exceptions, and bugs\n\n"
                  + "Route the user's request to exactly one sub-agent, then relay its answer. "
                  + "If a request spans multiple concerns (for example, write code and then "
                  + "review it), delegate to each sub-agent in turn and combine their results.")
          .subAgents(CODE_WRITER, CODE_REVIEWER, DEBUGGER)
          .build();

  /**
   * Compiles a Java source snippet with the JDK compiler and returns the real diagnostics.
   *
   * @param code a complete Java source file (a public type must match the file name derived from
   *     it)
   * @param language the language of the snippet; only Java is supported
   * @return compilation status plus per-diagnostic line, column, and message
   */
  public static Map<String, Object> compileJava(
      @Schema(name = "code", description = "The Java source code to compile") String code,
      @Schema(name = "language", description = "The language of the code; only Java is supported")
          String language) {
    if (!"java".equalsIgnoreCase(language == null ? "" : language.trim())) {
      Map<String, Object> unsupported = new LinkedHashMap<>();
      unsupported.put("status", "unsupported");
      unsupported.put("language", language);
      unsupported.put("message", "compileJava only supports Java source code.");
      return unsupported;
    }
    if (code == null || code.isBlank()) {
      Map<String, Object> empty = new LinkedHashMap<>();
      empty.put("status", "error");
      empty.put("message", "No code provided to compile.");
      return empty;
    }

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      Map<String, Object> noCompiler = new LinkedHashMap<>();
      noCompiler.put("status", "error");
      noCompiler.put(
          "message", "No system Java compiler available; compileJava requires a JDK, not a JRE.");
      return noCompiler;
    }

    try {
      Path workDir = Files.createTempDirectory("adk-compile-java");
      try {
        Path sourceDir = Files.createDirectories(workDir.resolve("src"));
        Path classDir = Files.createDirectories(workDir.resolve("classes"));
        String typeName = publicTypeName(code).orElse("Snippet");
        Path sourceFile = sourceDir.resolve(typeName + ".java");
        Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
          Iterable<? extends JavaFileObject> units =
              fileManager.getJavaFileObjects(sourceFile.toFile());
          List<String> options = List.of("-d", classDir.toString());
          JavaCompiler.CompilationTask task =
              compiler.getTask(null, fileManager, diagnostics, options, null, units);
          boolean success = task.call();

          Map<String, Object> result = new LinkedHashMap<>();
          result.put("status", success ? "success" : "compilation_failed");
          result.put("fileName", typeName + ".java");
          List<Map<String, Object>> entries = new ArrayList<>();
          for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", d.getKind().toString());
            entry.put("line", d.getLineNumber());
            entry.put("column", d.getColumnNumber());
            entry.put("message", d.getMessage(null));
            entries.add(entry);
          }
          result.put("diagnosticCount", entries.size());
          result.put("diagnostics", entries);
          return result;
        }
      } finally {
        deleteRecursively(workDir);
      }
    } catch (IOException e) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("status", "error");
      result.put("message", "Failed to set up compilation: " + e.getMessage());
      return result;
    }
  }

  /** Returns the name of the first public top-level type, used to name the source file. */
  private static java.util.Optional<String> publicTypeName(String code) {
    Matcher matcher = PUBLIC_TYPE_PATTERN.matcher(code);
    return matcher.find() ? java.util.Optional.of(matcher.group(1)) : java.util.Optional.empty();
  }

  private static void deleteRecursively(Path dir) {
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // Best-effort cleanup of a temp directory.
                }
              });
    } catch (IOException ignored) {
      // Best-effort cleanup of a temp directory.
    }
  }

  public static void main(String[] args) {
    AdkWebServer.start(ROOT_AGENT);
  }
}
