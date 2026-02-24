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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.StorageException;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link GcsArtifactService}. */
@RunWith(JUnit4.class)
public class GcsArtifactServiceTest {

  private static final String BUCKET_NAME = "test-bucket";
  private static final String APP_NAME = "test-app";
  private static final String USER_ID = "test-user";
  private static final String SESSION_ID = "test-session";
  private static final String FILENAME = "test-file.txt";
  private static final String USER_FILENAME = "user:config.json";

  @Mock private Storage mockStorage;
  @Mock private Page<Blob> mockBlobPage;
  @Captor private ArgumentCaptor<List<BlobId>> blobIdListCaptor;

  private GcsArtifactService service;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new GcsArtifactService(BUCKET_NAME, mockStorage);
    when(mockStorage.list(eq(BUCKET_NAME), any(BlobListOption.class))).thenReturn(mockBlobPage);
  }

  private Blob mockBlob(String name, String contentType, byte[] content) {
    Blob blob = mock(Blob.class);
    when(blob.getName()).thenReturn(name);
    when(blob.getContentType()).thenReturn(contentType);
    when(blob.getContent()).thenReturn(content);
    when(blob.exists()).thenReturn(true);
    BlobId blobId = BlobId.of(BUCKET_NAME, name);
    when(blob.getBlobId()).thenReturn(blobId);
    when(blob.getBucket()).thenReturn(BUCKET_NAME);
    return blob;
  }

  @Test
  public void save_firstVersion_savesCorrectly() {
    Part artifact = Part.fromBytes(new byte[] {1, 2, 3}, "application/octet-stream");
    String expectedBlobName =
        String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    BlobId expectedBlobId = BlobId.of(BUCKET_NAME, expectedBlobName);
    BlobInfo expectedBlobInfo =
        BlobInfo.newBuilder(expectedBlobId).setContentType("application/octet-stream").build();

    when(mockBlobPage.iterateAll()).thenReturn(ImmutableList.of());
    Blob savedBlob = mockBlob(expectedBlobName, "application/octet-stream", new byte[] {1, 2, 3});
    when(mockStorage.create(eq(expectedBlobInfo), eq(new byte[] {1, 2, 3}))).thenReturn(savedBlob);

    int version =
        service.saveArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact).blockingGet();

    assertThat(version).isEqualTo(0);
    verify(mockStorage).create(eq(expectedBlobInfo), eq(new byte[] {1, 2, 3}));
  }

  @Test
  public void save_subsequentVersion_savesCorrectly() {
    Part artifact = Part.fromBytes(new byte[] {4, 5}, "image/png");
    String blobNameV0 = String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    String expectedBlobNameV1 =
        String.format("%s/%s/%s/%s/1", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    BlobId expectedBlobIdV1 = BlobId.of(BUCKET_NAME, expectedBlobNameV1);
    BlobInfo expectedBlobInfoV1 =
        BlobInfo.newBuilder(expectedBlobIdV1).setContentType("image/png").build();

    Blob blobV0 = mockBlob(blobNameV0, "text/plain", new byte[] {1});
    when(mockBlobPage.iterateAll()).thenReturn(Collections.singletonList(blobV0));
    Blob savedBlob = mockBlob(expectedBlobNameV1, "image/png", new byte[] {4, 5});
    when(mockStorage.create(eq(expectedBlobInfoV1), eq(new byte[] {4, 5}))).thenReturn(savedBlob);

    int version =
        service.saveArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact).blockingGet();

    assertThat(version).isEqualTo(1);
    verify(mockStorage).create(eq(expectedBlobInfoV1), eq(new byte[] {4, 5}));
  }

  @Test
  public void save_userNamespace_savesCorrectly() {
    Part artifact = Part.fromBytes(new byte[] {1, 2, 3}, "application/json");
    String expectedBlobName = String.format("%s/%s/user/%s/0", APP_NAME, USER_ID, USER_FILENAME);
    BlobId expectedBlobId = BlobId.of(BUCKET_NAME, expectedBlobName);
    BlobInfo expectedBlobInfo =
        BlobInfo.newBuilder(expectedBlobId).setContentType("application/json").build();

    when(mockBlobPage.iterateAll()).thenReturn(ImmutableList.of());
    Blob savedBlob = mockBlob(expectedBlobName, "application/json", new byte[] {1, 2, 3});
    when(mockStorage.create(eq(expectedBlobInfo), eq(new byte[] {1, 2, 3}))).thenReturn(savedBlob);

    int version =
        service.saveArtifact(APP_NAME, USER_ID, SESSION_ID, USER_FILENAME, artifact).blockingGet();

    assertThat(version).isEqualTo(0);
    verify(mockStorage).create(eq(expectedBlobInfo), eq(new byte[] {1, 2, 3}));
  }

  @Test
  public void load_latestVersion_loadsCorrectly() {
    String blobNameV0 = String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    String blobNameV1 = String.format("%s/%s/%s/%s/1", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    Blob blobV0 = mockBlob(blobNameV0, "text/plain", new byte[] {1});
    Blob blobV1 = mockBlob(blobNameV1, "image/jpeg", new byte[] {2, 3});
    BlobId blobIdV1 = BlobId.of(BUCKET_NAME, blobNameV1);

    when(mockBlobPage.iterateAll()).thenReturn(Arrays.asList(blobV0, blobV1));
    when(mockStorage.get(blobIdV1)).thenReturn(blobV1);

    Optional<Part> loadedArtifact =
        asOptional(service.loadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, Optional.empty()));

    assertThat(loadedArtifact).isPresent();
    Optional<byte[]> actualDataOptional = loadedArtifact.get().inlineData().get().data();
    assertThat(actualDataOptional).isPresent();
    assertThat(actualDataOptional.get()).isEqualTo(new byte[] {2, 3});
    assertThat(loadedArtifact.get().inlineData().get().mimeType()).hasValue("image/jpeg");
    verify(mockStorage).get(blobIdV1);
  }

  @Test
  public void load_specificVersion_loadsCorrectly() {
    String blobNameV0 = String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    Blob blobV0 = mockBlob(blobNameV0, "text/plain", new byte[] {1});
    BlobId blobIdV0 = BlobId.of(BUCKET_NAME, blobNameV0);

    when(mockStorage.get(blobIdV0)).thenReturn(blobV0);

    Optional<Part> loadedArtifact =
        asOptional(service.loadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, Optional.of(0)));

    assertThat(loadedArtifact).isPresent();
    Optional<byte[]> actualDataOptional = loadedArtifact.get().inlineData().get().data();
    assertThat(actualDataOptional).isPresent();
    assertThat(actualDataOptional.get()).isEqualTo(new byte[] {1});
    assertThat(loadedArtifact.get().inlineData().get().mimeType()).hasValue("text/plain");
    verify(mockStorage).get(blobIdV0);
  }

  @Test
  public void load_userNamespace_loadsCorrectly() {
    String blobNameV0 = String.format("%s/%s/user/%s/0", APP_NAME, USER_ID, USER_FILENAME);
    Blob blobV0 = mockBlob(blobNameV0, "application/json", new byte[] {1});
    BlobId blobIdV0 = BlobId.of(BUCKET_NAME, blobNameV0);

    when(mockBlobPage.iterateAll()).thenReturn(Collections.singletonList(blobV0));
    when(mockStorage.get(blobIdV0)).thenReturn(blobV0);

    Optional<Part> loadedArtifact =
        asOptional(
            service.loadArtifact(APP_NAME, USER_ID, SESSION_ID, USER_FILENAME, Optional.empty()));

    assertThat(loadedArtifact).isPresent();
    Optional<byte[]> actualDataOptional = loadedArtifact.get().inlineData().get().data();
    assertThat(actualDataOptional).isPresent();
    assertThat(actualDataOptional.get()).isEqualTo(new byte[] {1});
    assertThat(loadedArtifact.get().inlineData().get().mimeType()).hasValue("application/json");
    verify(mockStorage).get(blobIdV0);
  }

  @Test
  public void load_versionNotFound_returnsEmpty() {
    String blobNameV0 = String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    BlobId blobIdV0 = BlobId.of(BUCKET_NAME, blobNameV0);

    when(mockStorage.get(blobIdV0)).thenReturn(null);

    Optional<Part> loadedArtifact =
        asOptional(service.loadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, Optional.of(0)));

    assertThat(loadedArtifact).isEmpty();
    verify(mockStorage).get(blobIdV0);
  }

  @Test
  public void load_noVersionsExist_returnsEmpty() {
    when(mockBlobPage.iterateAll()).thenReturn(ImmutableList.of());

    Optional<Part> loadedArtifact =
        asOptional(service.loadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, Optional.empty()));

    assertThat(loadedArtifact).isEmpty();
  }

  @Test
  public void list_noFiles_returnsEmpty() {
    String sessionPrefix = String.format("%s/%s/%s/", APP_NAME, USER_ID, SESSION_ID);
    String userPrefix = String.format("%s/%s/user/", APP_NAME, USER_ID);

    // Mocking generic Page class requires unchecked suppression.
    @SuppressWarnings("unchecked")
    Page<Blob> mockSessionPage = mock(Page.class);
    // Mocking generic Page class requires unchecked suppression.
    @SuppressWarnings("unchecked")
    Page<Blob> mockUserPage = mock(Page.class);
    when(mockStorage.list(BUCKET_NAME, BlobListOption.prefix(sessionPrefix)))
        .thenReturn(mockSessionPage);
    when(mockStorage.list(BUCKET_NAME, BlobListOption.prefix(userPrefix))).thenReturn(mockUserPage);
    when(mockSessionPage.iterateAll()).thenReturn(ImmutableList.of());
    when(mockUserPage.iterateAll()).thenReturn(ImmutableList.of());

    ListArtifactsResponse response =
        service.listArtifactKeys(APP_NAME, USER_ID, SESSION_ID).blockingGet();

    assertThat(response.filenames()).isEmpty();
  }

  @Test
  public void list_withFiles_returnsCorrectFilenames() {
    String sessionPrefix = String.format("%s/%s/%s/", APP_NAME, USER_ID, SESSION_ID);
    String userPrefix = String.format("%s/%s/user/", APP_NAME, USER_ID);
    String sessionFile1 = "session-file1.txt";
    String sessionFile2 = "session-file2.log";
    String userFile1 = "config.json";

    Blob blobS1V0 = mockBlob(sessionPrefix + sessionFile1 + "/0", "text/plain", new byte[0]);
    Blob blobS1V1 = mockBlob(sessionPrefix + sessionFile1 + "/1", "text/plain", new byte[0]);
    Blob blobS2V0 = mockBlob(sessionPrefix + sessionFile2 + "/0", "text/log", new byte[0]);
    Blob blobU1V0 = mockBlob(userPrefix + userFile1 + "/0", "app/json", new byte[0]);

    // Mocking generic Page class requires unchecked suppression.
    @SuppressWarnings("unchecked")
    Page<Blob> mockSessionPage = mock(Page.class);
    // Mocking generic Page class requires unchecked suppression.
    @SuppressWarnings("unchecked")
    Page<Blob> mockUserPage = mock(Page.class);
    when(mockStorage.list(BUCKET_NAME, BlobListOption.prefix(sessionPrefix)))
        .thenReturn(mockSessionPage);
    when(mockStorage.list(BUCKET_NAME, BlobListOption.prefix(userPrefix))).thenReturn(mockUserPage);
    when(mockSessionPage.iterateAll()).thenReturn(Arrays.asList(blobS1V0, blobS1V1, blobS2V0));
    when(mockUserPage.iterateAll()).thenReturn(Collections.singletonList(blobU1V0));

    ListArtifactsResponse response =
        service.listArtifactKeys(APP_NAME, USER_ID, SESSION_ID).blockingGet();

    assertThat(response.filenames()).containsExactly(sessionFile1, sessionFile2, userFile1);
  }

  @Test
  public void delete_removesAllVersions() {
    String blobNameV0 = String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    String blobNameV1 = String.format("%s/%s/%s/%s/1", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    Blob blobV0 = mockBlob(blobNameV0, "text/plain", new byte[] {1});
    Blob blobV1 = mockBlob(blobNameV1, "image/jpeg", new byte[] {2, 3});
    BlobId blobIdV0 = BlobId.of(BUCKET_NAME, blobNameV0);
    BlobId blobIdV1 = BlobId.of(BUCKET_NAME, blobNameV1);

    when(mockBlobPage.iterateAll()).thenReturn(Arrays.asList(blobV0, blobV1));

    service.deleteArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME).blockingAwait();

    // Verify delete was called for both blob IDs
    verify(mockStorage).delete(blobIdListCaptor.capture());
    assertThat(blobIdListCaptor.getValue()).containsExactly(blobIdV0, blobIdV1);
  }

  @Test
  public void listVersions_returnsCorrectVersions() {
    String blobNameV0 = String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    String blobNameV1 = String.format("%s/%s/%s/%s/1", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    String blobNameV2 = String.format("%s/%s/%s/%s/2", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    Blob blobV0 = mockBlob(blobNameV0, "text/plain", new byte[] {1});
    Blob blobV1 = mockBlob(blobNameV1, "image/jpeg", new byte[] {2, 3});
    Blob blobV2 = mockBlob(blobNameV2, "image/png", new byte[] {4});

    when(mockBlobPage.iterateAll()).thenReturn(Arrays.asList(blobV0, blobV1, blobV2));

    ImmutableList<Integer> versions =
        service.listVersions(APP_NAME, USER_ID, SESSION_ID, FILENAME).blockingGet();

    assertThat(versions).containsExactly(0, 1, 2).inOrder();
  }

  @Test
  public void listVersions_userNamespace_returnsCorrectVersions() {
    String blobNameV0 = String.format("%s/%s/user/%s/0", APP_NAME, USER_ID, USER_FILENAME);
    String blobNameV1 = String.format("%s/%s/user/%s/1", APP_NAME, USER_ID, USER_FILENAME);
    Blob blobV0 = mockBlob(blobNameV0, "app/json", new byte[] {1});
    Blob blobV1 = mockBlob(blobNameV1, "app/json", new byte[] {2, 3});

    when(mockBlobPage.iterateAll()).thenReturn(Arrays.asList(blobV0, blobV1));

    ImmutableList<Integer> versions =
        service.listVersions(APP_NAME, USER_ID, SESSION_ID, USER_FILENAME).blockingGet();

    assertThat(versions).containsExactly(0, 1).inOrder();
  }

  @Test
  public void listVersions_noVersions_returnsEmptyList() {
    when(mockBlobPage.iterateAll()).thenReturn(ImmutableList.of());

    ImmutableList<Integer> versions =
        service.listVersions(APP_NAME, USER_ID, SESSION_ID, FILENAME).blockingGet();

    assertThat(versions).isEmpty();
  }

  @Test
  public void saveAndReloadArtifact_savesAndReturnsFileData() {
    Part artifact = Part.fromBytes(new byte[] {1, 2, 3}, "application/octet-stream");
    String expectedBlobName =
        String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    BlobId expectedBlobId = BlobId.of(BUCKET_NAME, expectedBlobName);
    BlobInfo expectedBlobInfo =
        BlobInfo.newBuilder(expectedBlobId).setContentType("application/octet-stream").build();

    when(mockBlobPage.iterateAll()).thenReturn(ImmutableList.of());
    Blob savedBlob = mockBlob(expectedBlobName, "application/octet-stream", new byte[] {1, 2, 3});
    when(mockStorage.create(eq(expectedBlobInfo), eq(new byte[] {1, 2, 3}))).thenReturn(savedBlob);

    Optional<Part> result =
        asOptional(
            service.saveAndReloadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact));

    assertThat(result).isPresent();
    assertThat(result.get().fileData()).isPresent();
    assertThat(result.get().fileData().get().fileUri())
        .hasValue("gs://" + BUCKET_NAME + "/" + expectedBlobName);
    assertThat(result.get().fileData().get().mimeType()).hasValue("application/octet-stream");
    verify(mockStorage).create(eq(expectedBlobInfo), eq(new byte[] {1, 2, 3}));
  }

  @Test
  public void save_noInlineData_throwsException() {
    Part artifact = Part.builder().build(); // No inline data
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.saveArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact).blockingGet());
  }

  @Test
  public void save_storageException_throwsVerifyException() {
    Part artifact = Part.fromBytes(new byte[] {1}, "text/plain");
    when(mockBlobPage.iterateAll()).thenReturn(ImmutableList.of());
    when(mockStorage.create(any(BlobInfo.class), any(byte[].class)))
        .thenThrow(new StorageException(500, "Induced error"));

    assertThrows(
        VerifyException.class,
        () ->
            service.saveArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact).blockingGet());
  }

  @Test
  public void load_storageException_returnsEmpty() {
    String blobNameV0 = String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    BlobId blobIdV0 = BlobId.of(BUCKET_NAME, blobNameV0);
    when(mockStorage.get(blobIdV0)).thenThrow(new StorageException(500, "Induced error"));

    Optional<Part> loadedArtifact =
        asOptional(service.loadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, Optional.of(0)));

    assertThat(loadedArtifact).isEmpty();
  }

  @Test
  public void list_sessionStorageException_throwsVerifyException() {
    String sessionPrefix = String.format("%s/%s/%s/", APP_NAME, USER_ID, SESSION_ID);
    when(mockStorage.list(BUCKET_NAME, BlobListOption.prefix(sessionPrefix)))
        .thenThrow(new StorageException(500, "Induced error"));

    assertThrows(
        VerifyException.class,
        () -> service.listArtifactKeys(APP_NAME, USER_ID, SESSION_ID).blockingGet());
  }

  @Test
  public void list_userStorageException_throwsVerifyException() {
    String sessionPrefix = String.format("%s/%s/%s/", APP_NAME, USER_ID, SESSION_ID);
    String userPrefix = String.format("%s/%s/user/", APP_NAME, USER_ID);

    // Mocking generic Page class requires unchecked suppression.
    @SuppressWarnings("unchecked")
    Page<Blob> mockSessionPage = mock(Page.class);
    when(mockStorage.list(BUCKET_NAME, BlobListOption.prefix(sessionPrefix)))
        .thenReturn(mockSessionPage);
    when(mockSessionPage.iterateAll()).thenReturn(ImmutableList.of());

    when(mockStorage.list(BUCKET_NAME, BlobListOption.prefix(userPrefix)))
        .thenThrow(new StorageException(500, "Induced error"));

    assertThrows(
        VerifyException.class,
        () -> service.listArtifactKeys(APP_NAME, USER_ID, SESSION_ID).blockingGet());
  }

  @Test
  public void delete_storageException_throwsVerifyException() {
    String blobNameV0 = String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    Blob blobV0 = mockBlob(blobNameV0, "text/plain", new byte[] {1});

    when(mockBlobPage.iterateAll()).thenReturn(Collections.singletonList(blobV0));
    when(mockStorage.delete(ArgumentMatchers.<Iterable<BlobId>>any()))
        .thenThrow(new StorageException(500, "Induced error"));

    assertThrows(
        VerifyException.class,
        () -> service.deleteArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME).blockingAwait());
  }

  @Test
  public void listVersions_storageException_returnsEmptyList() {
    String prefix = String.format("%s/%s/%s/%s/", APP_NAME, USER_ID, SESSION_ID, FILENAME);
    when(mockStorage.list(BUCKET_NAME, BlobListOption.prefix(prefix)))
        .thenThrow(new StorageException(500, "Induced error"));

    ImmutableList<Integer> versions =
        service.listVersions(APP_NAME, USER_ID, SESSION_ID, FILENAME).blockingGet();

    assertThat(versions).isEmpty();
  }

  @Test
  public void saveAndReload_noContentTypeAnywhere_defaultsToOctetStream() {
    // Artifact with no mime type
    Part artifact =
        Part.builder()
            .inlineData(com.google.genai.types.Blob.builder().data(new byte[] {1}).build())
            .build();
    String expectedBlobName =
        String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);

    when(mockBlobPage.iterateAll()).thenReturn(ImmutableList.of());
    Blob savedBlob = mock(Blob.class);
    when(savedBlob.getName()).thenReturn(expectedBlobName);
    when(savedBlob.getBucket()).thenReturn(BUCKET_NAME);
    when(savedBlob.getContentType()).thenReturn(null);
    when(mockStorage.create(any(BlobInfo.class), any(byte[].class))).thenReturn(savedBlob);

    Part result =
        service
            .saveAndReloadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact)
            .blockingGet();

    assertThat(result.fileData().get().mimeType()).hasValue("application/octet-stream");
  }

  @Test
  public void saveAndReload_blobMissingContentType_usesArtifactContentType() {
    Part artifact = Part.fromBytes(new byte[] {1}, "application/pdf");
    String expectedBlobName =
        String.format("%s/%s/%s/%s/0", APP_NAME, USER_ID, SESSION_ID, FILENAME);

    when(mockBlobPage.iterateAll()).thenReturn(ImmutableList.of());
    Blob savedBlob = mock(Blob.class);
    when(savedBlob.getName()).thenReturn(expectedBlobName);
    when(savedBlob.getBucket()).thenReturn(BUCKET_NAME);
    when(savedBlob.getContentType()).thenReturn(null);
    when(mockStorage.create(any(BlobInfo.class), any(byte[].class))).thenReturn(savedBlob);

    Part result =
        service
            .saveAndReloadArtifact(APP_NAME, USER_ID, SESSION_ID, FILENAME, artifact)
            .blockingGet();

    assertThat(result.fileData().get().mimeType()).hasValue("application/pdf");
  }

  private static <T> Optional<T> asOptional(Maybe<T> maybe) {
    return maybe.map(Optional::of).defaultIfEmpty(Optional.empty()).blockingGet();
  }

  private static <T> Optional<T> asOptional(Single<T> single) {
    return Optional.of(single.blockingGet());
  }
}
