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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CodeAssistantTest {

  @Test
  public void generateCode_returnsSuccessResponse() {
    var result = CodeAssistant.generateCode("Java", "reverse a string");
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("language", "Java");
    assertThat(result).containsEntry("task", "reverse a string");
    assertThat(result).containsKey("message");
  }

  @Test
  public void generateCode_withDifferentLanguage_stillProcesses() {
    var result = CodeAssistant.generateCode("Python", "sort array");
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("language", "Python");
  }

  @Test
  public void reviewCode_returnsSuccessResponse() {
    var result = CodeAssistant.reviewCode("public void test() {}", "Java");
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("language", "Java");
    assertThat(result).containsKey("message");
  }

  @Test
  public void reviewCode_withNullLanguage_usesDetected() {
    var result = CodeAssistant.reviewCode("function test() {}", null);
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("language", "detected");
  }

  @Test
  public void explainCode_returnsSuccessResponse() {
    var result = CodeAssistant.explainCode("int x = 5;", "detailed");
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("detail_level", "detailed");
    assertThat(result).containsKey("message");
  }

  @Test
  public void explainCode_withNullDetailLevel_usesDefault() {
    var result = CodeAssistant.explainCode("String s = \"hello\";", null);
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("detail_level", "detailed");
  }

  @Test
  public void debugCode_returnsSuccessResponse() {
    var result = CodeAssistant.debugCode("int x = 5 / 0;", "Division by zero");
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("error_message", "Division by zero");
    assertThat(result).containsKey("message");
  }

  @Test
  public void debugCode_withDifferentErrorMessage_stillProcesses() {
    var result = CodeAssistant.debugCode("for(int i=0; i<10; i++) {}", "Syntax error");
    assertThat(result).containsEntry("status", "success");
    assertThat(result).containsEntry("error_message", "Syntax error");
  }

  @Test
  public void rootAgent_isNotNull() {
    assertThat(CodeAssistant.ROOT_AGENT).isNotNull();
  }

  @Test
  public void rootAgent_hasCorrectName() {
    assertThat(CodeAssistant.ROOT_AGENT.name()).isEqualTo("code_assistant");
  }
}
