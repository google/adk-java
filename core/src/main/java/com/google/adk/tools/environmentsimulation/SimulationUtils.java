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

package com.google.adk.tools.environmentsimulation;

import static java.util.stream.Collectors.joining;

import com.google.adk.models.LlmRegistry;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;

/** The model call and the JSON handling that the simulation's two model-backed passes share. */
final class SimulationUtils {

  /**
   * Asks the named model for a JSON object and returns the text it wrote.
   *
   * <p>The model is looked up by name when the returned {@link Single} is subscribed to, so a
   * simulation that never reaches the model never builds one.
   */
  static Single<String> generateJson(
      String modelName, GenerateContentConfig modelConfig, String prompt) {
    return Single.defer(
        () -> {
          LlmRequest request =
              LlmRequest.builder()
                  .model(modelName)
                  .contents(
                      ImmutableList.of(
                          Content.builder()
                              .role("user")
                              .parts(ImmutableList.of(Part.fromText(prompt)))
                              .build()))
                  .config(modelConfig.toBuilder().responseMimeType("application/json").build())
                  .build();
          return LlmRegistry.getLlm(modelName)
              .generateContent(request, /* stream= */ false)
              .map(SimulationUtils::text)
              .reduce("", String::concat);
        });
  }

  /** Strips the code fence a model puts around JSON when it is feeling helpful. */
  static String stripCodeFences(String text) {
    return text.replaceAll("^```[a-zA-Z]*\n", "").replaceAll("\n```$", "").trim();
  }

  private static String text(LlmResponse response) {
    return response.content().flatMap(Content::parts).orElse(ImmutableList.of()).stream()
        .map(Part::text)
        .flatMap(Optional::stream)
        .collect(joining());
  }

  private SimulationUtils() {}
}
