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

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.store.CassandraHelper;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A Cassandra-backed implementation of the {@link BaseArtifactService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public final class CassandraArtifactService implements BaseArtifactService {
  private final CqlSession session;
  private final ObjectMapper objectMapper;

  public CassandraArtifactService() {
    this.session = CassandraHelper.getSession();
    this.objectMapper = CassandraHelper.getObjectMapper();
  }

  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    return listVersions(appName, userId, sessionId, filename)
        .flatMap(
            versions -> {
              int newVersion = versions.size();
              return Single.fromCallable(
                  () -> {
                    // Serialize the entire Part object to a byte array.
                    // Jackson automatically handles Base64 encoding for any binary data within the
                    // Part.
                    byte[] artifactData = objectMapper.writeValueAsBytes(artifact);
                    session.execute(
                        "INSERT INTO artifacts (app_name, user_id, session_id, filename, version, artifact_data) VALUES (?, ?, ?, ?, ?, ?)",
                        appName,
                        userId,
                        sessionId,
                        filename,
                        newVersion,
                        java.nio.ByteBuffer.wrap(artifactData));
                    return newVersion;
                  });
            });
  }

  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, @Nullable Integer version) {
    return Maybe.fromCallable(
        () -> {
          Row row;
          if (version != null) {
            row =
                session
                    .execute(
                        "SELECT artifact_data FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? AND version = ?",
                        appName,
                        userId,
                        sessionId,
                        filename,
                        version)
                    .one();
          } else {
            row =
                session
                    .execute(
                        "SELECT artifact_data FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ? ORDER BY version DESC LIMIT 1",
                        appName,
                        userId,
                        sessionId,
                        filename)
                    .one();
          }

          if (row == null) {
            return null;
          }
          // Retrieve the bytes from the BLOB column.
          java.nio.ByteBuffer byteBuffer = row.getByteBuffer("artifact_data");
          if (byteBuffer == null) {
            return null;
          }
          byte[] artifactData = new byte[byteBuffer.remaining()];
          byteBuffer.get(artifactData);

          // Deserialize the byte array back into a Part object.
          return objectMapper.readValue(artifactData, Part.class);
        });
  }

  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    return Single.fromCallable(
        () -> {
          ResultSet rs =
              session.execute(
                  "SELECT filename FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ?",
                  appName,
                  userId,
                  sessionId);
          List<String> filenames = new ArrayList<>();
          for (Row row : rs) {
            String filename = row.getString("filename");
            if (!filenames.contains(filename)) {
              filenames.add(filename);
            }
          }
          return ListArtifactsResponse.builder().filenames(ImmutableList.copyOf(filenames)).build();
        });
  }

  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    return Completable.fromAction(
        () ->
            session.execute(
                "DELETE FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?",
                appName,
                userId,
                sessionId,
                filename));
  }

  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    return Single.fromCallable(
        () -> {
          ResultSet rs =
              session.execute(
                  "SELECT version FROM artifacts WHERE app_name = ? AND user_id = ? AND session_id = ? AND filename = ?",
                  appName,
                  userId,
                  sessionId,
                  filename);
          List<Integer> versions = new ArrayList<>();
          for (Row row : rs) {
            versions.add(row.getInt("version"));
          }
          return ImmutableList.copyOf(versions);
        });
  }

  public static class CassandraArtifactServiceExample {
    public static void main(String[] args) {
      CqlSessionBuilder sessionBuilder =
          CqlSession.builder()
              .addContactPoint(new java.net.InetSocketAddress("127.0.0.1", 9042))
              .withLocalDatacenter("datacenter1");
      CassandraHelper.initialize(sessionBuilder);

      CassandraArtifactService artifactService = new CassandraArtifactService();

      String appName = "myApp";
      String userId = "user123";
      String sessionId = "session456";
      String filename = "greeting.txt";
      Part artifact = Part.fromText("Hello, world!");

      // Save an artifact
      Integer version =
          artifactService
              .saveArtifact(appName, userId, sessionId, filename, artifact)
              .blockingGet();
      System.out.println("Saved artifact '" + filename + "' with version: " + version);

      // Load the artifact
      Part loadedArtifact =
          artifactService.loadArtifact(appName, userId, sessionId, filename, version).blockingGet();
      System.out.println("Loaded artifact content: " + loadedArtifact.text().get());

      CassandraHelper.close();
    }
  }
}
