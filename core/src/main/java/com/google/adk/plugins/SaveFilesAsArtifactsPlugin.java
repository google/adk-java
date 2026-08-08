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
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plugin that saves files embedded in user messages as artifacts.
 *
 * <p>This allows users to upload files in the chat experience and have those files available to the
 * agent within the current session. Each {@code inlineData} part of the incoming user message is
 * written to the configured {@link com.google.adk.artifacts.BaseArtifactService} and replaced, in
 * the message that reaches the model, by a short text placeholder naming the artifact. The bytes
 * themselves are therefore stored once and not resent on every turn.
 *
 * <p>The artifact name is taken from {@link Blob#displayName()} when present, so an uploaded {@code
 * report.pdf} is stored under that name. When the blob carries no display name, a name is generated
 * from the invocation id and the part index. Artifacts with the same name overwrite each other, and
 * a name prefixed with {@code user:} is scoped to the user rather than the session.
 *
 * <p>Add the {@code load_artifacts} tool to the agent, or load the artifacts from your own tool, to
 * let the model read the stored bytes back.
 *
 * <p>Register it on the runner:
 *
 * <pre>{@code
 * Runner runner =
 *     Runner.builder()
 *         .agent(agent)
 *         .appName("my-app")
 *         .artifactService(new InMemoryArtifactService())
 *         .sessionService(new InMemorySessionService())
 *         .plugins(new SaveFilesAsArtifactsPlugin())
 *         .build();
 * }</pre>
 *
 * <p>The plugin is a no-op when no artifact service is configured on the runner.
 */
public class SaveFilesAsArtifactsPlugin extends BasePlugin {

  /** Name used when the plugin is constructed without an explicit one. Matches adk-python's. */
  public static final String DEFAULT_NAME = "save_files_as_artifacts_plugin";

  private static final Logger logger = LoggerFactory.getLogger(SaveFilesAsArtifactsPlugin.class);

  private static final String GENERATED_FILE_NAME = "artifact_%s_%d";
  private static final String PLACEHOLDER_TEXT = "[Uploaded Artifact: \"%s\"]";

  public SaveFilesAsArtifactsPlugin() {
    this(DEFAULT_NAME);
  }

  public SaveFilesAsArtifactsPlugin(String name) {
    super(name);
  }

  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    if (invocationContext.artifactService() == null) {
      logger.warn("No artifact service is configured; plugin '{}' is disabled.", getName());
      return Maybe.empty();
    }
    ImmutableList<Part> parts =
        ImmutableList.copyOf(userMessage.parts().orElse(ImmutableList.of()));
    if (parts.stream().noneMatch(SaveFilesAsArtifactsPlugin::hasInlineData)) {
      return Maybe.empty();
    }
    return Flowable.range(0, parts.size())
        .concatMapSingle(index -> savePart(invocationContext, parts.get(index), index))
        .collect(toImmutableList())
        .map(results -> rebuildMessage(invocationContext, userMessage, results))
        .filter(Optional::isPresent)
        .map(Optional::get);
  }

  /**
   * Records the artifact versions stashed by {@link #onUserMessageCallback} on the first event
   * actions of the invocation. {@code onUserMessageCallback} runs before any {@link
   * com.google.adk.events.EventActions} exists, so the versions cannot be reported from there.
   *
   * <p>The reporting is a side effect and the return is always empty, deliberately: {@code
   * PluginManager} stops at the first plugin that returns a value, so returning content here would
   * both skip every later plugin's callback and halt the agent.
   */
  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    PendingArtifactDelta.drain(callbackContext, getName())
        .forEach(callbackContext.eventActions().artifactDelta()::put);
    return Maybe.empty();
  }

  /**
   * Discards a stash that {@link #beforeAgentCallback} never got to report, which happens when a
   * {@code beforeRunCallback} on another plugin halts the invocation before any agent runs.
   *
   * <p>This does not cover an invocation that <em>fails</em> in that same window: {@code
   * afterRunCallback} only runs after successful completion, and adk-java has no agent/run error
   * callback to clean up from — see <a
   * href="https://github.com/google/adk-java/issues/1316">#1316</a>. A failed upload therefore
   * leaves one inert entry in the in-memory session state; it is {@code temp:}-prefixed so it never
   * reaches persisted state, and invocation-scoped so no later invocation can read it.
   */
  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    PendingArtifactDelta.clear(invocationContext, getName());
    return Completable.complete();
  }

  /** Saves one part if it carries inline data, leaving every other part untouched. */
  private Single<SavedPart> savePart(InvocationContext invocationContext, Part part, int index) {
    if (!hasInlineData(part)) {
      return Single.just(SavedPart.unchanged(part));
    }
    String fileName = resolveFileName(invocationContext, part, index);
    return invocationContext
        .artifactService()
        .saveArtifact(
            invocationContext.appName(),
            invocationContext.userId(),
            invocationContext.session().id(),
            fileName,
            part)
        .map(version -> SavedPart.saved(placeholderFor(fileName), fileName, version))
        .onErrorReturn(error -> keepOriginal(part, fileName, error));
  }

  /** A failed save must not fail the invocation: the original part is passed through unchanged. */
  private SavedPart keepOriginal(Part part, String fileName, Throwable error) {
    logger.error("Failed to save artifact '{}'; keeping the original part.", fileName, error);
    return SavedPart.unchanged(part);
  }

  /** Returns the rewritten message, or empty when no part was actually offloaded. */
  private Optional<Content> rebuildMessage(
      InvocationContext invocationContext, Content userMessage, List<SavedPart> results) {
    ImmutableMap<String, Integer> delta = toArtifactDelta(results);
    if (delta.isEmpty()) {
      return Optional.empty();
    }
    PendingArtifactDelta.stash(invocationContext, getName(), delta);
    ImmutableList<Part> parts = results.stream().map(SavedPart::part).collect(toImmutableList());
    return Optional.of(userMessage.toBuilder().parts(parts).build());
  }

  private static ImmutableMap<String, Integer> toArtifactDelta(List<SavedPart> results) {
    return results.stream()
        .filter(SavedPart::isSaved)
        .collect(
            toImmutableMap(SavedPart::savedFileName, SavedPart::version, (older, newer) -> newer));
  }

  private static String resolveFileName(InvocationContext invocationContext, Part part, int index) {
    return part.inlineData()
        .flatMap(Blob::displayName)
        .filter(displayName -> !displayName.isEmpty())
        .orElseGet(() -> GENERATED_FILE_NAME.formatted(invocationContext.invocationId(), index));
  }

  private static Part placeholderFor(String fileName) {
    return Part.fromText(PLACEHOLDER_TEXT.formatted(fileName));
  }

  private static boolean hasInlineData(Part part) {
    return part.inlineData().isPresent();
  }

  /** One input part after the offload attempt: either untouched, or replaced by a placeholder. */
  private record SavedPart(Part part, Optional<String> fileName, int version) {

    static SavedPart unchanged(Part part) {
      return new SavedPart(part, Optional.empty(), 0);
    }

    static SavedPart saved(Part placeholder, String fileName, int version) {
      return new SavedPart(placeholder, Optional.of(fileName), version);
    }

    boolean isSaved() {
      return fileName.isPresent();
    }

    String savedFileName() {
      return fileName.orElseThrow();
    }
  }
}
