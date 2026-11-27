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

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.AgentTool;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CodeAssistantTest {

  private static final String HELLO_WORLD =
      """
      public class HelloWorld {
        public static void main(String[] args) {
          System.out.println("Hello, World!");
        }
      }
      """;

  private static final String BROKEN_SNIPPET =
      """
      public class Broken {
        public static void main(String[] args) {
          int x = ;
        }
      }
      """;

  // ---- agent structure ----

  @Test
  public void rootAgent_exposesSpecialistsAsAgentTools() {
    LlmAgent root = (LlmAgent) CodeAssistant.ROOT_AGENT;
    List<BaseTool> tools = root.tools().blockingGet();
    assertThat(tools.stream().map(BaseTool::name).toList())
        .containsExactly("code_writer", "code_reviewer", "debugger");
    for (BaseTool tool : tools) {
      assertThat(tool).isInstanceOf(AgentTool.class);
    }
  }

  @Test
  public void specialistsEachHaveCompileJavaTool() {
    LlmAgent root = (LlmAgent) CodeAssistant.ROOT_AGENT;
    for (BaseTool tool : root.tools().blockingGet()) {
      assertThat(tool).isInstanceOf(AgentTool.class);
      BaseAgent agent = ((AgentTool) tool).getAgent();
      assertThat(agent).isInstanceOf(LlmAgent.class);
      List<String> toolNames =
          ((LlmAgent) agent).tools().blockingGet().stream().map(BaseTool::name).toList();
      assertThat(toolNames).contains("compileJava");
    }
  }

  @Test
  public void compileJavaToolDeclarationHasDescription() {
    FunctionTool tool = FunctionTool.create(CodeAssistant.class, "compileJava");
    assertThat(tool.declaration()).isPresent();
    assertThat(tool.declaration().get().description().orElse("")).isNotEmpty();
  }

  // ---- compileJava ----

  @Test
  public void compileJava_validSnippetCompiles() {
    Map<String, Object> result = CodeAssistant.compileJava(HELLO_WORLD, "HelloWorld");
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("fileName", "HelloWorld.java");
    assertThat(result).containsEntry("diagnosticCount", 0);
  }

  @Test
  public void compileJava_brokenSnippetReturnsRealDiagnostics() {
    Map<String, Object> result = CodeAssistant.compileJava(BROKEN_SNIPPET, "Broken");
    assertThat(result).containsEntry("status", "compilation_failed");
    assertThat(result).containsEntry("fileName", "Broken.java");

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) result.get("diagnostics");
    assertThat(diagnostics).isNotEmpty();
    Map<String, Object> first = diagnostics.get(0);
    assertThat(first).containsKey("line");
    assertThat(first).containsKey("column");
    assertThat(first.get("message")).isNotNull();
    assertThat(first.get("line")).isEqualTo(3L);
  }

  @Test
  public void compileJava_typeErrorProducesDiagnostic() {
    String code =
        """
        public class TypeError {
          int answer() {
            return "not a number";
          }
        }
        """;
    Map<String, Object> result = CodeAssistant.compileJava(code, "TypeError");
    assertThat(result).containsEntry("status", "compilation_failed");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) result.get("diagnostics");
    assertThat(diagnostics).isNotEmpty();
    assertThat(diagnostics.get(0).get("line")).isEqualTo(3L);
  }

  @Test
  public void compileJava_warningProducesDiagnosticOnRealLine() {
    String code =
        """
        import java.util.ArrayList;
        import java.util.List;

        public class Warn {
          List raw = new ArrayList();
        }
        """;
    Map<String, Object> result = CodeAssistant.compileJava(code, "Warn");
    assertThat(result).containsEntry("status", "success");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) result.get("diagnostics");
    assertThat(diagnostics).isNotEmpty();
    assertThat(diagnostics.get(0)).containsEntry("kind", "WARNING");
    assertThat(diagnostics.get(0)).containsEntry("line", 5L);
  }

  @Test
  public void compileJava_missingSemicolonAndBraceProduceMultipleDiagnostics() {
    String code =
        """
        public class Bad {
          public static void main(String[] args) {
            int x = 1
            System.out.println(x);
        """;
    Map<String, Object> result = CodeAssistant.compileJava(code, "Bad");
    assertThat(result).containsEntry("status", "compilation_failed");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) result.get("diagnostics");
    assertThat(diagnostics.size()).isGreaterThan(1);
  }

  @Test
  public void compileJava_nullCodeReturnsError() {
    Map<String, Object> result = CodeAssistant.compileJava(null, "HelloWorld");
    assertThat(result).containsEntry("status", "error");
  }

  @Test
  public void compileJava_blankCodeReturnsError() {
    Map<String, Object> result = CodeAssistant.compileJava("   \n  ", "HelloWorld");
    assertThat(result).containsEntry("status", "error");
  }

  @Test
  public void compileJava_invalidClassNameReturnsError() {
    Map<String, Object> result = CodeAssistant.compileJava(HELLO_WORLD, "not a class!");
    assertThat(result).containsEntry("status", "error");
    assertThat(result.get("message")).isEqualTo("className must be a valid Java identifier.");
  }

  @Test
  public void compileJava_nullClassNameReturnsError() {
    Map<String, Object> result = CodeAssistant.compileJava(HELLO_WORLD, null);
    assertThat(result).containsEntry("status", "error");
  }

  @Test
  public void compileJava_keywordClassNameReturnsError() {
    Map<String, Object> result = CodeAssistant.compileJava(HELLO_WORLD, "class");
    assertThat(result).containsEntry("status", "error");
  }

  @Test
  public void compileJava_identifierIgnorableClassNameReturnsError() {
    Map<String, Object> result = CodeAssistant.compileJava(HELLO_WORLD, "Foo\u0000Bar");
    assertThat(result).containsEntry("status", "error");
  }
}
