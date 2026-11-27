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

import static com.google.adk.testing.TestUtils.createFunctionCallLlmResponse;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.adk.testing.TestUtils.createTextLlmResponse;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.LlmRequest;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Environment;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.FunctionResponseBlob;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
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

  private static final byte[] SCREENSHOT = {(byte) 0x89, 'P', 'N', 'G'};

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

    // Verify method filtering
    assertThat(tools.stream().map(BaseTool::name))
        .containsExactly(
            "open_web_browser",
            "click_at",
            "hover_at",
            "type_text_at",
            "scroll_document",
            "scroll_at",
            "wait_5_seconds",
            "go_back",
            "go_forward",
            "search",
            "navigate",
            "key_combination",
            "drag_and_drop");
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

    // The model gets its predefined actions from computerUse, not from function declarations.
    assertThat(tools.stream().noneMatch(t -> t.functionDeclarations().isPresent())).isTrue();
    assertThat(request.tools()).containsKey("click_at");
  }

  @Test
  public void runAsync_firstTurn_sendsComputerUseWithoutDuplicateTools() {
    TestLlm testLlm = createTestLlm(createTextLlmResponse("done"));
    LlmAgent agent = LlmAgent.builder().name("agent").model(testLlm).tools(toolset).build();

    List<Event> events = runAgent(agent);

    assertThat(events).isNotEmpty();
    LlmRequest request = testLlm.getLastRequest();
    List<Tool> tools = request.config().get().tools().get();
    assertThat(tools).hasSize(1);
    assertThat(tools.get(0).computerUse()).isPresent();
    assertThat(request.tools().keySet()).containsAtLeast("click_at", "navigate", "search");
  }

  @Test
  public void runAsync_modelCallsAction_runsComputerAndReturnsScreenshotAsImage() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse(
                "call-1", "click_at", ImmutableMap.of("x", 500, "y", 500)),
            createTextLlmResponse("done"));
    LlmAgent agent = LlmAgent.builder().name("agent").model(testLlm).tools(toolset).build();

    List<Event> unused = runAgent(agent);

    assertThat(mockComputer.lastClick).isEqualTo(new int[] {960, 540});
    FunctionResponse response = lastFunctionResponse(testLlm);
    assertThat(response.name()).hasValue("click_at");
    assertThat(response.response().get()).doesNotContainKey("image");
    assertThat(response.response().get()).containsEntry("url", "https://example.com");
    FunctionResponseBlob image = response.parts().get().get(0).inlineData().get();
    assertThat(image.mimeType()).hasValue("image/png");
    assertThat(image.data().get()).isEqualTo(SCREENSHOT);
  }

  @Test
  public void runAsync_modelCallsWait5Seconds_waitsFiveSeconds() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse("call-1", "wait_5_seconds", ImmutableMap.of()),
            createTextLlmResponse("done"));
    LlmAgent agent = LlmAgent.builder().name("agent").model(testLlm).tools(toolset).build();

    List<Event> unused = runAgent(agent);

    assertThat(mockComputer.lastWait).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  public void runAsync_imageWithInvalidBase64_keepsImageInResponse() {
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse("call-1", "click_at", ImmutableMap.of("x", 1, "y", 1)),
            createTextLlmResponse("done"));
    LlmAgent agent =
        LlmAgent.builder()
            .name("agent")
            .model(testLlm)
            .tools(toolset)
            .afterToolCallbackSync((invocationContext, tool, input, toolContext, response) ->
                Optional.of(
                    ImmutableMap.of(
                        "image", ImmutableMap.of("mimetype", "image/png", "data", "%%%"))))
            .build();

    List<Event> unused = runAgent(agent);

    FunctionResponse response = lastFunctionResponse(testLlm);
    assertThat(response.response().get()).containsKey("image");
    assertThat(response.parts()).isEmpty();
  }

  @Test
  public void runAsync_otherToolReturnsImage_keepsImageInResponse() {
    ImmutableMap<String, Object> image =
        ImmutableMap.of("mimetype", "image/png", "data", "iVBORw0K");
    FunctionTool imageTool = FunctionTool.create(ImageTool.class, "screenshot");
    ImageTool.result = ImmutableMap.of("image", image);
    TestLlm testLlm =
        createTestLlm(
            createFunctionCallLlmResponse("call-1", "screenshot", ImmutableMap.of()),
            createTextLlmResponse("done"));
    LlmAgent agent = LlmAgent.builder().name("agent").model(testLlm).tools(imageTool).build();

    List<Event> unused = runAgent(agent);

    FunctionResponse response = lastFunctionResponse(testLlm);
    assertThat(response.response().get()).containsEntry("image", image);
    assertThat(response.parts()).isEmpty();
  }

  @Test
  public void runAsync_standaloneComputerUseTool_isDeclaredAsFunction() throws Exception {
    ComputerUseTool tool =
        new ComputerUseTool(
            mockComputer,
            BaseComputer.class.getMethod("navigate", String.class),
            new int[] {1920, 1080},
            new int[] {1000, 1000});
    TestLlm testLlm = createTestLlm(createTextLlmResponse("done"));
    LlmAgent agent = LlmAgent.builder().name("agent").model(testLlm).tools(tool).build();

    List<Event> unused = runAgent(agent);

    LlmRequest request = testLlm.getLastRequest();
    assertThat(request.tools()).containsKey("navigate");
    assertThat(request.config().get().tools().get().get(0).functionDeclarations().get()).hasSize(1);
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

  private static List<Event> runAgent(LlmAgent agent) {
    InMemoryRunner runner = new InMemoryRunner(agent);
    Session session = runner.sessionService().createSession(runner.appName(), "user").blockingGet();
    return runner
        .runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText("click")))
        .toList()
        .blockingGet();
  }

  private static FunctionResponse lastFunctionResponse(TestLlm testLlm) {
    return testLlm.getLastRequest().contents().stream()
        .flatMap(c -> c.parts().orElse(ImmutableList.of()).stream())
        .flatMap(p -> p.functionResponse().stream())
        .collect(onlyElement());
  }

  /** A function tool whose result carries an image map, like a computer use tool. */
  public static final class ImageTool {
    static ImmutableMap<String, Object> result;

    public static ImmutableMap<String, Object> screenshot() {
      return result;
    }
  }

  private static class MockComputer implements BaseComputer {
    int initializeCallCount = 0;
    int[] lastClick;
    Duration lastWait;
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
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> clickAt(int x, int y) {
      lastClick = new int[] {x, y};
      return Single.just(ComputerState.create(SCREENSHOT, "https://example.com"));
    }

    @Override
    public Single<ComputerState> hoverAt(int x, int y) {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> typeTextAt(
        int x, int y, String text, Boolean pressEnter, Boolean clearBeforeTyping) {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> scrollDocument(String direction) {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> scrollAt(int x, int y, String direction, int magnitude) {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> wait(Duration duration) {
      lastWait = duration;
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> goBack() {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> goForward() {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> search() {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> navigate(String url) {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).url(url).build());
    }

    @Override
    public Single<ComputerState> keyCombination(List<String> keys) {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> dragAndDrop(int x, int y, int destinationX, int destinationY) {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Single<ComputerState> currentState() {
      return Single.just(ComputerState.builder().screenshot(new byte[0]).build());
    }

    @Override
    public Completable close() {
      return Completable.complete();
    }
  }
}
