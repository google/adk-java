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

import static java.util.Collections.max;

import com.google.auto.value.AutoValue;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageException;
import com.google.common.base.Splitter;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.genai.types.FileData;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** An artifact service implementation using Google Cloud Storage (GCS). */
public final class GcsArtifactService implements BaseArtifactService {
  private final String bucketName;
  private final Storage storageClient;

  /**
   * Initializes the GcsArtifactService.
   *
   * @param bucketName The name of the GCS bucket to use.
   * @param storageClient The GCS storage client instance.
   */
  public GcsArtifactService(String bucketName, Storage storageClient) {
    this.bucketName = bucketName;
    this.storageClient = storageClient;
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
   * Constructs the blob prefix for an artifact (excluding version).
   *
   * @param appName Application name.
   * @param userId User ID.
   * @param sessionId Session ID.
   * @param filename Artifact filename.
   * @return prefix string for blob location.
   */
  private String getBlobPrefix(String appName, String userId, String sessionId, String filename) {
    if (fileHasUserNamespace(filename)) {
      return String.format("%s/%s/user/%s/", appName, userId, filename);
    } else {
      return String.format("%s/%s/%s/%s/", appName, userId, sessionId, filename);
    }
  }

  /**
   * Constructs the full blob name for an artifact, including version.
   *
   * @param appName Application name.
   * @param userId User ID.
   * @param sessionId Session ID.
   * @param filename Artifact filename.
   * @param version Artifact version.
   * @return full blob name.
   */
  private String getBlobName(
      String appName, String userId, String sessionId, String filename, int version) {
    return getBlobPrefix(appName, userId, sessionId, filename) + version;
  }

  /**
   * Saves an artifact to GCS and assigns a new version.
   *
   * @param appName Application name.
   * @param userId User ID.
   * @param sessionId Session ID.
   * @param filename Artifact filename.
   * @param artifact Artifact content to save.
   * @return Single with assigned version number.
   */
  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    return saveArtifactAndReturnBlob(appName, userId, sessionId, filename, artifact)
        .map(SaveResult::version);
  }

