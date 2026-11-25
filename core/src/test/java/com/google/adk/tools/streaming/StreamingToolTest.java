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

package com.google.adk.tools.streaming;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.adk.agents.LiveRequest;
import com.google.adk.agents.LiveRequestQueue;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StreamingToolTest {

  private static final RunConfig BIDI_STREAMING_RUN_CONFIG =
      RunConfig.builder().setStreamingMode(StreamingMode.BIDI).build();

  public static final class StreamingTools {
    public static Flowable<ImmutableMap<String, Object>> monitorStockPrice(
        @Schema(name = "stockSymbol") String stockSymbol) {
      return Flowable.just(
              ImmutableMap.<String, Object>of(
                  "price_alert", String.format("Stock %s price: $150", stockSymbol)),
              ImmutableMap.<String, Object>of(
                  "price_alert", String.format("Stock %s price: $155", stockSymbol)),
              ImmutableMap.<String, Object>of(
                  "price_alert", String.format("Stock %s price: $160", stockSymbol)))
          .doOnSubscribe(
              disposable ->
                  System.out.println("STREAM STARTED: Monitoring stock price for " + stockSymbol))
          .doOnComplete(() -> System.out.println("STREAM COMPLETED: Monitoring " + stockSymbol))
          .doOnCancel(
              () -> System.out.println("STREAM STOPPED: Stopped monitoring " + stockSymbol));
    }

    public static Flowable<ImmutableMap<String, Object>> monitorVideoStream(
        LiveRequestQueue inputStream) {
      return inputStream
          .get()
          .filter(req -> req.blob().isPresent())
          .map(
              liveRequest -> {
                return "Processed frame: detected 2 people";
              })
          .take(3)
          .map(detection -> ImmutableMap.<String, Object>of("people_count_alert", detection));
    }

    public static ImmutableMap<String, Object> stopStreaming(String functionName) {
      return ImmutableMap.of("status", "stopped " + functionName);
    }

    private StreamingTools() {}
  }

  /** A streaming tool that never completes and records whether it was canceled. */
  public static final class EndlessWatch {
    final CountDownLatch canceled = new CountDownLatch(1);

    public Flowable<ImmutableMap<String, Object>> watchForever() {
      return Flowable.<ImmutableMap<String, Object>>never().doOnCancel(canceled::countDown);
    }
  }

  public static ImmutableMap<String, Object> getWeather(String location, String unit) {
    return ImmutableMap.of(
        "temperature", 22, "condition", "sunny", "location", location, "unit", unit);
  }

  @Test
  public void runLive_asyncFunctionCall_succeeds() throws Exception {
    Part functionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("getWeather")
                    .args(ImmutableMap.of("location", "San Francisco", "unit", "celsius"))
                    .build())
            .build();

    LlmResponse response1 =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(ImmutableList.of(functionCall)).build())
            .turnComplete(false)
            .build();
    LlmResponse response2 = LlmResponse.builder().turnComplete(true).build();

    TestLlm testLlm = new TestLlm(ImmutableList.of(response1, response2));

    LlmAgent rootAgent =
        LlmAgent.builder()
            .name("root_agent")
            .model(testLlm)
            .tools(ImmutableList.of(FunctionTool.create(StreamingToolTest.class, "getWeather")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("What is the weather in San Francisco?".getBytes(UTF_8), "audio/pcm")
                    .inlineData()
                    .get())
            .build());

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    List<Event> resEvents =
        runner.runLive(session, liveRequestQueue, BIDI_STREAMING_RUN_CONFIG).toList().blockingGet();

    assertThat(resEvents).isNotNull();
    assertThat(resEvents).isNotEmpty();

    boolean functionCallFound = false;
    boolean functionResponseFound = false;

    for (Event event : resEvents) {
      if (event.content().isPresent()) {
        for (Part part : event.content().get().parts().orElse(ImmutableList.of())) {
          if (part.functionCall().isPresent()) {
            FunctionCall fc = part.functionCall().get();
            if (fc.name().get().equals("getWeather")) {
              functionCallFound = true;
              assertThat(fc.args().get().get("location")).isEqualTo("San Francisco");
              assertThat(fc.args().get().get("unit")).isEqualTo("celsius");
            }
          } else if (part.functionResponse().isPresent()) {
            FunctionResponse fr = part.functionResponse().get();
            if (fr.name().get().equals("getWeather")) {
              functionResponseFound = true;
              assertThat(fr.response().get().get("temperature")).isEqualTo(22);
              assertThat(fr.response().get().get("condition")).isEqualTo("sunny");
            }
          }
        }
      }
    }

    assertThat(functionCallFound).isTrue();
    assertThat(functionResponseFound).isTrue();
  }

  public static ImmutableMap<String, Object> getWeatherWithError(String location) {
    if (location.equals("Invalid Location")) {
      return ImmutableMap.of("error", "Location not found");
    }
    return ImmutableMap.of("temperature", 22, "condition", "sunny", "location", location);
  }

  @Test
  public void runLive_functionCall_returnsErrors() throws Exception {
    Part functionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("getWeatherWithError")
                    .args(ImmutableMap.of("location", "Invalid Location"))
                    .build())
            .build();

    LlmResponse response1 =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(ImmutableList.of(functionCall)).build())
            .turnComplete(false)
            .build();
    LlmResponse response2 = LlmResponse.builder().turnComplete(true).build();

    TestLlm testLlm = new TestLlm(ImmutableList.of(response1, response2));

    LlmAgent rootAgent =
        LlmAgent.builder()
            .name("root_agent")
            .model(testLlm)
            .tools(
                ImmutableList.of(
                    FunctionTool.create(StreamingToolTest.class, "getWeatherWithError")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("What is weather in Invalid Location?".getBytes(UTF_8), "audio/pcm")
                    .inlineData()
                    .get())
            .build());

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    List<Event> resEvents =
        runner.runLive(session, liveRequestQueue, BIDI_STREAMING_RUN_CONFIG).toList().blockingGet();

    assertThat(resEvents).isNotNull();
    assertThat(resEvents).isNotEmpty();

    boolean functionCallFound = false;
    boolean functionResponseFound = false;
    for (Event event : resEvents) {
      if (event.content().isPresent()) {
        for (Part part : event.content().get().parts().orElse(ImmutableList.of())) {
          if (part.functionCall().isPresent()) {
            FunctionCall fc = part.functionCall().get();
            if (fc.name().get().equals("getWeatherWithError")) {
              functionCallFound = true;
              assertThat(fc.args().get().get("location")).isEqualTo("Invalid Location");
            }
          } else if (part.functionResponse().isPresent()) {
            FunctionResponse fr = part.functionResponse().get();
            if (fr.name().get().equals("getWeatherWithError")) {
              functionResponseFound = true;
              assertThat(fr.response().get().get("error")).isEqualTo("Location not found");
            }
          }
        }
      }
    }
    assertThat(functionCallFound).isTrue();
    assertThat(functionResponseFound).isTrue();
  }

  @Test
  public void runLive_videoStreamingTool_receivesVideoFramesAndSendsResultsToLlm()
      throws Exception {
    // Setup LLM to return a function call to monitorVideoStream, then a final response.
    Part functionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder().name("monitorVideoStream").args(ImmutableMap.of()).build())
            .build();
    LlmResponse response1 =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(ImmutableList.of(functionCall)).build())
            .turnComplete(false)
            .build();
    LlmResponse response2 = LlmResponse.builder().turnComplete(true).build();
    CompletableSubject modelDone = CompletableSubject.create();
    TestLlm testLlm = openModelStream(modelDone, response1, response2);
    LlmAgent rootAgent =
        LlmAgent.builder()
            .name("root_agent")
            .model(testLlm)
            .tools(
                ImmutableList.of(
                    FunctionTool.create(StreamingTools.class, "monitorVideoStream"),
                    FunctionTool.create(StreamingTools.class, "stopStreaming")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();

    // Send initial request to trigger the tool.
    liveRequestQueue.send(
        LiveRequest.builder()
            .content(Content.fromParts(Part.fromText("Monitor the video stream")))
            .build());

    // Run the agent and collect events.
    TestSubscriber<Event> run =
        runner.runLive(session, liveRequestQueue, BIDI_STREAMING_RUN_CONFIG).test();
    assertThat(awaitToolStarted(run, "monitorVideoStream")).isTrue();
    // The tool has started, so it reads video frames sent from this point on.
    sendVideoFrames(liveRequestQueue, 3);

    // Wait for the tool to send its 3 results back to the LLM
    assertThat(testLlm.waitForStreamingToolResults("monitorVideoStream", 3, Duration.ofSeconds(20)))
        .isTrue();
    List<Event> resEvents = finishRun(run, liveRequestQueue, modelDone);
    // Assert that the function call was made.
    boolean functionCallFound =
        resEvents.stream()
            .anyMatch(
                event ->
                    event.content().isPresent()
                        && event.content().get().parts().orElse(ImmutableList.of()).stream()
                            .anyMatch(
                                part ->
                                    part.functionCall().isPresent()
                                        && part.functionCall()
                                            .get()
                                            .name()
                                            .get()
                                            .equals("monitorVideoStream")));
    assertThat(functionCallFound).isTrue();

    // Assert that the tool's output was sent back to the LLM.
    ImmutableList<LiveRequest> liveRequestHistory = testLlm.getLiveRequestHistory();
    List<String> sentToolOutputsToLlm = new ArrayList<>();
    for (LiveRequest request : liveRequestHistory) {
      if (request.content().isPresent()) {
        Content content = request.content().get();
        if (content.role().orElse("").equals("user")) {
          String text = content.text();
          if (text != null && text.startsWith("Function monitorVideoStream returned:")) {
            sentToolOutputsToLlm.add(text);
          }
        }
      }
    }

    assertThat(sentToolOutputsToLlm)
        .containsExactly(
            "Function monitorVideoStream returned: {people_count_alert=Processed frame: detected 2"
                + " people}",
            "Function monitorVideoStream returned: {people_count_alert=Processed frame: detected 2"
                + " people}",
            "Function monitorVideoStream returned: {people_count_alert=Processed frame: detected 2"
                + " people}")
        .inOrder();
  }

  /**
   * Returns a model whose live stream sends {@code responses} and stays open until {@code done}.
   */
  private static TestLlm openModelStream(CompletableSubject done, LlmResponse... responses) {
    return new TestLlm(
        () -> Flowable.fromArray(responses).concatWith(done.<LlmResponse>toFlowable()));
  }

  /**
   * Waits for the run to emit the response to the call that started {@code toolName}, and returns
   * whether it did.
   */
  private static boolean awaitToolStarted(TestSubscriber<Event> run, String toolName)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(20);
    while (Instant.now().isBefore(deadline)) {
      if (run.values().stream()
          .flatMap(event -> event.functionResponses().stream())
          .anyMatch(response -> response.name().orElse("").equals(toolName))) {
        return true;
      }
      Thread.sleep(10);
    }
    return false;
  }

  /**
   * Closes the queue and the model stream, waits for the run to complete, and returns its events.
   */
  private static List<Event> finishRun(
      TestSubscriber<Event> run, LiveRequestQueue liveRequestQueue, CompletableSubject modelDone)
      throws InterruptedException {
    liveRequestQueue.close();
    modelDone.onComplete();
    assertThat(run.await(20, TimeUnit.SECONDS)).isTrue();
    run.assertComplete().assertNoErrors();
    return run.values();
  }

  private static void sendVideoFrames(LiveRequestQueue liveRequestQueue, int count) {
    for (int i = 1; i <= count; i++) {
      liveRequestQueue.realtime(
          Part.fromBytes(("fake_jpeg_data_" + i).getBytes(UTF_8), "image/jpeg").inlineData().get());
    }
  }

  @Test
  public void runLive_stopStreamingTool() throws Exception {
    Part startFunctionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("monitorStockPrice")
                    .args(ImmutableMap.of("stockSymbol", "TSLA"))
                    .build())
            .build();
    Part stopFunctionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("stopStreaming")
                    .args(ImmutableMap.of("functionName", "monitorStockPrice"))
                    .build())
            .build();

    LlmResponse response1 =
        LlmResponse.builder()
            .content(
                Content.builder().role("model").parts(ImmutableList.of(startFunctionCall)).build())
            .turnComplete(false)
            .build();
    LlmResponse response2 =
        LlmResponse.builder()
            .content(
                Content.builder().role("model").parts(ImmutableList.of(stopFunctionCall)).build())
            .turnComplete(false)
            .build();
    LlmResponse response3 = LlmResponse.builder().turnComplete(true).build();

    TestLlm testLlm = new TestLlm(ImmutableList.of(response1, response2, response3));

    LlmAgent rootAgent =
        LlmAgent.builder()
            .name("root_agent")
            .model(testLlm)
            .tools(
                ImmutableList.of(
                    FunctionTool.create(StreamingTools.class, "monitorStockPrice"),
                    FunctionTool.create(StreamingTools.class, "stopStreaming")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.send(
        LiveRequest.builder()
            .content(Content.fromParts(Part.fromText("Monitor TSLA and then stop")))
            .build());

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    List<Event> resEvents =
        runner.runLive(session, liveRequestQueue, BIDI_STREAMING_RUN_CONFIG).toList().blockingGet();

    assertThat(resEvents).isNotNull();
    assertThat(resEvents.size()).isAtLeast(1);

    boolean monitorCallFound =
        resEvents.stream()
            .anyMatch(
                event ->
                    event.content().isPresent()
                        && event.content().get().parts().orElse(ImmutableList.of()).stream()
                            .anyMatch(
                                part ->
                                    part.functionCall().isPresent()
                                        && part.functionCall()
                                            .get()
                                            .name()
                                            .get()
                                            .equals("monitorStockPrice")
                                        && part.functionCall()
                                            .get()
                                            .args()
                                            .get()
                                            .get("stockSymbol")
                                            .equals("TSLA")));
    assertThat(monitorCallFound).isTrue();

    boolean stopCallFound =
        resEvents.stream()
            .anyMatch(
                event ->
                    event.content().isPresent()
                        && event.content().get().parts().orElse(ImmutableList.of()).stream()
                            .anyMatch(
                                part ->
                                    part.functionCall().isPresent()
                                        && part.functionCall()
                                            .get()
                                            .name()
                                            .get()
                                            .equals("stopStreaming")
                                        && part.functionCall()
                                            .get()
                                            .args()
                                            .get()
                                            .get("functionName")
                                            .equals("monitorStockPrice")));
    assertThat(stopCallFound).isTrue();
  }

  @Test
  public void runLive_modelStreamEnds_stopsStreamingTools() throws Exception {
    EndlessWatch watch = new EndlessWatch();
    CompletableSubject modelDone = CompletableSubject.create();
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    TestSubscriber<Event> run = startWatch(watch, modelDone, liveRequestQueue);

    List<Event> unused = finishRun(run, liveRequestQueue, modelDone);

    assertThat(watch.canceled.getCount()).isEqualTo(0);
  }

  @Test
  public void runLive_callerCancels_stopsStreamingTools() throws Exception {
    EndlessWatch watch = new EndlessWatch();
    TestSubscriber<Event> run =
        startWatch(watch, CompletableSubject.create(), new LiveRequestQueue());

    run.cancel();

    assertThat(watch.canceled.getCount()).isEqualTo(0);
  }

  /** Starts a run where the model calls {@code watch}'s tool and waits until the tool runs. */
  private static TestSubscriber<Event> startWatch(
      EndlessWatch watch, CompletableSubject modelDone, LiveRequestQueue liveRequestQueue)
      throws InterruptedException {
    TestLlm testLlm =
        openModelStream(
            modelDone,
            LlmResponse.builder()
                .content(
                    Content.builder()
                        .role("model")
                        .parts(Part.fromFunctionCall("watchForever", ImmutableMap.of()))
                        .build())
                .build(),
            LlmResponse.builder().turnComplete(true).build());
    LlmAgent agent =
        LlmAgent.builder()
            .name("root_agent")
            .model(testLlm)
            .tools(ImmutableList.of(FunctionTool.create(watch, "watchForever")))
            .build();
    InMemoryRunner runner = new InMemoryRunner(agent);
    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    TestSubscriber<Event> run =
        runner.runLive(session, liveRequestQueue, BIDI_STREAMING_RUN_CONFIG).test();
    assertThat(awaitToolStarted(run, "watchForever")).isTrue();
    assertThat(watch.canceled.getCount()).isEqualTo(1);
    return run;
  }

  @Test
  public void runLive_streamingTool_responsesAreSentAsUserContentToLlm() throws Exception {
    Part functionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("monitorStockPrice")
                    .args(ImmutableMap.of("stockSymbol", "GOOG"))
                    .build())
            .build();

    LlmResponse response1 =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(ImmutableList.of(functionCall)).build())
            .turnComplete(false)
            .build();
    LlmResponse response2 = LlmResponse.builder().turnComplete(true).build();

    CompletableSubject modelDone = CompletableSubject.create();
    TestLlm testLlm = openModelStream(modelDone, response1, response2);

    LlmAgent rootAgent =
        LlmAgent.builder()
            .name("root_agent")
            .model(testLlm)
            .tools(
                ImmutableList.of(
                    FunctionTool.create(StreamingTools.class, "monitorStockPrice"),
                    FunctionTool.create(StreamingTools.class, "stopStreaming")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.send(
        LiveRequest.builder()
            .content(Content.fromParts(Part.fromText("Monitor GOOG stock price")))
            .build());

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();

    TestSubscriber<Event> run =
        runner.runLive(session, liveRequestQueue, BIDI_STREAMING_RUN_CONFIG).test();

    // Wait for the tool to send its 3 results back to the LLM
    assertThat(testLlm.waitForStreamingToolResults("monitorStockPrice", 3, Duration.ofSeconds(20)))
        .isTrue();
    List<Event> resEvents = finishRun(run, liveRequestQueue, modelDone);

    assertThat(resEvents).isNotNull();
    assertThat(resEvents).isNotEmpty();

    boolean functionCallFound = false;
    for (Event event : resEvents) {
      if (event.content().isPresent()) {
        for (Part part : event.content().get().parts().orElse(ImmutableList.of())) {
          if (part.functionCall().isPresent()) {
            FunctionCall fc = part.functionCall().get();
            if (fc.name().get().equals("monitorStockPrice")) {
              functionCallFound = true;
              assertThat(fc.args().get().get("stockSymbol")).isEqualTo("GOOG");
            }
          }
        }
      }
    }

    assertThat(functionCallFound).isTrue();
    ImmutableList<LiveRequest> liveRequestHistory = testLlm.getLiveRequestHistory();
    List<String> sentPriceAlertsToLlm = new ArrayList<>();

    for (LiveRequest request : liveRequestHistory) {
      if (request.content().isPresent()) {
        Content content = request.content().get();
        // The first content sent is the initial user message "Monitor GOOG stock price".
        // Subsequent contents with role "user" and text are the streaming tool outputs.
        if (content.role().orElse("").equals("user")) {
          String text = content.text();
          // Filter out the initial user message and only collect the tool outputs.
          if (text.startsWith("Function monitorStockPrice returned:")) {
            sentPriceAlertsToLlm.add(text);
          }
        }
      }
    }

    assertThat(sentPriceAlertsToLlm)
        .containsExactly(
            "Function monitorStockPrice returned: {price_alert=Stock GOOG price: $150}",
            "Function monitorStockPrice returned: {price_alert=Stock GOOG price: $155}",
            "Function monitorStockPrice returned: {price_alert=Stock GOOG price: $160}")
        .inOrder();
  }
}
