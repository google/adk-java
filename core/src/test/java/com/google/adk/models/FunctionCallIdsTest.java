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
package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FunctionCallIdsTest {

  @Test
  public void generatedId_isRecognizedAsClientGenerated() {
    String id = FunctionCallIds.generateClientFunctionCallId();

    assertThat(FunctionCallIds.isClientGeneratedFunctionCallId(id)).isTrue();
  }

  @Test
  public void generateClientFunctionCallId_returnsUniqueIds() {
    String first = FunctionCallIds.generateClientFunctionCallId();
    String second = FunctionCallIds.generateClientFunctionCallId();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  public void isClientGeneratedFunctionCallId_falseForModelGeneratedId() {
    assertThat(FunctionCallIds.isClientGeneratedFunctionCallId("call_123")).isFalse();
  }

  @Test
  public void isClientGeneratedFunctionCallId_falseForNullOrEmpty() {
    assertThat(FunctionCallIds.isClientGeneratedFunctionCallId(null)).isFalse();
    assertThat(FunctionCallIds.isClientGeneratedFunctionCallId("")).isFalse();
  }
}
