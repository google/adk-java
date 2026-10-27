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

import static java.util.Comparator.naturalOrder;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.sessions.SessionKey;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves files attached to user messages as artifacts, replacing each with a placeholder that names
 * the artifact so agents can load it later, such as with {@link
 * com.google.adk.tools.LoadArtifactsTool}. Each file is saved under its {@link Blob#displayName()
 * display name}, where a {@code user:} prefix shares it across the user's sessions, or under a
 * generated name if it has no display name; files over 20 MB are replaced with an error note
 * instead. Mirrors Python ADK's {@code SaveFilesAsArtifactsPlugin}.
 */
public class SaveFilesAsArtifactsPlugin extends BasePlugin {

  private static final String DEFAULT_NAME = "save_files_as_artifacts_plugin";

  private static final Logger logger = LoggerFactory.getLogger(SaveFilesAsArtifactsPlugin.class);

  private static final int BYTES_PER_MB = 1024 * 1024;

  // The Gemini API's size limit for inline data.
  private static final int MAX_FILE_SIZE_MB = 20;
  private static final int MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * BYTES_PER_MB;

  // The URI schemes models can fetch: Vertex reads `gs`, hosted endpoints `https` and `http`.
  private static final ImmutableSet<String> MODEL_ACCESSIBLE_URI_SCHEMES =
      ImmutableSet.of("gs", "https", "http");

  private static final String PENDING_DELTA_KEY = "%s:pending_delta";
  private static final String GENERATED_FILE_NAME = "artifact_%s_%d";
  private static final String PLACEHOLDER = "[Uploaded Artifact: \"%s\"]";
  private static final String UPLOAD_ERROR =
      "[Upload Error: File %s (%s MB) exceeds the maximum supported size of %dMB. Please upload a"
          + " smaller file.]";

  private final boolean attachFileReference;
  private final String pendingDeltaKey;

  public SaveFilesAsArtifactsPlugin() {
    this(/* attachFileReference= */ true);
  }

  public SaveFilesAsArtifactsPlugin(boolean attachFileReference) {
    this(attachFileReference, DEFAULT_NAME);
  }

