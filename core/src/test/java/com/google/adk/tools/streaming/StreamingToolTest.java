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
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.testing.TestLlm;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StreamingToolTest {

  public static ImmutableMap<String, Object> getWeather(String location, String unit) {
    return ImmutableMap.of(
        "temperature", 22, "condition", "sunny", "location", location, "unit", unit);
  }

  @Test
  public void liveStreaming_functionCall_single() throws Exception {
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
        runner
            .runLive(session, liveRequestQueue, RunConfig.builder().build())
            .toList()
            .blockingGet();

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

  public static ImmutableMap<String, Object> getTime(String timezone) {
    return ImmutableMap.of("time", "14:30", "timezone", timezone);
  }

  @Test
  public void liveStreaming_functionCall_multiple() throws Exception {
    Part functionCall1 =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("getWeather")
                    .args(ImmutableMap.of("location", "San Francisco"))
                    .build())
            .build();
    Part functionCall2 =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("getTime")
                    .args(ImmutableMap.of("timezone", "PST"))
                    .build())
            .build();

    LlmResponse response1 =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(ImmutableList.of(functionCall1)).build())
            .turnComplete(false)
            .build();
    LlmResponse response2 =
        LlmResponse.builder()
            .content(Content.builder().role("model").parts(ImmutableList.of(functionCall2)).build())
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
                    FunctionTool.create(StreamingToolTest.class, "getWeather"),
                    FunctionTool.create(StreamingToolTest.class, "getTime")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("What is the weather and current time?".getBytes(UTF_8), "audio/pcm")
                    .inlineData()
                    .get())
            .build());

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    List<Event> resEvents =
        runner
            .runLive(session, liveRequestQueue, RunConfig.builder().build())
            .toList()
            .blockingGet();

    assertThat(resEvents).isNotNull();
    assertThat(resEvents).isNotEmpty();

    boolean weatherCallFound = false;
    boolean timeCallFound = false;

    for (Event event : resEvents) {
      if (event.content().isPresent()) {
        for (Part part : event.content().get().parts().orElse(ImmutableList.of())) {
          if (part.functionCall().isPresent()) {
            FunctionCall fc = part.functionCall().get();
            if (fc.name().get().equals("getWeather")) {
              weatherCallFound = true;
              assertThat(fc.args().get().get("location")).isEqualTo("San Francisco");
            } else if (fc.name().get().equals("getTime")) {
              timeCallFound = true;
              assertThat(fc.args().get().get("timezone")).isEqualTo("PST");
            }
          }
        }
      }
    }
    assertThat(weatherCallFound).isTrue();
    assertThat(timeCallFound).isTrue();
  }

  @Test
  public void liveStreaming_functionCall_parallel() throws Exception {
    Part functionCall1 =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("getWeather")
                    .args(ImmutableMap.of("location", "San Francisco"))
                    .build())
            .build();
    Part functionCall2 =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("getWeather")
                    .args(ImmutableMap.of("location", "New York"))
                    .build())
            .build();

    LlmResponse response1 =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(functionCall1, functionCall2))
                    .build())
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
                Part.fromBytes("Compare weather in SF and NYC".getBytes(UTF_8), "audio/pcm")
                    .inlineData()
                    .get())
            .build());

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    List<Event> resEvents =
        runner
            .runLive(session, liveRequestQueue, RunConfig.builder().build())
            .toList()
            .blockingGet();

    assertThat(resEvents).isNotNull();
    assertThat(resEvents).isNotEmpty();

    boolean sfCallFound = false;
    boolean nycCallFound = false;

    for (Event event : resEvents) {
      if (event.content().isPresent()) {
        for (Part part : event.content().get().parts().orElse(ImmutableList.of())) {
          if (part.functionCall().isPresent()) {
            FunctionCall fc = part.functionCall().get();
            if (fc.name().get().equals("getWeather")) {
              String location = (String) fc.args().get().get("location");
              if (location.equals("San Francisco")) {
                sfCallFound = true;
              } else if (location.equals("New York")) {
                nycCallFound = true;
              }
            }
          }
        }
      }
    }
    assertThat(sfCallFound).isTrue();
    assertThat(nycCallFound).isTrue();
  }

  public static ImmutableMap<String, Object> getWeatherWithError(String location) {
    if (location.equals("Invalid Location")) {
      return ImmutableMap.of("error", "Location not found");
    }
    return ImmutableMap.of("temperature", 22, "condition", "sunny", "location", location);
  }

  @Test
  public void liveStreaming_functionCall_withError() throws Exception {
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
        runner
            .runLive(session, liveRequestQueue, RunConfig.builder().build())
            .toList()
            .blockingGet();

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

  private static class SimpleStreamingTool {
    @SuppressWarnings("unused")
    public static Flowable<Map<String, Object>> monitorStockPrice(String stockSymbol) {
      return Flowable.just(
              ImmutableMap.<String, Object>of(
                  "price_alert", String.format("Stock %s price: $150", stockSymbol)),
              ImmutableMap.<String, Object>of(
                  "price_alert", String.format("Stock %s price: $155", stockSymbol)),
              ImmutableMap.<String, Object>of(
                  "price_alert", String.format("Stock %s price: $160", stockSymbol)))
          .zipWith(Flowable.interval(10, TimeUnit.MILLISECONDS), (item, interval) -> item);
    }

    @SuppressWarnings("unused")
    public static ImmutableMap<String, Object> stopStreaming(String functionName) {
      return ImmutableMap.of("stopped", functionName);
    }
  }

  @Test
  public void liveStreaming_simpleStreamingTool() throws Exception {
    Part functionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("monitorStockPrice")
                    .args(ImmutableMap.of("stockSymbol", "AAPL"))
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
                    FunctionTool.create(SimpleStreamingTool.class, "monitorStockPrice"),
                    FunctionTool.create(SimpleStreamingTool.class, "stopStreaming")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("Monitor AAPL stock price".getBytes(UTF_8), "audio/pcm")
                    .inlineData()
                    .get())
            .build());

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    List<Event> resEvents =
        runner
            .runLive(session, liveRequestQueue, RunConfig.builder().build())
            .toList()
            .blockingGet();

    assertThat(resEvents).isNotNull();
    assertThat(resEvents.size()).isAtLeast(1);

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
                                            .equals("monitorStockPrice")
                                        && part.functionCall()
                                            .get()
                                            .args()
                                            .get()
                                            .get("stockSymbol")
                                            .equals("AAPL")));
    assertThat(functionCallFound).isTrue();
  }

  private static class VideoStreamingTool {
    @SuppressWarnings("unused")
    public static Flowable<Map<String, Object>> monitorVideoStream(LiveRequestQueue inputStream) {
      return inputStream
          .get()
          .filter(req -> req.blob().isPresent())
          .map(
              liveRequest -> {
                // mock processing
                return "Processed frame: detected 2 people";
              })
          .take(3)
          .map(detection -> ImmutableMap.<String, Object>of("people_count_alert", detection));
    }

    @SuppressWarnings("unused")
    public static ImmutableMap<String, Object> stopStreaming(String functionName) {
      return ImmutableMap.of("status", "stopped");
    }
  }

  @Test
  public void liveStreaming_videoStreamingTool() throws Exception {
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
                    FunctionTool.create(VideoStreamingTool.class, "monitorVideoStream"),
                    FunctionTool.create(VideoStreamingTool.class, "stopStreaming")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
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
                Part.fromBytes("Monitor video stream".getBytes(UTF_8), "audio/pcm")
                    .inlineData()
                    .get())
            .build());
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("fake_jpeg_data_3".getBytes(UTF_8), "image/jpeg").inlineData().get())
            .build());
    liveRequestQueue.close();

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    List<Event> resEvents =
        runner
            .runLive(session, liveRequestQueue, RunConfig.builder().build())
            .toList()
            .blockingGet();

    assertThat(resEvents).isNotNull();
    assertThat(resEvents.size()).isAtLeast(1);

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
  }

  private static class StopStreamingTool {
    public static Flowable<Map<String, Object>> monitorStockPrice(String stockSymbol) {
      return Flowable.interval(10, TimeUnit.MILLISECONDS)
          .map(
              i ->
                  ImmutableMap.<String, Object>of(
                      "price_alert", String.format("Stock %s price update", stockSymbol)));
    }

    public static Map<String, Object> stopStreaming(String functionName) {
      return ImmutableMap.of("status", "stopped " + functionName);
    }
  }

  @Test
  public void liveStreaming_stopStreamingTool() throws Exception {
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
                    FunctionTool.create(StopStreamingTool.class, "monitorStockPrice"),
                    FunctionTool.create(StopStreamingTool.class, "stopStreaming")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("Monitor TSLA and then stop".getBytes(UTF_8), "audio/pcm")
                    .inlineData()
                    .get())
            .build());

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    List<Event> resEvents =
        runner
            .runLive(session, liveRequestQueue, RunConfig.builder().build())
            .toList()
            .blockingGet();

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

  private static class MultipleStreamingTools {
    public static Flowable<Map<String, Object>> monitorStockPrice(String stockSymbol) {
      return Flowable.just(
              ImmutableMap.<String, Object>of(
                  "price_alert", String.format("Stock %s price: $800", stockSymbol)),
              ImmutableMap.<String, Object>of(
                  "price_alert", String.format("Stock %s price: $805", stockSymbol)))
          .zipWith(Flowable.interval(10, TimeUnit.MILLISECONDS), (item, interval) -> item);
    }

    @SuppressWarnings("unused")
    public static Flowable<Map<String, Object>> monitorVideoStream(LiveRequestQueue inputStream) {
      return Flowable.just(
              ImmutableMap.<String, Object>of("video_alert", "Video monitoring started"),
              ImmutableMap.<String, Object>of("video_alert", "Detected motion in video stream"))
          .zipWith(Flowable.interval(10, TimeUnit.MILLISECONDS), (item, interval) -> item);
    }

    public static ImmutableMap<String, Object> stopStreaming(String functionName) {
      return ImmutableMap.of("status", "stopped");
    }
  }

  @Test
  public void liveStreaming_multipleStreamingTools() throws Exception {
    Part stockFunctionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder()
                    .name("monitorStockPrice")
                    .args(ImmutableMap.of("stockSymbol", "NVDA"))
                    .build())
            .build();
    Part videoFunctionCall =
        Part.builder()
            .functionCall(
                FunctionCall.builder().name("monitorVideoStream").args(ImmutableMap.of()).build())
            .build();

    LlmResponse response1 =
        LlmResponse.builder()
            .content(
                Content.builder()
                    .role("model")
                    .parts(ImmutableList.of(stockFunctionCall, videoFunctionCall))
                    .build())
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
                    FunctionTool.create(MultipleStreamingTools.class, "monitorStockPrice"),
                    FunctionTool.create(MultipleStreamingTools.class, "monitorVideoStream"),
                    FunctionTool.create(MultipleStreamingTools.class, "stopStreaming")))
            .build();

    InMemoryRunner runner = new InMemoryRunner(rootAgent);
    LiveRequestQueue liveRequestQueue = new LiveRequestQueue();
    liveRequestQueue.send(
        LiveRequest.builder()
            .blob(
                Part.fromBytes("Monitor both stock and video".getBytes(UTF_8), "audio/pcm")
                    .inlineData()
                    .get())
            .build());

    Session session = runner.sessionService().createSession("test-app", "test-user").blockingGet();
    List<Event> resEvents =
        runner
            .runLive(session, liveRequestQueue, RunConfig.builder().build())
            .toList()
            .blockingGet();

    assertThat(resEvents).isNotNull();
    assertThat(resEvents.size()).isAtLeast(1);

    boolean stockCallFound =
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
                                            .equals("NVDA")));
    assertThat(stockCallFound).isTrue();

    boolean videoCallFound =
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
    assertThat(videoCallFound).isTrue();
  }
}
