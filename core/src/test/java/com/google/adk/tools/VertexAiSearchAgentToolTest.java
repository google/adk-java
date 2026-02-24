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
