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

package com.google.adk.flows.llmflows;

import static com.google.adk.testing.TestUtils.createLlmResponse;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.codeexecutors.BaseCodeExecutor;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionInput;
import com.google.adk.codeexecutors.CodeExecutionUtils.CodeExecutionResult;
import com.google.adk.events.Event;
import com.google.adk.flows.llmflows.RequestProcessor.RequestProcessingResult;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class CodeExecutionTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private BaseCodeExecutor mockCodeExecutor;
  @Mock private InvocationContext invocationContext;
  @Mock private BaseArtifactService mockArtifactService;
  private TestLlm testLlm;
  private LlmAgent agent;

  @Before
  public void setUp() {
    testLlm = createTestLlm(new LlmResponse[0]);
    agent = createTestAgentBuilder(testLlm).codeExecutor(mockCodeExecutor).build();
    InMemorySessionService sessionService = new InMemorySessionService();
    Session session = sessionService.createSession("app", "user").blockingGet();
    when(invocationContext.session()).thenReturn(session);
    when(invocationContext.agent()).thenReturn(agent);
    when(invocationContext.invocationId()).thenReturn("invocation-id");
    when(invocationContext.appName()).thenReturn("app");
    when(invocationContext.userId()).thenReturn("user");
    when(invocationContext.artifactService()).thenReturn(mockArtifactService);
    when(mockArtifactService.saveArtifact(
            anyString(), anyString(), anyString(), anyString(), any(Part.class)))
        .thenReturn(Single.just(1));
  }

  @Test
  public void testResponseProcessor_withCode_executesCode() {
    String code = "print('hello')";
    Content llmResponseContent =
        Content.builder()
            .role("model")
            .parts(
                ImmutableList.of(
                    Part.fromText("some text"),
                    Part.fromText("```tool_code\n" + code + "\n```"),
                    Part.fromText("more text")))
            .build();
    LlmResponse llmResponse = createLlmResponse(llmResponseContent);
    CodeExecutionResult executionResult = CodeExecutionResult.builder().stdout("hello\n").build();
    when(mockCodeExecutor.errorRetryAttempts()).thenReturn(2);
    when(mockCodeExecutor.executeCode(any(), any())).thenReturn(executionResult);
    when(mockCodeExecutor.codeBlockDelimiters())
        .thenReturn(ImmutableList.of(ImmutableList.of("```tool_code\n", "\n```")));

    ResponseProcessor.ResponseProcessingResult result =
        CodeExecution.responseProcessor
            .processResponse(invocationContext, llmResponse)
            .blockingGet();

    ArgumentCaptor<CodeExecutionInput> captor = ArgumentCaptor.forClass(CodeExecutionInput.class);
    verify(mockCodeExecutor).executeCode(any(InvocationContext.class), captor.capture());
    assertThat(captor.getValue().code()).isEqualTo(code);

    ImmutableList<Event> events = ImmutableList.copyOf(result.events());
    assertThat(events).hasSize(2);
    Part executableCodePart = events.get(0).content().get().parts().get().get(1);
    assertThat(executableCodePart.executableCode().get().code()).hasValue(code);

    Part executionResultPart = events.get(1).content().get().parts().get().get(0);
    assertThat(executionResultPart.codeExecutionResult().get().output())
        .hasValue("Code execution result:\nhello\n\n");
  }

  @Test
  public void testRequestProcessor_withCode_hasNoErrors() throws Exception {
    // arrange
    LlmRequest.Builder llmReqBuilder = LlmRequest.builder();
    when(mockCodeExecutor.codeBlockDelimiters())
        .thenReturn(ImmutableList.of(ImmutableList.of("```tool_code", "\n```")));
    when(mockCodeExecutor.optimizeDataFile()).thenReturn(true);
    when(mockCodeExecutor.errorRetryAttempts()).thenReturn(2);
    CodeExecutionResult executionResult = CodeExecutionResult.builder().stdout("hello\n").build();
    when(mockCodeExecutor.executeCode(any(), any())).thenReturn(executionResult);
    llmReqBuilder.contents(
        new ArrayList<>(
            ImmutableList.of(
                Content.builder()
                    .role("user")
                    .parts(
                        ImmutableList.of(
                            Part.builder()
                                .inlineData(
                                    Blob.builder()
                                        .mimeType("text/csv")
                                        .data("1,2,3\n".getBytes(UTF_8)))
                                .build()))
                    .build())));

    // act
    Single<RequestProcessingResult> result =
        CodeExecution.requestProcessor.processRequest(invocationContext, llmReqBuilder.build());
    TestObserver<RequestProcessingResult> testObserver = result.test();

    // assert
    testObserver.assertNoErrors();
  }
}
