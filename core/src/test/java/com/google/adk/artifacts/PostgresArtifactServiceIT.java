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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.genai.types.Part;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Integration tests for {@link PostgresArtifactService}.
 *
 * <p>These tests require a running PostgreSQL database and validate end-to-end artifact operations
 * including save, load, versioning, deletion, and multi-tenancy isolation.
 *
 * <p>Tests are skipped if database connection environment variables are not set: DBURL, DBUSER,
 * DBPASSWORD
 *
 * @author Yashas S
 * @since 2026-01-08
 */
public class PostgresArtifactServiceIT {

  private static final Logger logger = LoggerFactory.getLogger(PostgresArtifactServiceIT.class);
  private static boolean dbAvailable = false;

  private PostgresArtifactService artifactService;
  private String testAppName;
  private String testUserId;
  private String testSessionId;

  @BeforeAll
  public static void checkDatabaseAvailability() {
    // Check if database environment variables are set
    String dbUrl = System.getenv("DBURL");
    String dbUser = System.getenv("DBUSER");
    String dbPassword = System.getenv("DBPASSWORD");

    dbAvailable = (dbUrl != null && dbUser != null && dbPassword != null);

    if (!dbAvailable) {
      logger.warn(
          "Skipping PostgresArtifactServiceIT tests - database environment variables not set "
              + "(DBURL, DBUSER, DBPASSWORD)");
    } else {
      logger.info("Database environment variables found - running integration tests");
    }
  }

  @BeforeEach
  public void setUp() {
    assumeTrue(dbAvailable, "Database not available for integration testing");

    artifactService = new PostgresArtifactService();
    testAppName = "test_app_" + System.currentTimeMillis();
    testUserId = "test_user_" + System.currentTimeMillis();
    testSessionId = "test_session_" + System.currentTimeMillis();
  }

  @AfterEach
  public void tearDown() {
    if (dbAvailable && artifactService != null) {
      // Clean up test artifacts
      try {
        artifactService
            .listArtifactKeys(testAppName, testUserId, testSessionId)
            .blockingGet()
            .filenames()
            .forEach(
                filename -> {
                  try {
                    artifactService
                        .deleteArtifact(testAppName, testUserId, testSessionId, filename)
                        .blockingAwait();
                  } catch (Exception e) {
                    logger.warn("Failed to clean up test artifact: {}", filename, e);
                  }
                });
      } catch (Exception e) {
        logger.warn("Failed to clean up test artifacts", e);
      }
    }
  }

  @Test
  public void testSaveAndLoadArtifact_Success() {
    // Arrange
    String filename = "test-document.txt";
    Part originalArtifact = Part.fromText("This is a test document");

    // Act - Save
    Integer version =
        artifactService
            .saveArtifact(testAppName, testUserId, testSessionId, filename, originalArtifact)
            .blockingGet();

    // Assert - Version returned
    assertThat(version).isEqualTo(0); // First version should be 0

    // Act - Load
    Part loadedArtifact =
        artifactService
            .loadArtifact(testAppName, testUserId, testSessionId, filename, null)
            .blockingGet();

    // Assert - Content matches
    assertThat(loadedArtifact).isNotNull();
    assertThat(loadedArtifact.text()).isEqualTo(originalArtifact.text());
  }

  @Test
  public void testVersioning_MultipleVersions() {
    // Arrange
    String filename = "versioned-file.txt";

    // Act - Save multiple versions
    Part v0 = Part.fromText("Version 0 content");
    Part v1 = Part.fromText("Version 1 content");
    Part v2 = Part.fromText("Version 2 content");

    Integer version0 =
        artifactService
            .saveArtifact(testAppName, testUserId, testSessionId, filename, v0)
            .blockingGet();
    Integer version1 =
        artifactService
            .saveArtifact(testAppName, testUserId, testSessionId, filename, v1)
            .blockingGet();
    Integer version2 =
        artifactService
            .saveArtifact(testAppName, testUserId, testSessionId, filename, v2)
            .blockingGet();

    // Assert - Version numbers increment
    assertThat(version0).isEqualTo(0);
    assertThat(version1).isEqualTo(1);
    assertThat(version2).isEqualTo(2);

    // Act - Load specific versions
    Part loaded0 =
        artifactService
            .loadArtifact(testAppName, testUserId, testSessionId, filename, 0)
            .blockingGet();
    Part loaded1 =
        artifactService
            .loadArtifact(testAppName, testUserId, testSessionId, filename, 1)
            .blockingGet();
    Part loaded2 =
        artifactService
            .loadArtifact(testAppName, testUserId, testSessionId, filename, 2)
            .blockingGet();

    // Assert - Each version contains correct content
    assertThat(loaded0.text()).isEqualTo("Version 0 content");
    assertThat(loaded1.text()).isEqualTo("Version 1 content");
    assertThat(loaded2.text()).isEqualTo("Version 2 content");

    // Act - Load latest (should be version 2)
    Part loadedLatest =
        artifactService
            .loadArtifact(testAppName, testUserId, testSessionId, filename, null)
            .blockingGet();

    // Assert - Latest is version 2
    assertThat(loadedLatest.text()).isEqualTo("Version 2 content");
  }

