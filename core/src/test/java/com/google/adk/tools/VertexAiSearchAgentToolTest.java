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

package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class VertexAiSearchAgentToolTest {

  @Test
  public void create_createsAgent() {
    VertexAiSearchTool vertexAiSearchTool =
        VertexAiSearchTool.builder().searchEngineId("test-engine").build();
    VertexAiSearchAgentTool tool =
        VertexAiSearchAgentTool.create(new TestLlm(ImmutableList.of()), vertexAiSearchTool);
    assertThat(tool.getAgent().name()).isEqualTo("vertex_ai_search_agent");
    assertThat(((LlmAgent) tool.getAgent()).tools().blockingGet())
        .containsExactly(vertexAiSearchTool);
  }
}
