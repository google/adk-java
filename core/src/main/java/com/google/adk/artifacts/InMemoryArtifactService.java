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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/** An in-memory implementation of the {@link BaseArtifactService}. */
public final class InMemoryArtifactService implements BaseArtifactService {
  // Mirrors the "user" path segment GcsArtifactService.getBlobPrefix uses for user-namespaced
  // filenames: a stand-in session key so those artifacts are stored and looked up independently
  // of whatever sessionId happened to be current when they were saved.
  private static final String USER_NAMESPACE_SESSION_KEY = "user";

  private final Map<String, Map<String, Map<String, Map<String, List<Part>>>>> artifacts;

  public InMemoryArtifactService() {
    this.artifacts = new HashMap<>();
  }

  /**
   * Checks if a filename uses the user namespace.
   *
   * @param filename Filename to check.
   * @return true if prefixed with "user:", false otherwise.
   */
  private boolean fileHasUserNamespace(String filename) {
    return filename != null && filename.startsWith("user:");
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
        getArtifactsMap(appName, userId, sessionId, filename)
            .computeIfAbsent(filename, unused -> new ArrayList<>());
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
        getArtifactsMap(appName, userId, sessionId, filename)
            .getOrDefault(filename, ImmutableList.of());

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
   * Lists filenames of stored artifacts for the session.
   *
   * @return Single with list of artifact filenames.
   */
  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    Set<String> filenames = new HashSet<>();
    // Session-scoped filenames, keyed by the actual sessionId.
    filenames.addAll(getArtifactsMapForSessionKey(appName, userId, sessionId).keySet());
    // User-namespaced filenames live under the shared USER_NAMESPACE_SESSION_KEY bucket
    // regardless of sessionId, mirroring GcsArtifactService's merged session+user listing.
    filenames.addAll(
        getArtifactsMapForSessionKey(appName, userId, USER_NAMESPACE_SESSION_KEY).keySet());
    return Single.just(
        ListArtifactsResponse.builder().filenames(ImmutableList.sortedCopyOf(filenames)).build());
  }

  /**
   * Deletes all versions of the given artifact.
   *
   * @return Completable indicating completion.
   */
  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    getArtifactsMap(appName, userId, sessionId, filename).remove(filename);
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
        getArtifactsMap(appName, userId, sessionId, filename)
            .getOrDefault(filename, ImmutableList.of())
            .size();
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

  /**
   * Resolves the artifacts map for a filename, routing user-namespaced filenames to the shared
   * {@link #USER_NAMESPACE_SESSION_KEY} bucket instead of the given session, so they are visible
   * across all of a user's sessions exactly as {@link GcsArtifactService} stores them.
   */
  private Map<String, List<Part>> getArtifactsMap(
      String appName, String userId, String sessionId, String filename) {
    String sessionKey = fileHasUserNamespace(filename) ? USER_NAMESPACE_SESSION_KEY : sessionId;
    return getArtifactsMapForSessionKey(appName, userId, sessionKey);
  }

  private Map<String, List<Part>> getArtifactsMapForSessionKey(
      String appName, String userId, String sessionKey) {
    return artifacts
        .computeIfAbsent(appName, unused -> new HashMap<>())
        .computeIfAbsent(userId, unused -> new HashMap<>())
        .computeIfAbsent(sessionKey, unused -> new HashMap<>());
  }
}
