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

package com.google.adk.artifacts;

import com.google.adk.sessions.SessionKey;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

/** Standard interface for artifact services. */
public interface ArtifactService {

  /**
   * Saves an artifact and returns it with fileData if available.
   *
   * @param sessionKey the session key
   * @param filename the filename
   * @param artifact the artifact to save
   * @return the saved artifact with fileData if available.
   */
  Single<Part> saveAndReloadArtifact(SessionKey sessionKey, String filename, Part artifact);

  /**
   * Loads the latest version of an artifact from the service.
   *
   * @param sessionKey the session key
   * @param filename the filename
   * @return the loaded artifact with fileData if available.
   */
  Maybe<Part> loadArtifact(SessionKey sessionKey, String filename);

  /**
   * Loads a specific version of an artifact from the service.
   *
   * @param sessionKey the session key
   * @param filename the filename
   * @param version the version
   * @return the loaded artifact with fileData if available.
   */
  Maybe<Part> loadArtifact(SessionKey sessionKey, String filename, int version);

  /**
   * Lists all the artifact filenames within a session.
   *
   * @param sessionKey the session key
   * @return the list artifact response containing filenames
   */
  Single<ListArtifactsResponse> listArtifactKeys(SessionKey sessionKey);

  /**
   * Deletes an artifact.
   *
   * @param sessionKey the session key
   * @param filename the filename
   */
  Completable deleteArtifact(SessionKey sessionKey, String filename);

  /**
   * Lists all the versions (as revision IDs) of an artifact.
   *
   * @param sessionKey the session key
   * @param filename the filename
   * @return A list of integer version numbers.
   */
  Single<ImmutableList<Integer>> listVersions(SessionKey sessionKey, String filename);
}
