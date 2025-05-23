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

// LoadArtifactsTool.java
package com.google.adk.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.JsonBaseModel;
import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A tool that loads artifacts and adds them to the session.
 *
 * <p>This tool informs the model about available artifacts and provides their content when
 * requested by the model through a function call.
 */
public final class LoadArtifactsTool extends BaseTool {
  public LoadArtifactsTool() {
    super("load_artifacts", "Loads the artifacts and adds them to the session.");
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name(this.name())
            .description(this.description())
            .parameters(
                Schema.builder()
                    .type("OBJECT")
                    .properties(
                        ImmutableMap.of(
                            "artifact_names",
                            Schema.builder()
                                .type("ARRAY")
                                .items(Schema.builder().type("STRING").build())
                                .build()))
                    .build())
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    @SuppressWarnings("unchecked")
    List<String> artifactNames =
        (List<String>) args.getOrDefault("artifact_names", ImmutableList.of());
    return Single.just(ImmutableMap.of("artifact_names", artifactNames));
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {
    return super.processLlmRequest(llmRequestBuilder, toolContext)
        .andThen(appendArtifactsToLlmRequest(llmRequestBuilder, toolContext));
  }

  public Completable appendArtifactsToLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {

    return toolContext
        .listArtifacts()
        .flatMapCompletable(
            artifactNamesList -> {
              if (artifactNamesList.isEmpty()) {
                return Completable.complete();
              }

              appendInitialInstructions(llmRequestBuilder, artifactNamesList);

              return processLoadArtifactsFunctionCall(llmRequestBuilder, toolContext);
            });
  }

  private void appendInitialInstructions(
      LlmRequest.Builder llmRequestBuilder, List<String> artifactNamesList) {
    try {
      String instructions =
          String.format(
              "You have a list of artifacts:\n  %s\n\nWhen the user asks questions about"
                  + " any of the artifacts, you should call the `load_artifacts` function"
                  + " to load the artifact. Do not generate any text other than the"
                  + " function call.",
              JsonBaseModel.getMapper().writeValueAsString(artifactNamesList));
      llmRequestBuilder.appendInstructions(ImmutableList.of(instructions));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize artifact names to JSON", e);
    }
  }

  private Completable processLoadArtifactsFunctionCall(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {

    LlmRequest currentRequestState = llmRequestBuilder.build();
    List<Content> currentContents = currentRequestState.contents();

    if (currentContents.isEmpty()) {
      return Completable.complete();
    }

    return Iterables.getLast(currentContents)
        .parts()
        .filter(partsList -> !partsList.isEmpty())
        .flatMap(partsList -> partsList.get(0).functionResponse())
        .filter(fr -> Objects.equals(fr.name().orElse(null), "load_artifacts"))
        .flatMap(FunctionResponse::response)
        .flatMap(responseMap -> Optional.ofNullable(responseMap.get("artifact_names")))
        .filter(obj -> obj instanceof List)
        .map(obj -> (List<?>) obj)
        .filter(list -> !list.isEmpty())
        .map(
            artifactNamesRaw -> {
              @SuppressWarnings("unchecked")
              List<String> artifactNamesToLoad = (List<String>) artifactNamesRaw;

              return Observable.fromIterable(artifactNamesToLoad)
                  .flatMapCompletable(
                      artifactName ->
                          loadAndAppendIndividualArtifact(
                              llmRequestBuilder, toolContext, artifactName));
            })
        .orElse(Completable.complete());
  }

  private Completable loadAndAppendIndividualArtifact(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext, String artifactName) {

    return toolContext
        .loadArtifact(artifactName, Optional.empty())
        .flatMapCompletable(
            actualArtifact ->
                Completable.fromAction(
                    () ->
                        appendArtifactToLlmRequest(
                            llmRequestBuilder,
                            "Artifact " + artifactName + " is:",
                            actualArtifact)));
  }

  private void appendArtifactToLlmRequest(
      LlmRequest.Builder llmRequestBuilder, String prefix, Part artifact) {
    llmRequestBuilder.contents(
        ImmutableList.<Content>builder()
            .addAll(llmRequestBuilder.build().contents())
            .add(
                Content.builder()
                    .role("user")
                    .parts(ImmutableList.of(Part.fromText(prefix), artifact))
                    .build())
            .build());
  }
}
