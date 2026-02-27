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

import com.google.adk.sessions.SessionKey;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;

/** Base interface for artifact services. */
public interface BaseArtifactService {

  /**
   * Saves an artifact.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the filename
   * @param artifact the artifact
   * @return the revision ID (version) of the saved artifact.
   */
  Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact);

  default Single<Integer> saveArtifact(SessionKey sessionKey, String filename, Part artifact) {
    return saveArtifact(
        sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename, artifact);
  }

  /**
   * Saves an artifact and returns it with fileData if available.
   *
   * <p>Implementations should override this default method for efficiency, as the default performs
   * two I/O operations (save then load).
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the filename
   * @param artifact the artifact to save
   * @return the saved artifact with fileData if available.
   */
  default Single<Part> saveAndReloadArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    return saveArtifact(appName, userId, sessionId, filename, artifact)
        .flatMap(version -> loadArtifact(appName, userId, sessionId, filename, version).toSingle());
  }

  default Single<Part> saveAndReloadArtifact(
      SessionKey sessionKey, String filename, Part artifact) {
    return saveAndReloadArtifact(
        sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename, artifact);
  }

  /** Loads the latest version of an artifact from the service. */
  default Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename) {
    return loadArtifact(appName, userId, sessionId, filename, Optional.empty());
  }

  default Maybe<Part> loadArtifact(SessionKey sessionKey, String filename) {
    return loadArtifact(sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename);
  }

  /** Loads a specific version of an artifact from the service. */
  default Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, int version) {
    return loadArtifact(appName, userId, sessionId, filename, Optional.of(version));
  }

  default Maybe<Part> loadArtifact(SessionKey sessionKey, String filename, int version) {
    return loadArtifact(
        sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename, version);
  }

  /**
   * @deprecated Use {@link #loadArtifact(String, String, String, String)} or {@link
   *     #loadArtifact(String, String, String, String, int)} instead.
   */
  @Deprecated
  Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, Optional<Integer> version);

  default Maybe<Part> loadArtifact(
      SessionKey sessionKey, String filename, Optional<Integer> version) {
    return loadArtifact(
        sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename, version);
  }

  /**
   * Lists all the artifact filenames within a session.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @return the list artifact response containing filenames
   */
  Single<ListArtifactsResponse> listArtifactKeys(String appName, String userId, String sessionId);

  default Single<ListArtifactsResponse> listArtifactKeys(SessionKey sessionKey) {
    return listArtifactKeys(sessionKey.appName(), sessionKey.userId(), sessionKey.id());
  }

  /**
   * Deletes an artifact.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the filename
   */
  Completable deleteArtifact(String appName, String userId, String sessionId, String filename);

  default Completable deleteArtifact(SessionKey sessionKey, String filename) {
    return deleteArtifact(sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename);
  }

  /**
   * Lists all the versions (as revision IDs) of an artifact.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @return A list of integer version numbers.
   */
  Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename);

  default Single<ImmutableList<Integer>> listVersions(SessionKey sessionKey, String filename) {
    return listVersions(sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename);
  }
}
