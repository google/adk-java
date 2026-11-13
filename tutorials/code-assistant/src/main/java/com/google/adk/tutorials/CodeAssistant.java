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
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.web.AdkWebServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public class CodeAssistant {

  private static final String MODEL = "gemini-3.8-flash";

  private static final FunctionTool COMPILE_JAVA =
      FunctionTool.create(CodeAssistant.class, "compileJava");

  private static final LlmAgent CODE_WRITER =
      LlmAgent.builder()
          .name("code_writer")
          .model(MODEL)
          .description("Writes, explains, and refactors code.")
          .instruction(
              "You are a code writer. Produce correct, idiomatic code for the user's request, "
                  + "with brief explanations of the key decisions. Before returning Java code you "
                  + "wrote or changed, put it in a complete source file, call compileJava, and fix "
                  + "any errors it reports. Keep answers focused on the code itself.")
          .tools(COMPILE_JAVA)
          .build();

  private static final LlmAgent CODE_REVIEWER =
      LlmAgent.builder()
          .name("code_reviewer")
          .model(MODEL)
          .description("Reviews code for correctness, style, and best practices.")
          .instruction(
              "You are a code reviewer. When reviewing Java code, call compileJava first and "
                  + "base your findings on the real compiler diagnostics (line, column, "
                  + "message) together with your own analysis of style and best practices. "
                  + "Report concrete issues with line references, ordered by severity, and "
                  + "suggest fixes. If the code is not Java, review it directly without the "
                  + "compile tool.")
          .tools(COMPILE_JAVA)
          .build();

  private static final LlmAgent DEBUGGER =
      LlmAgent.builder()
          .name("debugger")
          .model(MODEL)
          .description("Diagnoses compile errors, runtime exceptions, and logic bugs.")
          .instruction(
              "You are a debugger. When the user reports a Java compile or runtime problem, call "
                  + "compileJava on the complete source file to reproduce the compiler "
                  + "diagnostics, then explain the root cause and the minimal fix. Note that "
                  + "compileJava performs a full compile, so a snippet missing imports or "
                  + "referenced classes will fail for reasons unrelated to the bug — include "
                  + "everything needed to compile. Once you propose a fix, compile it with "
                  + "compileJava to confirm it resolves the diagnostics. For runtime errors, "
                  + "reason from the stack trace and the code. Be concrete: name the line, the "
                  + "cause, and the fix.")
          .tools(COMPILE_JAVA)
          .build();

  public static final BaseAgent ROOT_AGENT =
      LlmAgent.builder()
          .name("code_assistant")
          .model(MODEL)
          .description(
              "A multi-agent code assistant that writes, reviews, and debugs code by calling"
                  + " specialist agents as tools.")
          .instruction(
              "You are a code assistant coordinator. Answer questions about programming concepts"
                  + " yourself, and use these tools for everything else:\n"
                  + "- code_writer: writing, generating, or explaining code\n"
                  + "- code_reviewer: reviewing code, finding issues, suggesting improvements\n"
                  + "- debugger: diagnosing compile errors, exceptions, and bugs\n\n"
                  + "The tools do not see the conversation, so include the complete code and any"
                  + " error messages in each request. If a request spans multiple concerns (for"
                  + " example, write code and then review it), call each tool in turn and combine"
                  + " their results.")
          .tools(
              AgentTool.create(CODE_WRITER),
              AgentTool.create(CODE_REVIEWER),
              AgentTool.create(DEBUGGER))
          .build();

  /**
   * Compiles one complete Java source file with the JDK compiler and returns its diagnostics with
   * line and column. Wrap a bare method or statement in a class first.
   *
   * @return compilation status plus per-diagnostic line, column, and message
   */
  @Schema(
      description =
          "Compiles one complete Java source file with the JDK compiler and returns its"
              + " diagnostics with line and column. Wrap a bare method or statement in a class"
              + " first.")
  public static Map<String, Object> compileJava(
      @Schema(name = "code", description = "A complete Java source file to compile") String code,
      @Schema(
              name = "className",
              description =
                  "The name of the public top-level type in the code, used as the file name. Any"
                      + " class name works if no type is public.")
          String className) {
    if (code == null || code.isBlank()) {
      Map<String, Object> empty = new LinkedHashMap<>();
      empty.put("status", "error");
      empty.put("message", "No code provided to compile.");
      return empty;
    }
    if (className == null
        || !SourceVersion.isIdentifier(className)
        || SourceVersion.isKeyword(className)
        || className.chars().anyMatch(Character::isIdentifierIgnorable)) {
      Map<String, Object> invalidName = new LinkedHashMap<>();
      invalidName.put("status", "error");
      invalidName.put("message", "className must be a valid Java identifier.");
      return invalidName;
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
        Path sourceFile = sourceDir.resolve(className + ".java");
        Files.writeString(sourceFile, code, StandardCharsets.UTF_8);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
          Iterable<? extends JavaFileObject> units =
              fileManager.getJavaFileObjects(sourceFile.toFile());
          List<String> options =
              List.of("-d", classDir.toString(), "-Xlint:all", "--release", "17");
          JavaCompiler.CompilationTask task =
              compiler.getTask(null, fileManager, diagnostics, options, null, units);
          boolean success = task.call();

          Map<String, Object> result = new LinkedHashMap<>();
          result.put("status", success ? "success" : "compilation_failed");
          result.put("fileName", className + ".java");
          List<Map<String, Object>> entries = new ArrayList<>();
          for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", d.getKind().toString());
            if (d.getLineNumber() != Diagnostic.NOPOS) {
              entry.put("line", d.getLineNumber());
              entry.put("column", d.getColumnNumber());
            }
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

  private static void deleteRecursively(Path dir) {
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(Comparator.reverseOrder())
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
