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
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import io.reactivex.rxjava3.core.Single;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ComputerUseTool}. */
@RunWith(JUnit4.class)
public final class ComputerUseToolTest {

  private LlmAgent agent;
  private InMemorySessionService sessionService;
  private ToolContext toolContext;
  private ComputerMock computerMock;

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
    computerMock = new ComputerMock();
  }

  @Test
  public void testNormalizeX() throws NoSuchMethodException {
    Method method = ComputerMock.class.getMethod("clickAt", int.class, int.class);
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {1000, 1000});

    assertThat(tool.runAsync(ImmutableMap.of("x", 0, "y", 0), toolContext).blockingGet())
        .isNotNull();
    assertThat(computerMock.lastX).isEqualTo(0);

    assertThat(tool.runAsync(ImmutableMap.of("x", 500, "y", 300), toolContext).blockingGet())
        .isNotNull();
    assertThat(computerMock.lastX).isEqualTo(960); // 500/1000 * 1920

    assertThat(tool.runAsync(ImmutableMap.of("x", 1000, "y", 300), toolContext).blockingGet())
        .isNotNull();
    assertThat(computerMock.lastX).isEqualTo(1919); // Clamped
  }

  @Test
  public void testNormalizeY() throws NoSuchMethodException {
    Method method = ComputerMock.class.getMethod("clickAt", int.class, int.class);
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {1000, 1000});

    assertThat(tool.runAsync(ImmutableMap.of("x", 0, "y", 500), toolContext).blockingGet())
        .isNotNull();
    assertThat(computerMock.lastY).isEqualTo(540); // 500/1000 * 1080
  }

  @Test
  public void testNormalizeWithCustomVirtualScreenSize() throws NoSuchMethodException {
    Method method = ComputerMock.class.getMethod("clickAt", int.class, int.class);
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {2000, 2000});

    assertThat(tool.runAsync(ImmutableMap.of("x", 1000, "y", 1000), toolContext).blockingGet())
        .isNotNull();
    assertThat(computerMock.lastX).isEqualTo(960); // 1000/2000 * 1920
    assertThat(computerMock.lastY).isEqualTo(540); // 1000/2000 * 1080
  }

  @Test
  public void testNormalizeDragAndDrop() throws NoSuchMethodException {
    Method method =
        ComputerMock.class.getMethod("dragAndDrop", int.class, int.class, int.class, int.class);
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {1000, 1000});

    Map<String, Object> result =
        tool.runAsync(
                ImmutableMap.of("x", 100, "y", 200, "destination_x", 800, "destination_y", 600),
                toolContext)
            .blockingGet();
    assertThat(result).isNotNull();

    assertThat(computerMock.lastX).isEqualTo(192);
    assertThat(computerMock.lastY).isEqualTo(216);
    assertThat(computerMock.lastDestX).isEqualTo(1536);
    assertThat(computerMock.lastDestY).isEqualTo(648);
  }

  @Test
  public void testResultFormatting() throws NoSuchMethodException {
    byte[] screenshot = new byte[] {1, 2, 3};
    computerMock.nextState =
        ComputerState.builder()
            .screenshot(screenshot)
            .url(Optional.of("https://example.com"))
            .build();

    Method method = ComputerMock.class.getMethod("clickAt", int.class, int.class);
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {1000, 1000});

    Map<String, Object> result =
        tool.runAsync(ImmutableMap.of("x", 500, "y", 500), toolContext).blockingGet();
    assertThat(result).containsKey("image");
    Object imageData = result.get("image");
    assertThat(imageData).isInstanceOf(Map.class);
    ((Map<?, ?>) imageData)
        .forEach(
            (key, value) -> {
              assertThat(key).isInstanceOf(String.class);
              assertThat(value).isInstanceOf(String.class);
            });
    @SuppressWarnings("unchecked") // The types of the key and value are checked above.
    Map<String, Object> imageMap = (Map<String, Object>) imageData;
    assertThat(imageMap.get("mimetype")).isEqualTo("image/png");
    assertThat(imageMap.get("data")).isEqualTo(Base64.getEncoder().encodeToString(screenshot));
    assertThat(result.get("url")).isEqualTo("https://example.com");
    assertThat(result).containsKey("image");
    assertThat(result).doesNotContainKey("screenshot");
  }

  @Test
  public void testResultFormatting_noScreenshot() throws NoSuchMethodException {
    Method method = ComputerMock.class.getMethod("noScreenshot");
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {1000, 1000});

    Map<String, Object> result = tool.runAsync(ImmutableMap.of(), toolContext).blockingGet();
    assertThat(result).doesNotContainKey("image");
    assertThat(result.get("url")).isEqualTo("https://example.com");
  }

  @Test
  public void testResultFormatting_nonByteArrayScreenshot() throws NoSuchMethodException {
    Method method = ComputerMock.class.getMethod("nonByteArrayScreenshot");
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {1000, 1000});

    Map<String, Object> result = tool.runAsync(ImmutableMap.of(), toolContext).blockingGet();
    assertThat(result).doesNotContainKey("image");
    assertThat(result.get("screenshot")).isEqualTo("not-a-byte-array");
  }

  @Test
  public void testNormalizeWithInvalidInputs() throws NoSuchMethodException {
    Method method = ComputerMock.class.getMethod("clickAt", int.class, int.class);
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {1000, 1000});

    assertThrows(
        IllegalArgumentException.class,
        () -> tool.runAsync(ImmutableMap.of("x", "invalid", "y", 500), toolContext).blockingGet());
  }

  @Test
  public void testRunAsyncWithNoCoordinates() throws NoSuchMethodException {
    Method method = ComputerMock.class.getMethod("clickAt", int.class, int.class);
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {1000, 1000});

    // Arguments without x, y, etc. should be passed as is.
    ImmutableMap<String, Object> args = ImmutableMap.of("other", "value");
    var unused = tool.runAsync(args, toolContext).blockingGet();
    assertThat(computerMock.lastX).isEqualTo(0);
    assertThat(computerMock.lastY).isEqualTo(0);
  }

  @Test
  public void testCoordinateClamping() throws NoSuchMethodException {
    Method method = ComputerMock.class.getMethod("clickAt", int.class, int.class);
    ComputerUseTool tool =
        new ComputerUseTool(computerMock, method, new int[] {1920, 1080}, new int[] {1000, 1000});

    // Test clamping to 0
    var unused1 = tool.runAsync(ImmutableMap.of("x", -100, "y", -50), toolContext).blockingGet();
    assertThat(computerMock.lastX).isEqualTo(0);
    assertThat(computerMock.lastY).isEqualTo(0);

    // Test clamping to max
    var unused2 = tool.runAsync(ImmutableMap.of("x", 2000, "y", 1500), toolContext).blockingGet();
    assertThat(computerMock.lastX).isEqualTo(1919);
    assertThat(computerMock.lastY).isEqualTo(1079);
  }

  /** A mock class for Computer actions. */
  public static class ComputerMock {
    public int lastX;
    public int lastY;
    public int lastDestX;
    public int lastDestY;
    public ComputerState nextState =
        ComputerState.builder().screenshot(new byte[0]).url(Optional.empty()).build();

    public Single<ComputerState> clickAt(@Schema(name = "x") int x, @Schema(name = "y") int y) {
      this.lastX = x;
      this.lastY = y;
      return Single.just(nextState);
    }

    public Single<ComputerState> dragAndDrop(
        @Schema(name = "x") int x,
        @Schema(name = "y") int y,
        @Schema(name = "destination_x") int destinationX,
        @Schema(name = "destination_y") int destinationY) {
      this.lastX = x;
      this.lastY = y;
      this.lastDestX = destinationX;
      this.lastDestY = destinationY;
      return Single.just(nextState);
    }

    public Single<Map<String, Object>> noScreenshot() {
      return Single.just(ImmutableMap.of("url", "https://example.com"));
    }

    public Single<Map<String, Object>> nonByteArrayScreenshot() {
      return Single.just(ImmutableMap.of("screenshot", "not-a-byte-array"));
    }
  }
}
