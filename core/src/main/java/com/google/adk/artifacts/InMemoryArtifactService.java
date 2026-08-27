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

package com.google.adk.artifacts;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/** An in-memory implementation of the {@link BaseArtifactService}. */
public final class InMemoryArtifactService implements BaseArtifactService {
  private final Map<String, List<Part>> artifacts;

  public InMemoryArtifactService() {
    this.artifacts = new HashMap<>();
  }

  /**
   * Checks if a filename uses the user namespace.
   *
   * @param filename Filename to check.
   * @return true if prefixed with "user:", false otherwise.
   */
  private static boolean fileHasUserNamespace(String filename) {
    return filename != null && filename.startsWith("user:");
  }

  /**
   * Builds the storage key for an artifact.
   *
   * <p>A "user:"-prefixed filename is stored under a session-independent, user-scoped key so it is
   * visible from every session for that user, matching {@link GcsArtifactService} and adk-python's
   * {@code InMemoryArtifactService}. Any other filename is scoped to the given session, as before.
   */
  private static String artifactKey(
      String appName, String userId, String sessionId, String filename) {
    return fileHasUserNamespace(filename)
        ? String.format("%s/%s/user/%s", appName, userId, filename)
        : String.format("%s/%s/%s/%s", appName, userId, sessionId, filename);
  }

  /**
   * Saves an artifact in memory and assigns a new version.
   *
   * @return Single with assigned version number.
   */
  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    List<Part> versions =
        artifacts.computeIfAbsent(
            artifactKey(appName, userId, sessionId, filename), unused -> new ArrayList<>());
    versions.add(artifact);
    return Single.just(versions.size() - 1);
  }

  /**
   * Loads an artifact by version or latest.
   *
   * @return Maybe with the artifact, or empty if not found.
   */
  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, @Nullable Integer version) {
    List<Part> versions =
        artifacts.getOrDefault(artifactKey(appName, userId, sessionId, filename), List.of());

    if (versions.isEmpty()) {
      return Maybe.empty();
    }
    if (version != null) {
      if (version >= 0 && version < versions.size()) {
        return Maybe.just(versions.get(version));
      } else {
        return Maybe.empty();
      }
    } else {
      return Maybe.fromOptional(Streams.findLast(versions.stream()));
    }
  }

  /**
   * Lists filenames of stored artifacts for the session, including every "user:"-namespaced
   * artifact for the user regardless of which session saved it.
   *
   * @return Single with list of artifact filenames.
   */
  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    String sessionPrefix = String.format("%s/%s/%s/", appName, userId, sessionId);
    String userPrefix = String.format("%s/%s/user/", appName, userId);
    List<String> filenames = new ArrayList<>();
    for (String key : artifacts.keySet()) {
      if (key.startsWith(sessionPrefix)) {
        filenames.add(key.substring(sessionPrefix.length()));
      } else if (key.startsWith(userPrefix)) {
        filenames.add(key.substring(userPrefix.length()));
      }
    }
    return Single.just(ListArtifactsResponse.builder().filenames(filenames).build());
  }

  /**
   * Deletes all versions of the given artifact.
   *
   * @return Completable indicating completion.
   */
  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    artifacts.remove(artifactKey(appName, userId, sessionId, filename));
    return Completable.complete();
  }

  /**
   * Lists all versions of the specified artifact.
   *
   * @return Single with list of version numbers.
   */
  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    int size =
        artifacts.getOrDefault(artifactKey(appName, userId, sessionId, filename), List.of()).size();
    if (size == 0) {
      return Single.just(ImmutableList.of());
    }
    return Single.just(IntStream.range(0, size).boxed().collect(toImmutableList()));
  }

  @Override
  public Single<Part> saveAndReloadArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    return saveArtifact(appName, userId, sessionId, filename, artifact)
        .flatMap(version -> loadArtifact(appName, userId, sessionId, filename, version).toSingle());
  }
}