  @Test
  public void testListVersions() {
    // Arrange
    String filename = "multi-version.txt";

    // Act - Create 3 versions
    artifactService
        .saveArtifact(testAppName, testUserId, testSessionId, filename, Part.fromText("V0"))
        .blockingGet();
    artifactService
        .saveArtifact(testAppName, testUserId, testSessionId, filename, Part.fromText("V1"))
        .blockingGet();
    artifactService
        .saveArtifact(testAppName, testUserId, testSessionId, filename, Part.fromText("V2"))
        .blockingGet();

    // Act - List versions
    List<Integer> versions =
        artifactService
            .listVersions(testAppName, testUserId, testSessionId, filename)
            .blockingGet();

    // Assert
    assertThat(versions).containsExactly(0, 1, 2).inOrder();
  }

  @Test
  public void testDeleteArtifact() {
    // Arrange
    String filename = "to-be-deleted.txt";
    artifactService
        .saveArtifact(testAppName, testUserId, testSessionId, filename, Part.fromText("Delete me"))
        .blockingGet();

    // Verify it exists
    Part beforeDelete =
        artifactService
            .loadArtifact(testAppName, testUserId, testSessionId, filename, null)
            .blockingGet();
    assertThat(beforeDelete).isNotNull();

    // Act - Delete
    artifactService
        .deleteArtifact(testAppName, testUserId, testSessionId, filename)
        .blockingAwait();

    // Assert - No longer exists
    Part afterDelete =
        artifactService
            .loadArtifact(testAppName, testUserId, testSessionId, filename, null)
            .blockingGet();
    assertThat(afterDelete).isNull();
  }

  @Test
  public void testListArtifactKeys() {
    // Arrange - Create multiple artifacts
    artifactService
        .saveArtifact(
            testAppName, testUserId, testSessionId, "file1.txt", Part.fromText("Content 1"))
        .blockingGet();
    artifactService
        .saveArtifact(
            testAppName, testUserId, testSessionId, "file2.pdf", Part.fromText("Content 2"))
        .blockingGet();
    artifactService
        .saveArtifact(
            testAppName, testUserId, testSessionId, "file3.json", Part.fromText("Content 3"))
        .blockingGet();

    // Act
    ListArtifactsResponse response =
        artifactService.listArtifactKeys(testAppName, testUserId, testSessionId).blockingGet();

    // Assert
    assertThat(response.filenames()).containsExactly("file1.txt", "file2.pdf", "file3.json");
  }

  @Test
  public void testMultiTenancy_AppNameIsolation() {
    // Arrange
    String app1 = "app1_" + System.currentTimeMillis();
    String app2 = "app2_" + System.currentTimeMillis();
    String userId = "shared_user";
    String sessionId = "shared_session";
    String filename = "shared_filename.txt";

    // Act - Save same filename to different apps
    artifactService
        .saveArtifact(app1, userId, sessionId, filename, Part.fromText("App1 content"))
        .blockingGet();
    artifactService
        .saveArtifact(app2, userId, sessionId, filename, Part.fromText("App2 content"))
        .blockingGet();

    // Act - Load from each app
    Part fromApp1 =
        artifactService.loadArtifact(app1, userId, sessionId, filename, null).blockingGet();
    Part fromApp2 =
        artifactService.loadArtifact(app2, userId, sessionId, filename, null).blockingGet();

    // Assert - Content is isolated
    assertThat(fromApp1.text()).isEqualTo("App1 content");
    assertThat(fromApp2.text()).isEqualTo("App2 content");

    // Cleanup
    artifactService.deleteArtifact(app1, userId, sessionId, filename).blockingAwait();
    artifactService.deleteArtifact(app2, userId, sessionId, filename).blockingAwait();
  }

