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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    TestLlm testLlm = new TestLlm(ImmutableList.of(response1, response2));
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

    // Send initial request to trigger the tool, followed by video frames for the tool to process.
    liveRequestQueue.send(
        LiveRequest.builder()
            .content(Content.fromParts(Part.fromText("Monitor the video stream")))
            .build());
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("fake_jpeg_data_1".getBytes(UTF_8), "image/jpeg").inlineData().get())
            .build());
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("fake_jpeg_data_2".getBytes(UTF_8), "image/jpeg").inlineData().get())
            .build());
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("fake_jpeg_data_3".getBytes(UTF_8), "image/jpeg").inlineData().get())
            .build());

    // Run the agent and collect events.
    List<Event> resEvents =
        runner.runLive(session, liveRequestQueue, BIDI_STREAMING_RUN_CONFIG).toList().blockingGet();

    // Wait for the tool to send its 3 results back to the LLM
    assertThat(testLlm.waitForStreamingToolResults("monitorVideoStream", 3, Duration.ofSeconds(20)))
        .isTrue();
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

    TestLlm testLlm = new TestLlm(ImmutableList.of(response1, response2));

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

    List<Event> resEvents =
        runner.runLive(session, liveRequestQueue, BIDI_STREAMING_RUN_CONFIG).toList().blockingGet();

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