  /**
   * Creates the plugin.
   *
   * @param attachFileReference Whether to also attach a {@link FileData} reference to each saved
   *     file when the artifact service stores it at a URI the model can read ({@code gs}, {@code
   *     https} or {@code http}).
   * @param name The unique name of the plugin instance.
   */
  public SaveFilesAsArtifactsPlugin(boolean attachFileReference, String name) {
    super(name);
    this.attachFileReference = attachFileReference;
    this.pendingDeltaKey = PENDING_DELTA_KEY.formatted(name);
  }

  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    BaseArtifactService artifactService = invocationContext.artifactService();
    if (artifactService == null) {
      logger.warn("Artifact service is not set. SaveFilesAsArtifactsPlugin will not be enabled.");
      return Maybe.empty();
    }
    return Maybe.defer(
        () -> {
          ImmutableList<Part> parts =
              ImmutableList.copyOf(userMessage.parts().orElse(ImmutableList.of()));
          Map<String, Integer> savedVersions = new LinkedHashMap<>();
          return Flowable.range(0, parts.size())
              .concatMapSingle(
                  index ->
                      newParts(
                          invocationContext,
                          artifactService,
                          parts.get(index),
                          index,
                          savedVersions))
              .concatMapIterable(replacements -> replacements)
              .toList()
              .filter(newParts -> !newParts.equals(parts))
              .map(
                  newParts -> {
                    if (!savedVersions.isEmpty()) {
                      // Unlike session state, which Python uses, per-invocation data leaves no
                      // entry behind.
                      invocationContext
                          .callbackContextData()
                          .put(
                              pendingDeltaKey,
                              new PendingDelta(ImmutableMap.copyOf(savedVersions)));
                    }
                    return userMessage.toBuilder().parts(ImmutableList.copyOf(newParts)).build();
                  });
        });
  }

  /**
   * Reports the versions saved from the user message on the event of the first agent to run, since
   * a plugin cannot add actions to the user's own event.
   */
  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    return Maybe.fromAction(
        () -> {
          if (callbackContext.invocationContext().callbackContextData().remove(pendingDeltaKey)
              instanceof PendingDelta pending) {
            callbackContext.eventActions().artifactDelta().putAll(pending.versions());
          }
        });
  }

  /** Returns the parts that take {@code part}'s place, recording any version saved. */
  private Single<ImmutableList<Part>> newParts(
      InvocationContext invocationContext,
      BaseArtifactService artifactService,
      Part part,
      int index,
      Map<String, Integer> savedVersions) {
    Optional<Blob> inlineData = part.inlineData();
    if (inlineData.isEmpty()) {
      return Single.just(ImmutableList.of(part));
    }
    String fileName =
        inlineData
            .flatMap(Blob::displayName)
            .filter(displayName -> !displayName.isEmpty())
            .orElseGet(
                () -> GENERATED_FILE_NAME.formatted(invocationContext.invocationId(), index));
    int size = inlineData.flatMap(Blob::data).map(data -> data.length).orElse(0);
    if (size > MAX_FILE_SIZE_BYTES) {
      logger.warn(
          "The file in part {} is {} bytes, over the limit; it was not saved.", index, size);
      return Single.just(
          ImmutableList.of(
              Part.fromText(UPLOAD_ERROR.formatted(fileName, megabytes(size), MAX_FILE_SIZE_MB))));
    }
    SessionKey sessionKey = invocationContext.session().sessionKey();
    return Single.defer(() -> save(artifactService, sessionKey, fileName, part))
        .map(
            saved -> {
              saved.version().ifPresent(version -> savedVersions.put(fileName, version));
              ImmutableList.Builder<Part> replacement = ImmutableList.builder();
              replacement.add(Part.fromText(PLACEHOLDER.formatted(fileName)));
              saved.reference().ifPresent(replacement::add);
              return replacement.build();
            })
        .onErrorReturn(
            error -> {
              // The exception message can contain the user-chosen file name, so log only the type.
              logger.error(
                  "Failed to save the file in part {} ({}); keeping the original part.",
                  index,
                  error.getClass().getSimpleName());
              return ImmutableList.of(part);
            });
  }

  /**
   * Saves {@code part} under {@code fileName}. When {@code attachFileReference} is enabled, this
   * uses {@link BaseArtifactService#saveAndReloadArtifact} to get the stored file's URI and then
   * reads back the latest version, since the service cannot look up the URI of a given version.
   */
  private Single<SavedFile> save(
      BaseArtifactService artifactService, SessionKey sessionKey, String fileName, Part part) {
    if (!attachFileReference) {
      return artifactService
          .saveArtifact(sessionKey, fileName, part)
          .map(version -> new SavedFile(Optional.of(version), Optional.empty()));
    }
    return artifactService
        .saveAndReloadArtifact(sessionKey, fileName, part)
        .flatMap(
            stored -> {
              Optional<FileData> storedFile = stored.fileData();
              Optional<Part> reference =
                  storedFile
                      .flatMap(FileData::fileUri)
                      .filter(SaveFilesAsArtifactsPlugin::isModelAccessibleUri)
                      .map(
                          uri -> {
                            FileData.Builder fileData =
                                FileData.builder().displayName(fileName).fileUri(uri);
                            part.inlineData()
                                .flatMap(Blob::mimeType)
                                .filter(type -> !type.isEmpty())
                                .or(() -> storedFile.flatMap(FileData::mimeType))
                                .ifPresent(fileData::mimeType);
                            return Part.builder().fileData(fileData.build()).build();
                          });
              // The file is saved by now, so failing to read its version only leaves it out of
              // the delta.
              return Single.defer(() -> artifactService.listVersions(sessionKey, fileName))
                  .map(versions -> versions.stream().max(naturalOrder()))
                  .doOnSuccess(
                      version -> {
                        if (version.isEmpty()) {
                          logger.warn("Saved a file but the service listed no versions of it.");
                        }
                      })
                  .onErrorReturn(
                      error -> {
                        logger.warn(
                            "Saved a file but could not list its versions ({}).",
                            error.getClass().getSimpleName());
                        return Optional.empty();
                      })
                  .map(version -> new SavedFile(version, reference));
            });
  }

  private static boolean isModelAccessibleUri(String uri) {
    int colon = uri.indexOf(':');
    return colon > 0
        && MODEL_ACCESSIBLE_URI_SCHEMES.contains(Ascii.toLowerCase(uri.substring(0, colon)));
  }

  /**
   * Formats {@code bytes} in megabytes to two decimal places, rounding half to even like Python.
   */
  private static String megabytes(int bytes) {
    return BigDecimal.valueOf(bytes)
        .divide(BigDecimal.valueOf(BYTES_PER_MB))
        .setScale(2, RoundingMode.HALF_EVEN)
        .toPlainString();
  }

  private record SavedFile(Optional<Integer> version, Optional<Part> reference) {}

  private record PendingDelta(ImmutableMap<String, Integer> versions) {}
}
