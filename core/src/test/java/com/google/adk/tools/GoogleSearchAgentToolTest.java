package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.LlmAgent;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GoogleSearchAgentToolTest {

  @Test
  public void create_createsAgent() {
    GoogleSearchAgentTool tool = GoogleSearchAgentTool.create(new TestLlm(ImmutableList.of()));
    assertThat(tool.getAgent().name()).isEqualTo("google_search_agent");
    assertThat(((LlmAgent) tool.getAgent()).tools().blockingGet())
        .containsExactly(GoogleSearchTool.INSTANCE);
  }
}