  /**
   * Loads an artifact from GCS.
   *
   * @param appName Application name.
   * @param userId User ID.
   * @param sessionId Session ID.
   * @param filename Artifact filename.
   * @param version Optional version to load. Loads latest if empty.
   * @return Maybe with loaded artifact, or empty if not found.
   */
  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, Optional<Integer> version) {
    return version
        .map(Maybe::just)
        .orElseGet(
            () ->
                listVersions(appName, userId, sessionId, filename)
                    .flatMapMaybe(
                        versions -> versions.isEmpty() ? Maybe.empty() : Maybe.just(max(versions))))
        .flatMap(
            versionToLoad ->
                Maybe.fromCallable(
                    () -> {
                      String blobName =
                          getBlobName(appName, userId, sessionId, filename, versionToLoad);
                      BlobId blobId = BlobId.of(bucketName, blobName);

                      try {
                        Blob blob = storageClient.get(blobId);
                        if (blob == null || !blob.exists()) {
                          return null;
                        }
                        byte[] data = blob.getContent();
                        String mimeType = blob.getContentType();
                        return Part.fromBytes(data, mimeType);
                      } catch (StorageException e) {
                        return null;
                      }
                    }));
  }

  /**
   * Lists artifact filenames for a user and session.
   *
   * @param appName Application name.
   * @param userId User ID.
   * @param sessionId Session ID.
   * @return Single with sorted list of artifact filenames.
   */
  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    return Single.fromCallable(
        () -> {
          Set<String> filenames = new HashSet<>();

          // List session-specific files
          String sessionPrefix = String.format("%s/%s/%s/", appName, userId, sessionId);
          try {
            for (Blob blob :
                storageClient.list(bucketName, BlobListOption.prefix(sessionPrefix)).iterateAll()) {
              List<String> parts = Splitter.on('/').splitToList(blob.getName());
              filenames.add(parts.get(3)); // appName/userId/sessionId/filename/version
            }
          } catch (StorageException e) {
            throw new VerifyException("Failed to list session artifacts from GCS", e);
          }

          // List user-namespace files
          String userPrefix = String.format("%s/%s/user/", appName, userId);
          try {
            for (Blob blob :
                storageClient.list(bucketName, BlobListOption.prefix(userPrefix)).iterateAll()) {
              List<String> parts = Splitter.on('/').splitToList(blob.getName());
              filenames.add(parts.get(3)); // appName/userId/user/filename/version
            }
          } catch (StorageException e) {
            throw new VerifyException("Failed to list user artifacts from GCS", e);
          }

          return ListArtifactsResponse.builder()
              .filenames(ImmutableList.sortedCopyOf(filenames))
              .build();
        });
  }

  /**
   * Deletes all versions of the specified artifact from GCS.
   *
   * @param appName Application name.
   * @param userId User ID.
   * @param sessionId Session ID.
   * @param filename Artifact filename.
   * @return Completable indicating operation completion.
   */
  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    return listVersions(appName, userId, sessionId, filename)
        .flatMapCompletable(
            versions -> {
              if (versions.isEmpty()) {
                return Completable.complete();
              }
              ImmutableList<BlobId> blobIdsToDelete =
                  versions.stream()
                      .map(
                          version ->
                              BlobId.of(
                                  bucketName,
                                  getBlobName(appName, userId, sessionId, filename, version)))
                      .collect(ImmutableList.toImmutableList());

              return Completable.fromAction(
                  () -> {
                    try {
                      var unused = storageClient.delete(blobIdsToDelete);
                    } catch (StorageException e) {
                      throw new VerifyException("Failed to delete artifact versions from GCS", e);
                    }
                  });
            });
  }

  /**
   * Lists all available versions for a given artifact.
   *
   * @param appName Application name.
   * @param userId User ID.
   * @param sessionId Session ID.
   * @param filename Artifact filename.
   * @return Single with sorted list of version numbers.
   */
  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    return Single.fromCallable(
        () -> {
          String prefix = getBlobPrefix(appName, userId, sessionId, filename);
          try {
            return Streams.stream(
                    storageClient.list(bucketName, BlobListOption.prefix(prefix)).iterateAll())
                .map(Blob::getName)
                .map(
                    name -> {
                      int versionDelimiterIndex = name.lastIndexOf('/');
                      return versionDelimiterIndex != -1
                              && versionDelimiterIndex < name.length() - 1
                          ? Optional.of(name.substring(versionDelimiterIndex + 1))
                          : Optional.<String>empty();
                    })
                .flatMap(Optional::stream)
                .map(Integer::parseInt)
                .sorted()
                .collect(ImmutableList.toImmutableList());
          } catch (StorageException e) {
            return ImmutableList.of();
          }
        });
  }

  @Override
  public Single<Part> saveAndReloadArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    return saveArtifactAndReturnBlob(appName, userId, sessionId, filename, artifact)
        .flatMap(
            blob -> {
              Blob savedBlob = blob.blob();
              String resultMimeType =
                  Optional.ofNullable(savedBlob.getContentType())
                      .or(
                          () ->
                              artifact.inlineData().flatMap(com.google.genai.types.Blob::mimeType))
                      .orElse("application/octet-stream");
              return Single.just(
                  Part.builder()
                      .fileData(
                          FileData.builder()
                              .fileUri("gs://" + savedBlob.getBucket() + "/" + savedBlob.getName())
                              .mimeType(resultMimeType)
                              .build())
                      .build());
            });
  }

  @AutoValue
  abstract static class SaveResult {
    static SaveResult create(Blob blob, int version) {
      return new AutoValue_GcsArtifactService_SaveResult(blob, version);
    }

    abstract Blob blob();

    abstract int version();
  }

  private Single<SaveResult> saveArtifactAndReturnBlob(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    return listVersions(appName, userId, sessionId, filename)
        .map(versions -> versions.isEmpty() ? 0 : max(versions) + 1)
        .flatMap(
            nextVersion ->
                Single.fromCallable(
                    () -> {
                      if (artifact.inlineData().isEmpty()) {
                        throw new IllegalArgumentException(
                            "Saveable artifact must have inline data.");
                      }

                      String blobName =
                          getBlobName(appName, userId, sessionId, filename, nextVersion);
                      BlobId blobId = BlobId.of(bucketName, blobName);

                      BlobInfo blobInfo =
                          BlobInfo.newBuilder(blobId)
                              .setContentType(artifact.inlineData().get().mimeType().orElse(null))
                              .build();

                      try {
                        byte[] dataToSave =
                            artifact
                                .inlineData()
                                .get()
                                .data()
                                .orElseThrow(
                                    () ->
                                        new IllegalArgumentException(
                                            "Saveable artifact data must be non-empty."));
                        Blob blob = storageClient.create(blobInfo, dataToSave);
                        return SaveResult.create(blob, nextVersion);
                      } catch (StorageException e) {
                        throw new VerifyException("Failed to save artifact to GCS", e);
                      }
                    }));
  }
}
