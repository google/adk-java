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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.google.adk.store.CassandraHelper;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.CassandraContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link CassandraArtifactService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-21
 */
@Testcontainers
public class CassandraArtifactServiceIT {

  @Container
  private static final CassandraContainer<?> cassandra =
      new CassandraContainer<>("cassandra:latest");

  private static CassandraArtifactService artifactService;

  @BeforeAll
  public static void setUp() {
    cassandra.start();
    CqlSessionBuilder sessionBuilder =
        CqlSession.builder()
            .addContactPoint(
                new InetSocketAddress(cassandra.getHost(), cassandra.getMappedPort(9042)))
            .withLocalDatacenter(cassandra.getLocalDatacenter());
    CassandraHelper.initialize(sessionBuilder);
    artifactService = new CassandraArtifactService();
  }

  @AfterAll
  public static void tearDown() {
    CassandraHelper.close();
    cassandra.stop();
  }

  @Test
  public void testSaveAndLoadArtifact() {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";
    String filename = "test.txt";
    Part artifact = Part.fromText("hello world");

    Integer version =
        artifactService.saveArtifact(appName, userId, sessionId, filename, artifact).blockingGet();
    assertThat(version).isEqualTo(0);

    Part loadedArtifact =
        artifactService.loadArtifact(appName, userId, sessionId, filename, version).blockingGet();
    assertThat(loadedArtifact.text().get()).isEqualTo("hello world");
  }

  @Test
  public void testDeleteArtifact() {
    String appName = "testApp";
    String userId = "testUserDelete";
    String sessionId = "testSession";
    String filename = "test.txt";
    Part artifact = Part.fromText("hello world");

    Integer version =
        artifactService.saveArtifact(appName, userId, sessionId, filename, artifact).blockingGet();
    assertThat(version).isEqualTo(0);

    artifactService.deleteArtifact(appName, userId, sessionId, filename).blockingAwait();

    Maybe<Part> loadedArtifact =
        artifactService.loadArtifact(appName, userId, sessionId, filename, version);
    assertThat(loadedArtifact.blockingGet()).isNull();
  }

  @Test
  public void testListArtifactKeys() {
    String appName = "testApp";
    String userId = "testUserList";
    String sessionId = "testSession";
    String filename1 = "test1.txt";
    String filename2 = "test2.txt";
    Part artifact = Part.fromText("hello world");

    artifactService.saveArtifact(appName, userId, sessionId, filename1, artifact).blockingGet();
    artifactService.saveArtifact(appName, userId, sessionId, filename2, artifact).blockingGet();

    List<String> artifactKeys =
        artifactService.listArtifactKeys(appName, userId, sessionId).blockingGet().filenames();
    assertThat(artifactKeys).containsExactly(filename1, filename2);
  }

  @Test
  public void testSaveAndLoadBinaryArtifact() {
    String appName = "testApp";
    String userId = "testUserBinary";
    String sessionId = "testSessionBinary";
    String filename = "test.bin";
    byte[] binaryData = new byte[] {1, 2, 3, 4, 5};
    Part artifact = Part.fromBytes(binaryData, "application/octet-stream");

    Integer version =
        artifactService.saveArtifact(appName, userId, sessionId, filename, artifact).blockingGet();
    assertThat(version).isEqualTo(0);

    Part loadedBinaryArtifact =
        artifactService.loadArtifact(appName, userId, sessionId, filename, version).blockingGet();
    assertThat(loadedBinaryArtifact.inlineData().get().data().get()).isEqualTo(binaryData);
    assertThat(loadedBinaryArtifact.inlineData().get().mimeType().get())
        .isEqualTo("application/octet-stream");
  }
}
