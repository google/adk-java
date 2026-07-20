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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.store.CassandraHelper;
import com.google.genai.types.Part;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * Unit tests for {@link CassandraArtifactService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-21
 */
public class CassandraArtifactServiceTest {

  private CqlSession mockCqlSession;
  private ObjectMapper mockObjectMapper;
  private CassandraArtifactService artifactService;
  private MockedStatic<CassandraHelper> cassandraHelper;

  @BeforeEach
  public void setUp() throws IOException {
    mockCqlSession = mock(CqlSession.class);
    mockObjectMapper = mock(ObjectMapper.class);
    cassandraHelper = Mockito.mockStatic(CassandraHelper.class);

    // Mock CassandraHelper to return our mock objects
    cassandraHelper.when(CassandraHelper::getSession).thenReturn(mockCqlSession);
    cassandraHelper.when(CassandraHelper::getObjectMapper).thenReturn(mockObjectMapper);
    artifactService = new CassandraArtifactService();
  }

  @AfterEach
  public void tearDown() {
    cassandraHelper.close();
  }

  @Test
  public void testSaveAndLoadArtifact() throws Exception {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";
    String filename = "test.txt";
    Part artifact = Part.fromText("hello world");
    byte[] artifactData = new byte[] {1, 2, 3};

    when(mockObjectMapper.writeValueAsBytes(artifact)).thenReturn(artifactData);
    ResultSet mockResultSet = mock(ResultSet.class);
    when(mockResultSet.iterator()).thenReturn(Collections.emptyIterator());
    when(mockCqlSession.execute(any(String.class), any(), any(), any(), any()))
        .thenReturn(mockResultSet);

    Integer version =
        artifactService.saveArtifact(appName, userId, sessionId, filename, artifact).blockingGet();
    assertThat(version).isEqualTo(0);

    ArgumentCaptor<ByteBuffer> byteBufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(mockCqlSession)
        .execute(any(String.class), any(), any(), any(), any(), any(), byteBufferCaptor.capture());
    assertThat(byteBufferCaptor.getValue().array()).isEqualTo(artifactData);

    Row mockRow = mock(Row.class);
    when(mockResultSet.one()).thenReturn(mockRow);
    when(mockCqlSession.execute(
            "SELECT artifact_data FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? AND version = ?",
            appName,
            userId,
            sessionId,
            filename,
            version))
        .thenReturn(mockResultSet);
    when(mockRow.getByteBuffer("artifact_data")).thenReturn(ByteBuffer.wrap(artifactData));
    when(mockObjectMapper.readValue(artifactData, Part.class)).thenReturn(artifact);

    Part loadedArtifact =
        artifactService.loadArtifact(appName, userId, sessionId, filename, version).blockingGet();
    assertThat(loadedArtifact).isEqualTo(artifact);
  }

  @Test
  public void testDeleteArtifact() {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";
    String filename = "test.txt";

    artifactService.deleteArtifact(appName, userId, sessionId, filename).blockingAwait();

    verify(mockCqlSession)
        .execute(
            "DELETE FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?",
            appName,
            userId,
            sessionId,
            filename);
  }

  @Test
  public void testListArtifactKeys() throws Exception {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";
    String filename1 = "test1.txt";
    String filename2 = "test2.txt";

    Row mockRow1 = mock(Row.class);
    when(mockRow1.getString("filename")).thenReturn(filename1);
    Row mockRow2 = mock(Row.class);
    when(mockRow2.getString("filename")).thenReturn(filename2);
    ResultSet mockResultSet = mock(ResultSet.class);
    when(mockResultSet.iterator())
        .thenReturn(java.util.Arrays.asList(mockRow1, mockRow2).iterator());
    when(mockCqlSession.execute(any(String.class), any(), any(), any())).thenReturn(mockResultSet);

    List<String> artifactKeys =
        artifactService.listArtifactKeys(appName, userId, sessionId).blockingGet().filenames();
    assertThat(artifactKeys).containsExactly(filename1, filename2);
  }

  @Test
  public void testListVersions() throws Exception {
    String appName = "testApp";
    String userId = "testUser";
    String sessionId = "testSession";
    String filename = "test.txt";

    Row mockRow1 = mock(Row.class);
    when(mockRow1.getInt("version")).thenReturn(0);
    Row mockRow2 = mock(Row.class);
    when(mockRow2.getInt("version")).thenReturn(1);
    ResultSet mockResultSet = mock(ResultSet.class);
    when(mockResultSet.iterator())
        .thenReturn(java.util.Arrays.asList(mockRow1, mockRow2).iterator());
    when(mockCqlSession.execute(any(String.class), any(), any(), any(), any()))
        .thenReturn(mockResultSet);

    List<Integer> versions =
        artifactService.listVersions(appName, userId, sessionId, filename).blockingGet();
    assertThat(versions).containsExactly(0, 1);
  }
}
