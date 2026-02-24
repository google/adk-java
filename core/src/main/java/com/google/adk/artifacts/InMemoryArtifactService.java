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
import java.util.Optional;
import java.util.stream.IntStream;

/** An in-memory implementation of the {@link BaseArtifactService}. */
public final class InMemoryArtifactService implements BaseArtifactService {
  private final Map<String, Map<String, Map<String, Map<String, List<Part>>>>> artifacts;

  public InMemoryArtifactService() {
    this.artifacts = new HashMap<>();
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
        getArtifactsMap(appName, userId, sessionId)
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
      String appName, String userId, String sessionId, String filename, Optional<Integer> version) {
    List<Part> versions =
        getArtifactsMap(appName, userId, sessionId)
            .computeIfAbsent(filename, unused -> new ArrayList<>());

    if (versions.isEmpty()) {
      return Maybe.empty();
    }
    if (version.isPresent()) {
      int v = version.get();
      if (v >= 0 && v < versions.size()) {
        return Maybe.just(versions.get(v));
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
    return Single.just(
        ListArtifactsResponse.builder()
            .filenames(ImmutableList.copyOf(getArtifactsMap(appName, userId, sessionId).keySet()))
            .build());
  }

  /**
   * Deletes all versions of the given artifact.
   *
   * @return Completable indicating completion.
   */
  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    getArtifactsMap(appName, userId, sessionId).remove(filename);
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
        getArtifactsMap(appName, userId, sessionId)
            .computeIfAbsent(filename, unused -> new ArrayList<>())
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

  private Map<String, List<Part>> getArtifactsMap(String appName, String userId, String sessionId) {
    return artifacts
        .computeIfAbsent(appName, unused -> new HashMap<>())
        .computeIfAbsent(userId, unused -> new HashMap<>())
        .computeIfAbsent(sessionId, unused -> new HashMap<>());
  }
}
