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
  public void rootAgent_isCoordinatorWithSubAgents() {
    assertThat(CodeAssistant.ROOT_AGENT.name()).isEqualTo("code_assistant");
    assertThat(
            CodeAssistant.ROOT_AGENT.subAgents().stream()
                .map(agent -> agent.name())
                .collect(java.util.stream.Collectors.toList()))
        .containsExactly("code_writer", "code_reviewer", "debugger");
  }

  // ---- compileJava ----

  @Test
  public void compileJava_validSnippetCompiles() {
    Map<String, Object> result = CodeAssistant.compileJava(HELLO_WORLD, "Java");
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("fileName", "HelloWorld.java");
    assertThat(result).containsEntry("diagnosticCount", 0);
  }

  @Test
  public void compileJava_snippetWithoutPublicTypeUsesSnippetFileName() {
    String code = "class Greeter {\n  String greet() { return \"hi\"; }\n}\n";
    Map<String, Object> result = CodeAssistant.compileJava(code, "Java");
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("fileName", "Snippet.java");
  }

  @Test
  public void compileJava_brokenSnippetReturnsRealDiagnostics() {
    Map<String, Object> result = CodeAssistant.compileJava(BROKEN_SNIPPET, "Java");
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
  public void compileJava_missingSemicolonAndBraceProduceMultipleDiagnostics() {
    String code =
        """
        public class Bad {
          public static void main(String[] args) {
            int x = 1
            System.out.println(x);
        """;
    Map<String, Object> result = CodeAssistant.compileJava(code, "Java");
    assertThat(result).containsEntry("status", "compilation_failed");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) result.get("diagnostics");
    assertThat(diagnostics.size()).isGreaterThan(1);
  }

  @Test
  public void compileJava_unsupportedLanguageReturnsUnsupported() {
    Map<String, Object> result = CodeAssistant.compileJava("print('hi')", "Python");
    assertThat(result).containsEntry("status", "unsupported");
    assertThat(result).containsEntry("language", "Python");
  }

  @Test
  public void compileJava_nullLanguageReturnsUnsupported() {
    Map<String, Object> result = CodeAssistant.compileJava(HELLO_WORLD, null);
    assertThat(result).containsEntry("status", "unsupported");
  }

  @Test
  public void compileJava_nullCodeReturnsError() {
    Map<String, Object> result = CodeAssistant.compileJava(null, "Java");
    assertThat(result).containsEntry("status", "error");
  }

  @Test
  public void compileJava_blankCodeReturnsError() {
    Map<String, Object> result = CodeAssistant.compileJava("   \n  ", "Java");
    assertThat(result).containsEntry("status", "error");
  }

  @Test
  public void compileJava_lowercaseJavaLanguageIsAccepted() {
    Map<String, Object> result = CodeAssistant.compileJava(HELLO_WORLD, "java");
    assertThat(result).containsEntry("status", "success");
  }
}
