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

package com.google.adk.web.controller;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Controller handling artifact-related API endpoints. */
@RestController
public class ArtifactController {

  private static final Logger log = LoggerFactory.getLogger(ArtifactController.class);

  private final BaseArtifactService artifactService;

  @Autowired
  public ArtifactController(BaseArtifactService artifactService) {
    this.artifactService = artifactService;
  }

  /**
   * Loads the latest or a specific version of an artifact associated with a session.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param artifactName The name of the artifact.
   * @param version Optional specific version number. If null, loads the latest.
   * @return The artifact content as a Part object.
   * @throws ResponseStatusException if the artifact is not found (NOT_FOUND).
   */
  @GetMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}")
  public Part loadArtifact(
      @PathVariable String appName,
      @PathVariable String userId,
      @PathVariable String sessionId,
      @PathVariable String artifactName,
      @RequestParam(required = false) Integer version) {
    String versionStr = (version == null) ? "latest" : String.valueOf(version);
    log.info(
        "Request received to load artifact: app={}, user={}, session={}, artifact={}, version={}",
        appName,
        userId,
        sessionId,
        artifactName,
        versionStr);

    Maybe<Part> artifactMaybe =
        artifactService.loadArtifact(
            appName, userId, sessionId, artifactName, Optional.ofNullable(version));

    Part artifact = artifactMaybe.blockingGet();

    if (artifact == null) {
      log.warn(
          "Artifact not found: app={}, user={}, session={}, artifact={}, version={}",
          appName,
          userId,
          sessionId,
          artifactName,
          versionStr);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact not found");
    }
    log.debug("Artifact {} version {} loaded successfully.", artifactName, versionStr);
    return artifact;
  }

  /**
   * Loads a specific version of an artifact.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param artifactName The name of the artifact.
   * @param versionId The specific version number.
   * @return The artifact content as a Part object.
   * @throws ResponseStatusException if the artifact version is not found (NOT_FOUND).
   */
  @GetMapping(
      "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}/versions/{versionId}")
  public Part loadArtifactVersion(
      @PathVariable String appName,
      @PathVariable String userId,
      @PathVariable String sessionId,
      @PathVariable String artifactName,
      @PathVariable int versionId) {
    log.info(
        "Request received to load artifact version: app={}, user={}, session={}, artifact={},"
            + " version={}",
        appName,
        userId,
        sessionId,
        artifactName,
        versionId);

    Maybe<Part> artifactMaybe =
        artifactService.loadArtifact(
            appName, userId, sessionId, artifactName, Optional.of(versionId));

    Part artifact = artifactMaybe.blockingGet();

    if (artifact == null) {
      log.warn(
          "Artifact version not found: app={}, user={}, session={}, artifact={}, version={}",
          appName,
          userId,
          sessionId,
          artifactName,
          versionId);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Artifact version not found");
    }
    log.debug("Artifact {} version {} loaded successfully.", artifactName, versionId);
    return artifact;
  }

  /**
   * Lists the names of all artifacts associated with a session.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @return A list of artifact names.
   */
  @GetMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts")
  public List<String> listArtifactNames(
      @PathVariable String appName, @PathVariable String userId, @PathVariable String sessionId) {
    log.info(
        "Request received to list artifact names for app={}, user={}, session={}",
        appName,
        userId,
        sessionId);

    Single<ListArtifactsResponse> responseSingle =
        artifactService.listArtifactKeys(appName, userId, sessionId);

    ListArtifactsResponse response = responseSingle.blockingGet();
    List<String> filenames =
        (response != null && response.filenames() != null)
            ? response.filenames()
            : Collections.emptyList();
    log.info("Found {} artifact names for session {}", filenames.size(), sessionId);
    return filenames;
  }

  /**
   * Lists the available versions for a specific artifact.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param artifactName The name of the artifact.
   * @return A list of version numbers (integers).
   */
  @GetMapping(
      "/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}/versions")
  public List<Integer> listArtifactVersions(
      @PathVariable String appName,
      @PathVariable String userId,
      @PathVariable String sessionId,
      @PathVariable String artifactName) {
    log.info(
        "Request received to list versions for artifact: app={}, user={}, session={},"
            + " artifact={}",
        appName,
        userId,
        sessionId,
        artifactName);

    Single<ImmutableList<Integer>> versionsSingle =
        artifactService.listVersions(appName, userId, sessionId, artifactName);
    ImmutableList<Integer> versions = versionsSingle.blockingGet();
    log.info(
        "Found {} versions for artifact {}", versions != null ? versions.size() : 0, artifactName);
    return versions != null ? versions : Collections.emptyList();
  }

  /**
   * Deletes an artifact and all its versions.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param artifactName The name of the artifact to delete.
   * @return A ResponseEntity with status NO_CONTENT on success.
   * @throws ResponseStatusException if deletion fails (INTERNAL_SERVER_ERROR).
   */
  @DeleteMapping("/apps/{appName}/users/{userId}/sessions/{sessionId}/artifacts/{artifactName}")
  public ResponseEntity<Void> deleteArtifact(
      @PathVariable String appName,
      @PathVariable String userId,
      @PathVariable String sessionId,
      @PathVariable String artifactName) {
    log.info(
        "Request received to delete artifact: app={}, user={}, session={}, artifact={}",
        appName,
        userId,
        sessionId,
        artifactName);

    try {

      artifactService.deleteArtifact(appName, userId, sessionId, artifactName);
      log.info("Artifact deleted successfully: {}", artifactName);
      return ResponseEntity.noContent().build();
    } catch (Exception e) {
      log.error("Error deleting artifact {}", artifactName, e);

      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Error deleting artifact", e);
    }
  }
}