  @Test
  public void testMultiTenancy_UserIdIsolation() {
    // Arrange
    String appName = testAppName;
    String user1 = "user1_" + System.currentTimeMillis();
    String user2 = "user2_" + System.currentTimeMillis();
    String sessionId = "shared_session";
    String filename = "shared_filename.txt";

    // Act - Save same filename to different users
    artifactService
        .saveArtifact(appName, user1, sessionId, filename, Part.fromText("User1 content"))
        .blockingGet();
    artifactService
        .saveArtifact(appName, user2, sessionId, filename, Part.fromText("User2 content"))
        .blockingGet();

    // Act - Load from each user
    Part fromUser1 =
        artifactService.loadArtifact(appName, user1, sessionId, filename, null).blockingGet();
    Part fromUser2 =
        artifactService.loadArtifact(appName, user2, sessionId, filename, null).blockingGet();

    // Assert - Content is isolated
    assertThat(fromUser1.text()).isEqualTo("User1 content");
    assertThat(fromUser2.text()).isEqualTo("User2 content");

    // Cleanup
    artifactService.deleteArtifact(appName, user1, sessionId, filename).blockingAwait();
    artifactService.deleteArtifact(appName, user2, sessionId, filename).blockingAwait();
  }

  @Test
  public void testMultiTenancy_SessionIdIsolation() {
    // Arrange
    String appName = testAppName;
    String userId = testUserId;
    String session1 = "session1_" + System.currentTimeMillis();
    String session2 = "session2_" + System.currentTimeMillis();
    String filename = "shared_filename.txt";

    // Act - Save same filename to different sessions
    artifactService
        .saveArtifact(appName, userId, session1, filename, Part.fromText("Session1 content"))
        .blockingGet();
    artifactService
        .saveArtifact(appName, userId, session2, filename, Part.fromText("Session2 content"))
        .blockingGet();

    // Act - Load from each session
    Part fromSession1 =
        artifactService.loadArtifact(appName, userId, session1, filename, null).blockingGet();
    Part fromSession2 =
        artifactService.loadArtifact(appName, userId, session2, filename, null).blockingGet();

    // Assert - Content is isolated
    assertThat(fromSession1.text()).isEqualTo("Session1 content");
    assertThat(fromSession2.text()).isEqualTo("Session2 content");

    // Cleanup
    artifactService.deleteArtifact(appName, userId, session1, filename).blockingAwait();
    artifactService.deleteArtifact(appName, userId, session2, filename).blockingAwait();
  }

  @Test
  public void testSaveArtifact_WithMetadata() {
    // Arrange
    String filename = "doc-with-metadata.txt";
    Part artifact = Part.fromText("Document content");
    String metadata = "{\"author\":\"John Doe\",\"tags\":[\"important\",\"reviewed\"]}";

    // Act
    Integer version =
        artifactService
            .saveArtifact(testAppName, testUserId, testSessionId, filename, artifact, metadata)
            .blockingGet();

    // Assert
    assertThat(version).isEqualTo(0);

    // Note: Loading and verifying metadata would require extending the API
    // to expose metadata in the response, which is outside the scope of this test
  }

  @Test
  public void testLoadArtifact_NonExistent() {
    // Act
    Part result =
        artifactService
            .loadArtifact(testAppName, testUserId, testSessionId, "nonexistent.txt", null)
            .blockingGet();

    // Assert
    assertThat(result).isNull();
  }

  @Test
  public void testLoadArtifact_NonExistentVersion() {
    // Arrange - Create one version
    String filename = "single-version.txt";
    artifactService
        .saveArtifact(testAppName, testUserId, testSessionId, filename, Part.fromText("Version 0"))
        .blockingGet();

    // Act - Try to load non-existent version 99
    Part result =
        artifactService
            .loadArtifact(testAppName, testUserId, testSessionId, filename, 99)
            .blockingGet();

    // Assert
    assertThat(result).isNull();
  }

  @Test
  public void testConcurrentSaves_VersionIncrement() throws InterruptedException {
    // This test verifies that concurrent saves don't cause version conflicts
    String filename = "concurrent-test.txt";
    int numThreads = 5;
    Thread[] threads = new Thread[numThreads];

    // Act - Concurrent saves
    for (int i = 0; i < numThreads; i++) {
      final int threadNum = i;
      threads[i] =
          new Thread(
              () -> {
                artifactService
                    .saveArtifact(
                        testAppName,
                        testUserId,
                        testSessionId,
                        filename,
                        Part.fromText("Content from thread " + threadNum))
                    .blockingGet();
              });
      threads[i].start();
    }

    // Wait for all threads
    for (Thread thread : threads) {
      thread.join();
    }

    // Assert - Should have 5 versions (0-4)
    List<Integer> versions =
        artifactService
            .listVersions(testAppName, testUserId, testSessionId, filename)
            .blockingGet();
    assertThat(versions).hasSize(numThreads);
    assertThat(versions).containsExactly(0, 1, 2, 3, 4).inOrder();
  }
}
