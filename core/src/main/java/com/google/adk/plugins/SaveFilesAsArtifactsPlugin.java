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
package com.google.adk.plugins;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plugin that saves files embedded in user messages as artifacts.
 *
 * <p>This is useful to allow users to upload files in the chat experience and have those files
 * available to the agent within the current session.
 *
 * <p>Artifacts with the same name will be overwritten. A placeholder with the artifact name will be
 * put in place of the embedded file in the user message so the model knows where to find the file.
 * You may want to add load_artifacts tool to the agent, or load the artifacts in your own tool to
 * use the files.
 */
public class SaveFilesAsArtifactsPlugin extends BasePlugin {
  private static final Logger logger = LoggerFactory.getLogger(SaveFilesAsArtifactsPlugin.class);

  private static final ImmutableSet<String> MODEL_ACCESSIBLE_URI_SCHEMES =
      ImmutableSet.of("gs", "https", "http");

  public SaveFilesAsArtifactsPlugin(String name) {
    super(name);
  }

  public SaveFilesAsArtifactsPlugin() {
    this("save_files_as_artifacts_plugin");
  }

  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    if (invocationContext.artifactService() == null) {
      logger.warn("Artifact service is not set. SaveFilesAsArtifactsPlugin will not be enabled.");
      return Maybe.just(userMessage);
    }

    if (userMessage.parts().isEmpty()
        || userMessage.parts().stream()
            .flatMap(List::stream)
            .noneMatch(part -> part.inlineData().isPresent())) {
      return Maybe.empty();
    }

    AtomicBoolean modified = new AtomicBoolean(false);
    AtomicInteger index = new AtomicInteger(0);

    return Flowable.fromIterable(userMessage.parts().get())
        .concatMapSingle(
            part -> {
              if (part.inlineData().isEmpty()) {
                return Single.just(ImmutableList.of(part));
              }
              modified.set(true);
              return saveArtifactAndBuildParts(invocationContext, part, index.getAndIncrement());
            })
        .toList() // Collects Single<ImmutableList<Part>> into a Single<List<ImmutableList<Part>>>
        .map(
            listOfLists ->
                listOfLists.stream()
                    .flatMap(List::stream)
                    .collect(toImmutableList())) // Flatten the list of lists
        .filter(unused -> modified.get())
        .map(
            parts ->
                Content.builder().parts(parts).role(userMessage.role().orElse("user")).build());
  }

  private Single<ImmutableList<Part>> saveArtifactAndBuildParts(
      InvocationContext invocationContext, Part part, int index) {
    Blob inlineData = part.inlineData().get();
    String fileName =
        inlineData
            .displayName()
            .filter(s -> !s.isEmpty())
            .orElseGet(
                () -> {
                  String generatedName =
                      String.format("artifact_%s_%d", invocationContext.invocationId(), index);
                  logger.info("No display_name found, using generated filename: {}", generatedName);
                  return generatedName;
                });
    String displayName = fileName;

    return invocationContext
        .artifactService()
        .saveArtifact(
            invocationContext.appName(),
            invocationContext.userId(),
            invocationContext.session().id(),
            fileName,
            part)
        .flatMap(
            version -> {
              logger.info("Successfully saved artifact: {}", fileName);
              Part placeholderPart =
                  Part.fromText(String.format("[Uploaded Artifact: \"%s\"]", displayName));

              return buildFileReferencePart(
                      invocationContext, fileName, version, inlineData.mimeType(), displayName)
                  .map(filePart -> ImmutableList.of(placeholderPart, filePart))
                  .defaultIfEmpty(ImmutableList.of(placeholderPart));
            })
        .onErrorReturn(
            e -> {
              logger.error("Failed to save artifact for part {}: {}", index, e.getMessage());
              return ImmutableList.of(part); // Keep original part if saving fails
            });
  }

  private Maybe<Part> buildFileReferencePart(
      InvocationContext invocationContext,
      String filename,
      int version,
      Optional<String> mimeType,
      String displayName) {
    BaseArtifactService artifactService = invocationContext.artifactService();
    if (artifactService == null) {
      return Maybe.empty();
    }

    return artifactService
        .loadArtifact(
            invocationContext.appName(),
            invocationContext.userId(),
            invocationContext.session().id(),
            filename,
            Optional.of(version))
        .flatMap(
            artifact -> {
              Optional<Part> optionalPart =
                  artifact
                      .fileData()
                      .filter(fd -> fd.fileUri().map(this::isModelAccessibleUri).orElse(false))
                      .map(
                          fd ->
                              Part.builder()
                                  .fileData(
                                      FileData.builder()
                                          .fileUri(fd.fileUri().get())
                                          .mimeType(
                                              mimeType
                                                  .or(fd::mimeType)
                                                  .orElse("application/octet-stream"))
                                          .displayName(displayName)
                                          .build())
                                  .build());
              if (optionalPart.isPresent()) {
                return Maybe.just(optionalPart.get());
              }
              return Maybe.empty();
            })
        .doOnError(e -> logger.warn("Failed to resolve artifact version for {}: {}", filename, e))
        .onErrorComplete();
  }

  private boolean isModelAccessibleUri(String uri) {
    try {
      URI parsed = new URI(uri);
      return parsed.getScheme() != null
          && MODEL_ACCESSIBLE_URI_SCHEMES.contains(parsed.getScheme().toLowerCase(Locale.ROOT));
    } catch (URISyntaxException e) {
      return false;
    }
  }
}
