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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.adk.store.PostgresArtifactStore;
import com.google.adk.store.PostgresArtifactStore.ArtifactData;
import com.google.genai.types.Part;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for {@link PostgresArtifactService}.
 *
 * <p>These tests verify the reactive RxJava3 wrapper implementation around PostgresArtifactStore,
 * ensuring proper artifact lifecycle management (save, load, delete, list operations) with
 * multi-tenancy support.
 *
 * @author Yashas S
 * @since 2026-01-08
 */
public class PostgresArtifactServiceTest {

  private PostgresArtifactStore mockStore;
  private PostgresArtifactService artifactService;
  private MockedStatic<PostgresArtifactStore> mockedStaticStore;
  private ObjectMapper objectMapper;

  @BeforeEach
  public void setUp() {
    mockStore = mock(PostgresArtifactStore.class);
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new Jdk8Module());

    // Mock the static getInstance method
    mockedStaticStore = Mockito.mockStatic(PostgresArtifactStore.class);
    mockedStaticStore
        .when(() -> PostgresArtifactStore.getInstance(anyString()))
        .thenReturn(mockStore);

    artifactService = new PostgresArtifactService();
  }

  @AfterEach
  public void tearDown() {
    mockedStaticStore.close();
  }

  @Test
  public void testSaveArtifact_Success() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";
    String filename = "test.txt";
    byte[] contentBytes = "Hello, World!".getBytes();
    Part artifact = Part.fromBytes(contentBytes, "text/plain");
    int expectedVersion = 1;

    when(mockStore.saveArtifact(
            eq(appName),
            eq(userId),
            eq(sessionId),
            eq(filename),
            any(byte[].class),
            anyString(),
            isNull()))
        .thenReturn(expectedVersion);

    // Act
    Integer version =
        artifactService.saveArtifact(appName, userId, sessionId, filename, artifact).blockingGet();

    // Assert
    assertThat(version).isEqualTo(expectedVersion);
    verify(mockStore)
        .saveArtifact(
            eq(appName),
            eq(userId),
            eq(sessionId),
            eq(filename),
            any(byte[].class),
            anyString(),
            isNull());
  }

  @Test
  public void testSaveArtifact_WithMetadata() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";
    String filename = "report.pdf";
    byte[] contentBytes = "Report content".getBytes();
    Part artifact = Part.fromBytes(contentBytes, "text/plain");
    String metadata = "{\"author\":\"John\",\"tags\":[\"important\"]}";
    int expectedVersion = 2;

    when(mockStore.saveArtifact(
            eq(appName),
            eq(userId),
            eq(sessionId),
            eq(filename),
            any(byte[].class),
            anyString(),
            eq(metadata)))
        .thenReturn(expectedVersion);

    // Act
    Integer version =
        artifactService
            .saveArtifact(appName, userId, sessionId, filename, artifact, metadata)
            .blockingGet();

    // Assert
    assertThat(version).isEqualTo(expectedVersion);
    verify(mockStore)
        .saveArtifact(
            eq(appName),
            eq(userId),
            eq(sessionId),
            eq(filename),
            any(byte[].class),
            anyString(),
            eq(metadata));
  }

  @Test
  public void testLoadArtifact_LatestVersion() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";
    String filename = "test.txt";
    String content = "Hello, World!";
    byte[] contentBytes = content.getBytes();
    Part expectedArtifact = Part.fromBytes(contentBytes, "text/plain");

    ArtifactData artifactData =
        new ArtifactData(
            contentBytes, "text/plain", 1, new Timestamp(System.currentTimeMillis()), null);

    when(mockStore.loadArtifact(eq(appName), eq(userId), eq(sessionId), eq(filename), isNull()))
        .thenReturn(artifactData);

    // Act
    Part loadedArtifact =
        artifactService.loadArtifact(appName, userId, sessionId, filename, null).blockingGet();

    // Assert
    assertThat(loadedArtifact).isNotNull();
    assertThat(loadedArtifact.text()).isEqualTo(expectedArtifact.text());
    verify(mockStore).loadArtifact(eq(appName), eq(userId), eq(sessionId), eq(filename), isNull());
  }

  @Test
  public void testLoadArtifact_SpecificVersion() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";
    String filename = "test.txt";
    int version = 3;
    String content = "Version 3 content";
    byte[] contentBytes = content.getBytes();
    Part expectedArtifact = Part.fromBytes(contentBytes, "text/plain");

    ArtifactData artifactData =
        new ArtifactData(
            contentBytes, "text/plain", version, new Timestamp(System.currentTimeMillis()), null);

    when(mockStore.loadArtifact(eq(appName), eq(userId), eq(sessionId), eq(filename), eq(version)))
        .thenReturn(artifactData);

    // Act
    Part loadedArtifact =
        artifactService.loadArtifact(appName, userId, sessionId, filename, version).blockingGet();

    // Assert
    assertThat(loadedArtifact).isNotNull();
    assertThat(loadedArtifact.text()).isEqualTo(expectedArtifact.text());
    verify(mockStore)
        .loadArtifact(eq(appName), eq(userId), eq(sessionId), eq(filename), eq(version));
  }

  @Test
  public void testLoadArtifact_NotFound() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";
    String filename = "nonexistent.txt";

    when(mockStore.loadArtifact(eq(appName), eq(userId), eq(sessionId), eq(filename), isNull()))
        .thenReturn(null);

    // Act
    Part loadedArtifact =
        artifactService.loadArtifact(appName, userId, sessionId, filename, null).blockingGet();

    // Assert
    assertThat(loadedArtifact).isNull();
  }

  @Test
  public void testDeleteArtifact_Success() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";
    String filename = "delete-me.txt";

    // Act
    artifactService.deleteArtifact(appName, userId, sessionId, filename).blockingAwait();

    // Assert
    verify(mockStore).deleteArtifact(eq(appName), eq(userId), eq(sessionId), eq(filename));
  }

  @Test
  public void testListArtifactKeys_Success() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";
    List<String> expectedFilenames = Arrays.asList("file1.txt", "file2.pdf", "file3.json");

    when(mockStore.listFilenames(eq(appName), eq(userId), eq(sessionId)))
        .thenReturn(expectedFilenames);

    // Act
    ListArtifactsResponse response =
        artifactService.listArtifactKeys(appName, userId, sessionId).blockingGet();

    // Assert
    assertThat(response).isNotNull();
    assertThat(response.filenames()).containsExactlyElementsIn(expectedFilenames);
    verify(mockStore).listFilenames(eq(appName), eq(userId), eq(sessionId));
  }

  @Test
  public void testListArtifactKeys_EmptyList() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";

    when(mockStore.listFilenames(eq(appName), eq(userId), eq(sessionId)))
        .thenReturn(Arrays.asList());

    // Act
    ListArtifactsResponse response =
        artifactService.listArtifactKeys(appName, userId, sessionId).blockingGet();

    // Assert
    assertThat(response).isNotNull();
    assertThat(response.filenames()).isEmpty();
  }

  @Test
  public void testListVersions_Success() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";
    String filename = "versioned-file.txt";
    List<Integer> expectedVersions = Arrays.asList(0, 1, 2, 3);

    when(mockStore.listVersions(eq(appName), eq(userId), eq(sessionId), eq(filename)))
        .thenReturn(expectedVersions);

    // Act
    List<Integer> versions =
        artifactService.listVersions(appName, userId, sessionId, filename).blockingGet();

    // Assert
    assertThat(versions).containsExactlyElementsIn(expectedVersions).inOrder();
    verify(mockStore).listVersions(eq(appName), eq(userId), eq(sessionId), eq(filename));
  }

  @Test
  public void testListVersions_NoVersions() throws Exception {
    // Arrange
    String appName = "testApp";
    String userId = "user123";
    String sessionId = "session456";
    String filename = "no-versions.txt";

    when(mockStore.listVersions(eq(appName), eq(userId), eq(sessionId), eq(filename)))
        .thenReturn(Arrays.asList());

    // Act
    List<Integer> versions =
        artifactService.listVersions(appName, userId, sessionId, filename).blockingGet();

    // Assert
    assertThat(versions).isEmpty();
  }

  @Test
  public void testMultiTenancy_IsolatedByAppNameUserIdSessionId() throws Exception {
    // This test verifies that artifacts are properly isolated by tenant identifiers
    String appName1 = "app1";
    String appName2 = "app2";
    String userId1 = "user1";
    String userId2 = "user2";
    String sessionId1 = "session1";
    String sessionId2 = "session2";
    String filename = "shared-filename.txt";

    byte[] contentBytes1 = "App1 User1 Session1".getBytes();
    byte[] contentBytes2 = "App2 User2 Session2".getBytes();
    Part artifact1 = Part.fromBytes(contentBytes1, "text/plain");
    Part artifact2 = Part.fromBytes(contentBytes2, "text/plain");

    when(mockStore.saveArtifact(
            eq(appName1), eq(userId1), eq(sessionId1), eq(filename), any(), anyString(), isNull()))
        .thenReturn(0);
    when(mockStore.saveArtifact(
            eq(appName2), eq(userId2), eq(sessionId2), eq(filename), any(), anyString(), isNull()))
        .thenReturn(0);

    // Act - Save artifacts for different tenants
    artifactService.saveArtifact(appName1, userId1, sessionId1, filename, artifact1).blockingGet();
    artifactService.saveArtifact(appName2, userId2, sessionId2, filename, artifact2).blockingGet();

    // Assert - Verify both saves were called with correct tenant identifiers
    verify(mockStore)
        .saveArtifact(
            eq(appName1), eq(userId1), eq(sessionId1), eq(filename), any(), anyString(), isNull());
    verify(mockStore)
        .saveArtifact(
            eq(appName2), eq(userId2), eq(sessionId2), eq(filename), any(), anyString(), isNull());
  }
}
