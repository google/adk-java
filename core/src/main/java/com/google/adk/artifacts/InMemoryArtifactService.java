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
   * Creates a new instance of {@link InMemoryArtifactService} with an empty artifact store.
   * 
   * @return A new instance of {@link InMemoryArtifactService}.
   */
  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    List<Part> versions =
        artifacts
            .computeIfAbsent(appName, k -> new HashMap<>())
            .computeIfAbsent(userId, k -> new HashMap<>())
            .computeIfAbsent(sessionId, k -> new HashMap<>())
            .computeIfAbsent(filename, k -> new ArrayList<>());
    versions.add(artifact);
    return Single.just(versions.size() - 1);
  }

  /**
   * Loads an artifact from the in-memory store.
   *
   * @param appName The name of the application.
   * @param userId The ID of the user.
   * @param sessionId The ID of the session.
   * @param filename The name of the file to load.
   * @param version Optional version number to load; if not provided, the latest version is returned.
   * @return A {@link Maybe} containing the requested artifact, or empty if not found.
   */
  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, Optional<Integer> version) {
    List<Part> versions =
        artifacts
            .getOrDefault(appName, new HashMap<>())
            .getOrDefault(userId, new HashMap<>())
            .getOrDefault(sessionId, new HashMap<>())
            .getOrDefault(filename, new ArrayList<>());

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
   * Lists the keys (filenames) of all artifacts stored for a given application, user, and session.
   *
   * @param appName The name of the application.
   * @param userId The ID of the user.
   * @param sessionId The ID of the session.
   * @return A {@link Single} containing a response with a list of artifact filenames.
   */
  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    return Single.just(
        ListArtifactsResponse.builder()
            .filenames(
                ImmutableList.copyOf(
                    artifacts
                        .getOrDefault(appName, new HashMap<>())
                        .getOrDefault(userId, new HashMap<>())
                        .getOrDefault(sessionId, new HashMap<>())
                        .keySet()))
            .build());
  }

  /**
   * Deletes an artifact from the in-memory store.
   *
   * @param appName The name of the application.
   * @param userId The ID of the user.
   * @param sessionId The ID of the session.
   * @param filename The name of the file to delete.
   * @return A {@link Completable} indicating the completion of the delete operation.
   */
  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    artifacts
        .getOrDefault(appName, new HashMap<>())
        .getOrDefault(userId, new HashMap<>())
        .getOrDefault(sessionId, new HashMap<>())
        .remove(filename);
    return Completable.complete();
  }

  /**
   * Lists all versions of a specific artifact file for a given application, user, and session.
   *
   * @param appName The name of the application.
   * @param userId The ID of the user.
   * @param sessionId The ID of the session.
   * @param filename The name of the file to list versions for.
   * @return A {@link Single} containing a list of version numbers for the specified artifact file.
   */
  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    int size =
        artifacts
            .getOrDefault(appName, new HashMap<>())
            .getOrDefault(userId, new HashMap<>())
            .getOrDefault(sessionId, new HashMap<>())
            .getOrDefault(filename, new ArrayList<>())
            .size();
    if (size == 0) {
      return Single.just(ImmutableList.of());
    }
    return Single.just(IntStream.range(0, size).boxed().collect(toImmutableList()));
  }
}
