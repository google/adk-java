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

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.LoadMemoryTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AgentWithMemoryTest {
  @Test
  public void agentRemembersUserNameWithMemoryTool() throws Exception {
    String userId = "test-user";
    String agentName = "test-agent";

    Part functionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("loadMemory")
                    .args(ImmutableMap.of("query", "what is my name?"))
                    .build())
            .build();

    TestLlm testLlm =
        new TestLlm(
            ImmutableList.of(
                LlmResponse.builder()
                    .content(
                        Content.builder()
                            .parts(Part.fromText("OK, I'll remember that."))
                            .role("model")
                            .build())
                    .build(),
                LlmResponse.builder()
                    .content(
                        Content.builder()
                            .role("model")
                            .parts(ImmutableList.of(functionCall))
                            .build())
                    .build(),
                LlmResponse.builder()
                    .content(
                        Content.builder()
                            // we won't actually read the name from here since that'd be
                            // cheating.
                            .parts(Part.fromText("Your name is James."))
                            .role("model")
                            .build())
                    .build()));

    LlmAgent agent =
        LlmAgent.builder()
            .name(agentName)
            .model(testLlm)
            .tools(ImmutableList.of(new LoadMemoryTool()))
            .build();

    InMemoryRunner runner = new InMemoryRunner(agent);
    String sessionId = runner.sessionService().createSession(agentName, userId).blockingGet().id();

    Content firstMessage = Content.fromParts(Part.fromText("My name is James"));

    var unused =
        runner
            .runAsync(userId, sessionId, firstMessage, RunConfig.builder().build())
            .toList()
            .blockingGet();

    // Retrieve the updated session after the first runAsync
    Session updatedSession =
        runner
            .sessionService()
            .getSession("test-agent", userId, sessionId, Optional.empty())
            .blockingGet();

    // Save the updated session to memory so we can bring it up on the next request.
    runner.memoryService().addSessionToMemory(updatedSession).blockingAwait();

    Content secondMessage = Content.fromParts(Part.fromText("what is my name?"));
    unused =
        runner
            .runAsync(userId, updatedSession.id(), secondMessage, RunConfig.builder().build())
            .toList()
            .blockingGet();

    // Verify that the tool's response was included in the next LLM call.
    LlmRequest lastRequest = testLlm.getLastRequest();
    Content functionResponseContent = Iterables.getLast(lastRequest.contents());
    Optional<Part> functionResponsePart =
        functionResponseContent.parts().get().stream()
            .filter(p -> p.functionResponse().isPresent())
            .findFirst();
    assertThat(functionResponsePart).isPresent();
    FunctionResponse functionResponse = functionResponsePart.get().functionResponse().get();
    assertThat(functionResponse.name()).hasValue("loadMemory");
    assertThat(functionResponse.response().get().toString()).contains("My name is James");
  }
}
