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
package com.google.adk.reasoning;

import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic {@link BaseLlm} test double that returns canned model text (or an error) and
 * records the last request it received.
 */
final class FakeLlm extends BaseLlm {

  private final List<LlmResponse> responses;
  private final RuntimeException error;

  /** The most recent request passed to {@link #generateContent}, for prompt-routing assertions. */
  LlmRequest lastRequest;

  private FakeLlm(List<LlmResponse> responses, RuntimeException error) {
    super("fake-model");
    this.responses = responses;
    this.error = error;
  }

  /** Emits one model turn per supplied text. */
  static FakeLlm returningText(String... texts) {
    List<LlmResponse> responses = new ArrayList<>();
    for (String text : texts) {
      responses.add(
          LlmResponse.builder()
              .content(
                  Content.builder()
                      .role("model")
                      .parts(ImmutableList.of(Part.fromText(text)))
                      .build())
              .build());
    }
    return new FakeLlm(responses, null);
  }

  /** Emits no content at all (empty stream). */
  static FakeLlm returningNothing() {
    return new FakeLlm(ImmutableList.of(), null);
  }

  /** Fails the stream with the given error. */
  static FakeLlm erroring(RuntimeException error) {
    return new FakeLlm(ImmutableList.of(), error);
  }

  /** Returns the text of the user turn in the last request (joined across parts). */
  String lastUserText() {
    StringBuilder sb = new StringBuilder();
    for (Content content : lastRequest.contents()) {
      content.parts().ifPresent(parts -> parts.forEach(part -> part.text().ifPresent(sb::append)));
    }
    return sb.toString();
  }

  /** Returns the system-instruction text of the last request (joined across parts). */
  String lastSystemText() {
    StringBuilder sb = new StringBuilder();
    lastRequest
        .config()
        .flatMap(GenerateContentConfig::systemInstruction)
        .flatMap(Content::parts)
        .ifPresent(parts -> parts.forEach(part -> part.text().ifPresent(sb::append)));
    return sb.toString();
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    this.lastRequest = llmRequest;
    if (error != null) {
      return Flowable.error(error);
    }
    return Flowable.fromIterable(responses);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    throw new UnsupportedOperationException("FakeLlm does not support live connections");
  }
}
