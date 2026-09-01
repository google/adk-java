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

package com.google.adk.memory;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link InMemoryMemoryService}. */
@RunWith(JUnit4.class)
public final class InMemoryMemoryServiceTest {

  private static final String APP_NAME = "app";
  private static final String USER_ID = "user";

  private static Event event(Part... parts) {
    return Event.builder().author("agent").content(Content.fromParts(parts)).build();
  }

  private static Session sessionWith(Event... events) {
    return Session.builder("session")
        .appName(APP_NAME)
        .userId(USER_ID)
        .events(ImmutableList.copyOf(events))
        .build();
  }

  @Test
  public void searchMemory_functionCallPart_skipsPartInsteadOfThrowing() {
    Part functionCall =
        Part.builder().functionCall(FunctionCall.builder().name("loadMemory").build()).build();
    InMemoryMemoryService service = new InMemoryMemoryService();
    service
        .addSessionToMemory(
            sessionWith(event(Part.fromText("weather in Seoul")), event(functionCall)))
        .blockingAwait();

    SearchMemoryResponse response =
        service.searchMemory(APP_NAME, USER_ID, "weather").blockingGet();

    assertThat(response.memories()).hasSize(1);
  }

  @Test
  public void searchMemory_functionResponsePart_skipsPartInsteadOfThrowing() {
    Part functionResponse =
        Part.builder()
            .functionResponse(
                FunctionResponse.builder()
                    .name("loadMemory")
                    .response(Map.of("result", "ok"))
                    .build())
            .build();
    InMemoryMemoryService service = new InMemoryMemoryService();
    service.addSessionToMemory(sessionWith(event(functionResponse))).blockingAwait();

    SearchMemoryResponse response =
        service.searchMemory(APP_NAME, USER_ID, "weather").blockingGet();

    assertThat(response.memories()).isEmpty();
  }

  @Test
  public void searchMemory_inlineDataPart_skipsPartInsteadOfThrowing() {
    Part inlineData = Part.fromBytes(new byte[] {1, 2, 3}, "image/png");
    InMemoryMemoryService service = new InMemoryMemoryService();
    service
        .addSessionToMemory(
            sessionWith(event(Part.fromText("weather in Seoul")), event(inlineData)))
        .blockingAwait();

    SearchMemoryResponse response =
        service.searchMemory(APP_NAME, USER_ID, "weather").blockingGet();

    assertThat(response.memories()).hasSize(1);
  }

  @Test
  public void searchMemory_fileDataPart_skipsPartInsteadOfThrowing() {
    Part fileData =
        Part.builder()
            .fileData(
                FileData.builder()
                    .mimeType("application/pdf")
                    .fileUri("gs://bucket/file.pdf")
                    .build())
            .build();
    InMemoryMemoryService service = new InMemoryMemoryService();
    service
        .addSessionToMemory(sessionWith(event(Part.fromText("weather in Seoul")), event(fileData)))
        .blockingAwait();

    SearchMemoryResponse response =
        service.searchMemory(APP_NAME, USER_ID, "weather").blockingGet();

    assertThat(response.memories()).hasSize(1);
  }

  @Test
  public void searchMemory_textAndFunctionCallInOneEvent_stillMatchesOnTheText() {
    Part functionCall =
        Part.builder().functionCall(FunctionCall.builder().name("loadMemory").build()).build();
    InMemoryMemoryService service = new InMemoryMemoryService();
    service
        .addSessionToMemory(sessionWith(event(Part.fromText("weather in Seoul"), functionCall)))
        .blockingAwait();

    SearchMemoryResponse response =
        service.searchMemory(APP_NAME, USER_ID, "weather").blockingGet();

    assertThat(response.memories()).hasSize(1);
    assertThat(response.memories().get(0).content())
        .isEqualTo(Content.fromParts(Part.fromText("weather in Seoul"), functionCall));
  }
}
