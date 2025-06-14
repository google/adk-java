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

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageException;
import com.google.common.base.Splitter;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
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
   * Checks if the filename indicates a user namespace.
   * User namespace files are prefixed with "user:".
   *
   * @param filename The filename to check.
   * @return true if the file is in the user namespace, false otherwise.
   */
  private boolean fileHasUserNamespace(String filename) {
    return filename != null && filename.startsWith("user:");
  }

  /**
   * Constructs the blob prefix for a given artifact.
   * The prefix is the path through the filename but without the version.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param filename The filename of the artifact.
   * @return The blob prefix as a string, i.e. the path through the filename but without the version.
   */
  private String getBlobPrefix(String appName, String userId, String sessionId, String filename) {
    if (fileHasUserNamespace(filename)) {
      return String.format("%s/%s/user/%s/", appName, userId, filename);
    } else {
      return String.format("%s/%s/%s/%s/", appName, userId, sessionId, filename);
    }
  }

  /**
   * Constructs the full blob name for a given artifact, including the version.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param filename The filename of the artifact.
   * @param version The version number of the artifact.
   * @return The full blob name as a string.
   */
  private String getBlobName(
      String appName, String userId, String sessionId, String filename, int version) {
    return getBlobPrefix(appName, userId, sessionId, filename) + version;
  }

  /**
   * Saves an artifact to GCS, assigning it a version based on existing versions.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param filename The filename of the artifact.
   * @param artifact The Part representing the artifact to save.
   * @return A Single containing the version number assigned to the saved artifact.
   */
  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    ImmutableList<Integer> versions =
        listVersions(appName, userId, sessionId, filename).blockingGet();
    int nextVersion = versions.isEmpty() ? 0 : max(versions) + 1;

    String blobName = getBlobName(appName, userId, sessionId, filename, nextVersion);
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
                  () -> new IllegalArgumentException("Saveable artifact data must be non-empty."));
      storageClient.create(blobInfo, dataToSave);
      return Single.just(nextVersion);
    } catch (StorageException e) {
      throw new VerifyException("Failed to save artifact to GCS", e);
    }
  }

  /**
   * Loads an artifact from GCS, optionally specifying a version.
   * If no version is specified, the latest version is loaded.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param filename The filename of the artifact.
   * @param version Optional version number of the artifact to load.
   * @return A Maybe containing the Part representing the loaded artifact, or empty if not found.
   */
  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, Optional<Integer> version) {
    int versionToLoad;
    if (version.isPresent()) {
      versionToLoad = version.get();
    } else {
      ImmutableList<Integer> versions =
          listVersions(appName, userId, sessionId, filename).blockingGet();
      if (versions.isEmpty()) {
        return Maybe.empty();
      }
      versionToLoad = max(versions);
    }

    String blobName = getBlobName(appName, userId, sessionId, filename, versionToLoad);
    BlobId blobId = BlobId.of(bucketName, blobName);

    try {
      Blob blob = storageClient.get(blobId);
      if (blob == null || !blob.exists()) {
        return Maybe.empty();
      }
      byte[] data = blob.getContent();
      String mimeType = blob.getContentType();
      return Maybe.just(Part.fromBytes(data, mimeType));
    } catch (StorageException e) {
      return Maybe.empty();
    }
  }

  /**
   * Lists all artifact keys for a given app, user, and session.
   * This includes both session-specific files and user-namespace files.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @return A Single containing a ListArtifactsResponse with sorted filenames.
   */
  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
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

    return Single.just(
        ListArtifactsResponse.builder().filenames(ImmutableList.sortedCopyOf(filenames)).build());
  }

  /**
   * Deletes all versions of a specified artifact from GCS.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param filename The filename of the artifact to delete.
   * @return A Completable indicating the completion of the delete operation.
   */
  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    ImmutableList<Integer> versions =
        listVersions(appName, userId, sessionId, filename).blockingGet();
    List<BlobId> blobIdsToDelete = new ArrayList<>();
    for (int version : versions) {
      String blobName = getBlobName(appName, userId, sessionId, filename, version);
      blobIdsToDelete.add(BlobId.of(bucketName, blobName));
    }

    if (!blobIdsToDelete.isEmpty()) {
      try {
        var unused = storageClient.delete(blobIdsToDelete);
      } catch (StorageException e) {
        throw new VerifyException("Failed to delete artifact versions from GCS", e);
      }
    }
    return Completable.complete();
  }

  /**
   * Lists all versions of a specified artifact.
   * This method retrieves all versions of the artifact based on the filename and returns them as a sorted list.
   *
   * @param appName The application name.
   * @param userId The user ID.
   * @param sessionId The session ID.
   * @param filename The filename of the artifact.
   * @return A Single containing a sorted list of version numbers for the specified artifact.
   */
  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    String prefix = getBlobPrefix(appName, userId, sessionId, filename);
    List<Integer> versions = new ArrayList<>();
    try {
      for (Blob blob : storageClient.list(bucketName, BlobListOption.prefix(prefix)).iterateAll()) {
        String name = blob.getName();
        int versionDelimiterIndex = name.lastIndexOf('/'); // immediately before the version number
        if (versionDelimiterIndex != -1 && versionDelimiterIndex < name.length() - 1) {
          versions.add(Integer.parseInt(name.substring(versionDelimiterIndex + 1)));
        }
      }
      return Single.just(ImmutableList.sortedCopyOf(versions));
    } catch (StorageException e) {
      return Single.just(ImmutableList.of());
    }
  }
}
