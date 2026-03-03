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

package com.google.adk.tools.computeruse;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Environment;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ComputerUseToolset}. */
@RunWith(JUnit4.class)
public final class ComputerUseToolsetTest {

  private LlmAgent agent;
  private InMemorySessionService sessionService;
  private ToolContext toolContext;
  private MockComputer mockComputer;
  private ComputerUseToolset toolset;

  @Before
  public void setUp() {
    agent = LlmAgent.builder().name("test-agent").build();
    sessionService = new InMemorySessionService();
    Session session =
        sessionService.createSession("test-app", "test-user", null, "test-session").blockingGet();
    InvocationContext invocationContext =
        InvocationContext.builder()
            .agent(agent)
            .session(session)
            .sessionService(sessionService)
            .invocationId("invocation-id")
            .build();
    toolContext = ToolContext.builder(invocationContext).functionCallId("functionCallId").build();

    mockComputer = new MockComputer();
    toolset = new ComputerUseToolset(mockComputer);
  }

  @Test
  public void testGetTools() {
    List<BaseTool> tools = toolset.getTools(null).toList().blockingGet();

    assertThat(mockComputer.initializeCallCount).isEqualTo(1);
    assertThat(tools).isNotEmpty();

    // Verify method filtering
    assertThat(tools.stream().anyMatch(t -> t.name().equals("clickAt"))).isTrue();
    assertThat(tools.stream().noneMatch(t -> t.name().equals("screenSize"))).isTrue();
    assertThat(tools.stream().noneMatch(t -> t.name().equals("environment"))).isTrue();
  }

  @Test
  public void testEnsureInitializedOnlyCalledOnce() {
    var unused1 = toolset.getTools(null).toList().blockingGet();
    var unused2 = toolset.getTools(null).toList().blockingGet();

    assertThat(mockComputer.initializeCallCount).isEqualTo(1);
  }

  @Test
  public void testGetTools_cachesTools() {
    List<BaseTool> tools1 = toolset.getTools(null).toList().blockingGet();
    List<BaseTool> tools2 = toolset.getTools(null).toList().blockingGet();

    assertThat(tools1).hasSize(tools2.size());
    for (int i = 0; i < tools1.size(); i++) {
      assertThat(tools1.get(i)).isSameInstanceAs(tools2.get(i));
    }
  }

  @Test
  public void testProcessLlmRequest() {
    LlmRequest.Builder builder =
        LlmRequest.builder().model("test-model").config(GenerateContentConfig.builder().build());

    toolset.processLlmRequest(builder, toolContext).blockingAwait();

    LlmRequest request = builder.build();
    assertThat(request.config()).isPresent();
    GenerateContentConfig config = request.config().get();

    assertThat(config.tools()).isPresent();
    List<Tool> tools = config.tools().get();

    // Find the computer use tool
    Optional<Tool> computerUseTool =
        tools.stream().filter(t -> t.computerUse().isPresent()).findFirst();
    assertThat(computerUseTool).isPresent();
    assertThat(computerUseTool.get().computerUse().get().environment().get().knownEnum())
        .isEqualTo(Environment.Known.ENVIRONMENT_BROWSER);

    // Verify computer actions were added as function declarations
    Optional<Tool> functionTool =
        tools.stream().filter(t -> t.functionDeclarations().isPresent()).findFirst();
    assertThat(functionTool).isPresent();
    assertThat(
            functionTool.get().functionDeclarations().get().stream()
                .anyMatch(fd -> fd.name().orElse("").equals("clickAt")))
        .isTrue();
  }

  @Test
  public void testProcessLlmRequest_withComputerError() {
    mockComputer.nextError = new RuntimeException("Computer failure");
    LlmRequest.Builder builder =
        LlmRequest.builder().model("test-model").config(GenerateContentConfig.builder().build());

    assertThrows(
        RuntimeException.class,
        () -> toolset.processLlmRequest(builder, toolContext).blockingAwait());
  }

  private static class MockComputer implements BaseComputer {
    int initializeCallCount = 0;
    Throwable nextError = null;

    @Override
    public Completable initialize() {
      if (nextError != null) {
        return Completable.error(nextError);
      }
      this.initializeCallCount++;
      return Completable.complete();
    }

    @Override
    public Single<int[]> screenSize() {
      if (nextError != null) {
        return Single.error(nextError);
      }
      return Single.just(new int[] {1920, 1080});
    }

    @Override
    public Single<ComputerEnvironment> environment() {
      if (nextError != null) {
        return Single.error(nextError);
      }
      return Single.just(ComputerEnvironment.ENVIRONMENT_BROWSER);
    }

    @Override
    public Single<ComputerState> openWebBrowser() {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> clickAt(int x, int y) {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> hoverAt(int x, int y) {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> typeTextAt(
        int x, int y, String text, Boolean pressEnter, Boolean clearBeforeTyping) {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> scrollDocument(String direction) {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> scrollAt(int x, int y, String direction, int magnitude) {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> wait(Duration duration) {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> goBack() {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> goForward() {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> search() {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> navigate(String url) {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.of(url)).build());
    }

    @Override
    public Single<ComputerState> keyCombination(List<String> keys) {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> dragAndDrop(int x, int y, int destinationX, int destinationY) {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Single<ComputerState> currentState() {
      return Single.just(
          ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build());
    }

    @Override
    public Completable close() {
      return Completable.complete();
    }
  }
}
