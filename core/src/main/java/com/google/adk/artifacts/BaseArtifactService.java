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
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Base interface for artifact services. */
public interface BaseArtifactService {

  Logger logger = LoggerFactory.getLogger(BaseArtifactService.class);

  /**
   * Saves an artifact.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the filename
   * @param artifact the artifact
   * @return the revision ID (version) of the saved artifact.
   * @deprecated Use {@link #saveArtifact(SessionKey, String, Part)} instead.
   */
  @Deprecated
  Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact);

  /**
   * Saves an artifact.
   *
   * <p><b>Deprecation warning:</b> The default implementation will be removed and all implementing
   * classes must provide their own implementation.
   */
  default Single<Integer> saveArtifact(SessionKey sessionKey, String filename, Part artifact) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
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
   * @deprecated Use {@link #saveAndReloadArtifact(SessionKey, String, Part)} instead.
   */
  @Deprecated
  default Single<Part> saveAndReloadArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
    return saveArtifact(appName, userId, sessionId, filename, artifact)
        .flatMap(version -> loadArtifact(appName, userId, sessionId, filename, version).toSingle());
  }

  /**
   * Saves an artifact and returns it with fileData if available.
   *
   * <p><b>Deprecation warning:</b> The default implementation will be removed and all implementing
   * classes must provide their own implementation.
   */
  default Single<Part> saveAndReloadArtifact(
      SessionKey sessionKey, String filename, Part artifact) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
    return saveAndReloadArtifact(
        sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename, artifact);
  }

  /**
   * Loads the latest version of an artifact from the service.
   *
   * @deprecated Use {@link #loadArtifact(SessionKey, String)} instead.
   */
  @Deprecated
  default Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
    return loadArtifact(appName, userId, sessionId, filename, /* version= */ (Integer) null);
  }

  /**
   * Loads the latest version of an artifact from the service.
   *
   * <p><b>Deprecation warning:</b> The default implementation will be removed and all implementing
   * classes must provide their own implementation.
   */
  default Maybe<Part> loadArtifact(SessionKey sessionKey, String filename) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
    return loadArtifact(sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename);
  }

  /**
   * Loads a specific version of an artifact from the service.
   *
   * @deprecated Use {@link #loadArtifact(SessionKey, String, int)} instead.
   */
  @Deprecated
  default Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, int version) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
    return loadArtifact(appName, userId, sessionId, filename, Integer.valueOf(version));
  }

  /**
   * <b>Deprecation warning:</b> The default implementation will be removed and all implementing
   * classes must provide their own implementation.
   */
  default Maybe<Part> loadArtifact(SessionKey sessionKey, String filename, int version) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
    return loadArtifact(
        sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename, version);
  }

  /**
   * @deprecated Use {@link #loadArtifact(SessionKey, String, Integer)} instead.
   */
  @Deprecated
  Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, @Nullable Integer version);

  /**
   * <b>Deprecation warning:</b> The default implementation will be removed and all implementing
   * classes must provide their own implementation.
   */
  default Maybe<Part> loadArtifact(
      SessionKey sessionKey, String filename, @Nullable Integer version) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
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
   * @deprecated Use {@link #listArtifactKeys(SessionKey)} instead.
   */
  @Deprecated
  Single<ListArtifactsResponse> listArtifactKeys(String appName, String userId, String sessionId);

  /**
   * <b>Deprecation warning:</b> The default implementation will be removed and all implementing
   * classes must provide their own implementation.
   */
  default Single<ListArtifactsResponse> listArtifactKeys(SessionKey sessionKey) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
    return listArtifactKeys(sessionKey.appName(), sessionKey.userId(), sessionKey.id());
  }

  /**
   * Deletes an artifact.
   *
   * @param appName the app name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the filename
   * @deprecated Use {@link #deleteArtifact(SessionKey, String)} instead.
   */
  @Deprecated
  Completable deleteArtifact(String appName, String userId, String sessionId, String filename);

  /**
   * <b>Deprecation warning:</b> The default implementation will be removed and all implementing
   * classes must provide their own implementation.
   */
  default Completable deleteArtifact(SessionKey sessionKey, String filename) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
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
   * @deprecated Use {@link #listVersions(SessionKey, String)} instead.
   */
  @Deprecated
  Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename);

  /**
   * <b>Deprecation warning:</b> The default implementation will be removed and all implementing
   * classes must provide their own implementation.
   */
  default Single<ImmutableList<Integer>> listVersions(SessionKey sessionKey, String filename) {
    logger.warn(
        "This method relies on the default implementation, which will be removed in the future.");
    return listVersions(sessionKey.appName(), sessionKey.userId(), sessionKey.id(), filename);
  }
}
