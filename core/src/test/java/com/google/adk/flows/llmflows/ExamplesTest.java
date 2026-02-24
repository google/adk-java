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

package com.google.adk.flows.llmflows;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.examples.BaseExampleProvider;
import com.google.adk.examples.Example;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ExamplesTest {

  private static final InMemorySessionService sessionService = new InMemorySessionService();

  private static class TestExampleProvider implements BaseExampleProvider {
    @Override
    public List<Example> getExamples(String query) {
      return ImmutableList.of(
          Example.builder()
              .input(Content.fromParts(Part.fromText("input1")))
              .output(
                  ImmutableList.of(
                      Content.builder().parts(Part.fromText("output1")).role("model").build()))
              .build());
    }
  }

  @Test
  public void processRequest_withExampleProvider_addsExamplesToInstructions() {
    LlmAgent agent =
        LlmAgent.builder().name("test-agent").exampleProvider(new TestExampleProvider()).build();
    InvocationContext context =
        InvocationContext.builder()
            .invocationId("invocation1")
            .session(Session.builder("session1").build())
            .sessionService(sessionService)
            .agent(agent)
            .userContent(Content.fromParts(Part.fromText("what is up?")))
            .runConfig(RunConfig.builder().build())
            .build();
    LlmRequest request = LlmRequest.builder().build();
    Examples examplesProcessor = new Examples();

    RequestProcessor.RequestProcessingResult result =
        examplesProcessor.processRequest(context, request).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions()).isNotEmpty();
    assertThat(result.updatedRequest().getSystemInstructions().get(0))
        .contains("[user]\ninput1\n\n[model]\noutput1\n");
  }

  @Test
  public void processRequest_withoutExampleProvider_doesNotAddExamplesToInstructions() {
    LlmAgent agent = LlmAgent.builder().name("test-agent").build();
    InvocationContext context =
        InvocationContext.builder()
            .invocationId("invocation1")
            .session(Session.builder("session1").build())
            .sessionService(sessionService)
            .agent(agent)
            .userContent(Content.fromParts(Part.fromText("what is up?")))
            .runConfig(RunConfig.builder().build())
            .build();
    LlmRequest request = LlmRequest.builder().build();
    Examples examplesProcessor = new Examples();

    RequestProcessor.RequestProcessingResult result =
        examplesProcessor.processRequest(context, request).blockingGet();

    assertThat(result.updatedRequest().getSystemInstructions()).isEmpty();
  }
}
