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
package com.google.adk.plugins;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class GlobalInstructionPluginTest {
  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();
  @Mock private CallbackContext mockCallbackContext;
  @Mock private InvocationContext mockInvocationContext;
  private final State state = new State(new ConcurrentHashMap<>());
  private final Session session = Session.builder("session_id").state(state).build();
  @Mock private BaseArtifactService mockArtifactService;

  @Before
  public void setUp() {
    state.clear();
    when(mockCallbackContext.invocationId()).thenReturn("invocation_id");
    when(mockCallbackContext.agentName()).thenReturn("agent_name");
    when(mockCallbackContext.invocationContext()).thenReturn(mockInvocationContext);
    when(mockInvocationContext.session()).thenReturn(session);
    when(mockInvocationContext.artifactService()).thenReturn(mockArtifactService);
  }

  @Test
  public void beforeModelCallback_noExistingInstruction() {
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();
    GlobalInstructionPlugin plugin = new GlobalInstructionPlugin("global instruction");
    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();
    Content systemInstruction = llmRequestBuilder.build().config().get().systemInstruction().get();
    List<Part> parts = systemInstruction.parts().get();
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("global instruction");
    assertThat(systemInstruction.role()).isEmpty();
  }

  @Test
  public void beforeModelCallback_withExistingInstruction() {
    LlmRequest.Builder llmRequestBuilder =
        LlmRequest.builder()
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder().parts(Part.fromText("existing instruction")).build())
                    .build());
    GlobalInstructionPlugin plugin = new GlobalInstructionPlugin("global instruction");
    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();
    Content systemInstruction = llmRequestBuilder.build().config().get().systemInstruction().get();
    List<Part> parts = systemInstruction.parts().get();
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).text()).hasValue("global instruction\n\n");
    assertThat(parts.get(1).text()).hasValue("existing instruction");
    assertThat(systemInstruction.role()).isEmpty();
  }

  @Test
  public void beforeModelCallback_withInstructionProvider() {
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();
    GlobalInstructionPlugin plugin =
        new GlobalInstructionPlugin(unusedContext -> Maybe.just("instruction from provider"));
    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();
    Content systemInstruction = llmRequestBuilder.build().config().get().systemInstruction().get();
    List<Part> parts = systemInstruction.parts().get();
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("instruction from provider");
    assertThat(systemInstruction.role()).isEmpty();
  }

  @Test
  public void beforeModelCallback_withStringInstruction_injectsState() {
    state.put("name", "Alice");
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();
    GlobalInstructionPlugin plugin = new GlobalInstructionPlugin("Hello {name}");
    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();
    Content systemInstruction = llmRequestBuilder.build().config().get().systemInstruction().get();
    List<Part> parts = systemInstruction.parts().get();
    assertThat(parts).hasSize(1);
    assertThat(parts.get(0).text()).hasValue("Hello Alice");
    assertThat(systemInstruction.role()).isEmpty();
  }

  @Test
  public void beforeModelCallback_nullInstruction() {
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();
    GlobalInstructionPlugin plugin = new GlobalInstructionPlugin((String) null);
    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();
    assertThat(llmRequestBuilder.build().config()).isEmpty();
  }

  @Test
  public void beforeModelCallback_emptyInstruction() {
    LlmRequest.Builder llmRequestBuilder = LlmRequest.builder();
    GlobalInstructionPlugin plugin = new GlobalInstructionPlugin("");
    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();
    assertThat(llmRequestBuilder.build().config()).isEmpty();
  }

  @Test
  public void beforeModelCallback_withExistingInstructionAndRole_preservesRole() {
    LlmRequest.Builder llmRequestBuilder =
        LlmRequest.builder()
            .config(
                GenerateContentConfig.builder()
                    .systemInstruction(
                        Content.builder()
                            .parts(Part.fromText("existing instruction"))
                            .role("system")
                            .build())
                    .build());
    GlobalInstructionPlugin plugin = new GlobalInstructionPlugin("global instruction");
    plugin.beforeModelCallback(mockCallbackContext, llmRequestBuilder).test().assertComplete();
    Content systemInstruction = llmRequestBuilder.build().config().get().systemInstruction().get();
    List<Part> parts = systemInstruction.parts().get();
    assertThat(parts).hasSize(2);
    assertThat(parts.get(0).text()).hasValue("global instruction\n\n");
    assertThat(parts.get(1).text()).hasValue("existing instruction");
    assertThat(systemInstruction.role()).hasValue("system");
  }
}
