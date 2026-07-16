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

import com.google.adk.kafka.consumer.KafkaWriter;
import com.google.adk.store.PostgresArtifactStore;
import com.google.adk.store.PostgresArtifactStore.ArtifactData;
import com.google.adk.utils.PropertiesHelper;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import org.json.JSONObject;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * A PostgreSQL-backed implementation of the {@link BaseArtifactService}.
 *
 * <p>Stores artifacts persistently in PostgreSQL database with BYTEA storage. Uses reactive RxJava3
 * types wrapping JDBC operations. Supports environment variable configuration or explicit
 * constructor parameters.
 *
 * <p><b>Single Table Per JVM:</b> This service uses a single "artifacts" table per JVM for all
 * artifact storage. Multi-tenancy is achieved through (appName, userId, sessionId) isolation,
 * eliminating the need for separate physical tables.
 *
 * <p>Example usage with environment variables:
 *
 * <pre>{@code
 * PostgresArtifactService artifactService = new PostgresArtifactService();
 * // Uses DBURL, DBUSER, DBPASSWORD environment variables
 * // Stores in "artifacts" table with appName/userId/sessionId isolation
 * }</pre>
 *
 * <p>Example usage with explicit connection parameters:
 *
 * <pre>{@code
 * PostgresArtifactService artifactService = new PostgresArtifactService(
 *     "jdbc:postgresql://localhost:5432/mydb",
 *     "username",
 *     "password"
 * );
 * }</pre>
 *
 * @author Yashas S
 * @since 2025-12-08
 */
public final class PostgresArtifactService implements BaseArtifactService {

  private static final String DEFAULT_TABLE_NAME = "artifacts";
  private static final String DEFAULT_MIME_TYPE = "application/octet-stream";
  private final PostgresArtifactStore dbHelper;
  private final @Nullable S3Client s3Client;
  private final @Nullable String s3Bucket;
  private final boolean kafkaEnabled;
  private final @Nullable String kafkaTopic;

  private final @Nullable String s3BasePath;

  /**
   * Creates a new PostgresArtifactService using environment variables for database connection. Uses
   * the default "artifacts" table. Per JVM, only one table is used for all artifact operations,
   * with multi-tenancy achieved through (appName, userId, sessionId) isolation.
   *
   * <p>Required environment variables:
   *
   * <ul>
   *   <li>DBURL - PostgreSQL database URL (e.g., jdbc:postgresql://localhost:5432/mydb)
   *   <li>DBUSER - Database username
   *   <li>DBPASSWORD - Database password
   * </ul>
   */
  public PostgresArtifactService() {
    this.dbHelper = PostgresArtifactStore.getInstance(DEFAULT_TABLE_NAME);
    this.s3Bucket = getenvOrNull("S3_BUCKET");
    this.s3BasePath = getenvOrNull("S3_BASE_PATH");
    this.s3Client = s3Bucket != null ? buildS3Client() : null;
    this.kafkaEnabled = Boolean.parseBoolean(PropertiesHelper.getInstance().getValue("use_kafka"));
    this.kafkaTopic = PropertiesHelper.getInstance().getValue("kafka_topic");
  }

  /**
   * Creates a new PostgresArtifactService with explicit connection parameters. Uses the default
   * "artifacts" table.
   *
   * <p>This constructor is useful for testing or when environment variables are not available.
   *
   * @param dbUrl the database URL
   * @param dbUser the database username
   * @param dbPassword the database password
   */
  public PostgresArtifactService(String dbUrl, String dbUser, String dbPassword) {
    this.dbHelper =
        PostgresArtifactStore.createInstance(dbUrl, dbUser, dbPassword, DEFAULT_TABLE_NAME);
    this.s3Bucket = getenvOrNull("S3_BUCKET");
    this.s3BasePath = getenvOrNull("S3_BASE_PATH");
    this.s3Client = s3Bucket != null ? buildS3Client() : null;
    this.kafkaEnabled = Boolean.parseBoolean(PropertiesHelper.getInstance().getValue("use_kafka"));
    this.kafkaTopic = PropertiesHelper.getInstance().getValue("kafka_topic");
  }

  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    return Single.fromCallable(
            () -> {
              try {
                // Extract data from Part
                byte[] data = extractBytesFromPart(artifact);
                String mimeType = extractMimeTypeFromPart(artifact);

                int version =
                    dbHelper.saveArtifact(
                        appName, userId, sessionId, filename, data, mimeType, null);
                persistToS3AndKafka(
                    appName, userId, sessionId, filename, version, data, mimeType, null);
                return version;
              } catch (SQLException e) {
                throw new RuntimeException("Failed to save artifact: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Save an artifact with custom metadata.
   *
   * <p>This overloaded method allows the caller to provide custom metadata as a JSON string.
   * Metadata can contain any application-specific information (e.g., cost tracking, business
   * context, processing results).
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * String metadata = "{\"projectId\":\"ABC\",\"uploadedBy\":\"user123\",\"cost\":0.005}";
   * artifactService.saveArtifact(appName, userId, sessionId, filename, part, metadata);
   * }</pre>
   *
   * @param appName the application name
   * @param userId the user ID
   * @param sessionId the session ID
   * @param filename the artifact filename
   * @param artifact the artifact as a Part object
   * @param metadata custom metadata JSON string (can be null)
   * @return a Single emitting the version number of the saved artifact
   */
  public Single<Integer> saveArtifact(
      String appName,
      String userId,
      String sessionId,
      String filename,
      Part artifact,
      String metadata) {
    return Single.fromCallable(
            () -> {
              try {
                // Extract data from Part
                byte[] data = extractBytesFromPart(artifact);
                String mimeType = extractMimeTypeFromPart(artifact);

                int version =
                    dbHelper.saveArtifact(
                        appName, userId, sessionId, filename, data, mimeType, metadata);
                persistToS3AndKafka(
                    appName, userId, sessionId, filename, version, data, mimeType, metadata);
                return version;
              } catch (SQLException e) {
                throw new RuntimeException("Failed to save artifact: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, @Nullable Integer version) {
    return Maybe.fromCallable(
            () -> {
              try {
                // Load from database
                ArtifactData artifactData =
                    dbHelper.loadArtifact(appName, userId, sessionId, filename, version);

                if (artifactData == null) {
                  return null;
                }

                // Reconstruct Part from binary data
                return Part.fromBytes(artifactData.data, artifactData.mimeType);
              } catch (SQLException e) {
                throw new RuntimeException("Failed to load artifact: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    return Single.fromCallable(
            () -> {
              try {
                List<String> filenames = dbHelper.listFilenames(appName, userId, sessionId);
                return ListArtifactsResponse.builder().filenames(filenames).build();
              } catch (SQLException e) {
                throw new RuntimeException("Failed to list artifacts: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    return Completable.fromAction(
            () -> {
              try {
                dbHelper.deleteArtifact(appName, userId, sessionId, filename);
              } catch (SQLException e) {
                throw new RuntimeException("Failed to delete artifact: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    return Single.fromCallable(
            () -> {
              try {
                List<Integer> versions =
                    dbHelper.listVersions(appName, userId, sessionId, filename);
                return ImmutableList.copyOf(versions);
              } catch (SQLException e) {
                throw new RuntimeException("Failed to list versions: " + e.getMessage(), e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  /**
   * Extract bytes from Part object. Handles the nested Optional structure of Part.inlineData().
   *
   * @param part the Part object to extract bytes from
   * @return the byte array
   * @throws IllegalStateException if Part does not contain inline data
   */
  private byte[] extractBytesFromPart(Part part) {
    if (part.inlineData() != null && part.inlineData().isPresent()) {
      return part.inlineData()
          .get()
          .data()
          .orElseThrow(() -> new IllegalStateException("Part does not contain data"));
    }
    throw new IllegalStateException("Part does not contain inline data");
  }

  /**
   * Extract MIME type from Part object.
   *
   * @param part the Part object to extract MIME type from
   * @return the MIME type, or "application/octet-stream" as default
   */
  private String extractMimeTypeFromPart(Part part) {
    if (part.inlineData() != null && part.inlineData().isPresent()) {
      return part.inlineData().get().mimeType().orElse(DEFAULT_MIME_TYPE);
    }
    return DEFAULT_MIME_TYPE; // Default fallback
  }

  private void persistToS3AndKafka(
      String appName,
      String userId,
      String sessionId,
      String filename,
      int version,
      byte[] data,
      String mimeType,
      @Nullable String metadata) {
    if (s3Client == null || s3Bucket == null) {
      return;
    }
    String key = buildObjectKey(appName, userId, sessionId, filename, version);
    
    // Construct an HTTPS URL instead of an s3:// path
    String region = getenvOrNull("S3_REGION");
    String fileUri;
    if (region != null && !region.isBlank()) {
      fileUri = String.format("https://%s.s3.%s.amazonaws.com/%s", s3Bucket, region, key);
    } else {
      // Fallback to global endpoint if region is not set
      fileUri = String.format("https://%s.s3.amazonaws.com/%s", s3Bucket, key);
    }

    try {
      PutObjectRequest.Builder requestBuilder =
          PutObjectRequest.builder().bucket(s3Bucket).key(key).contentType(mimeType);
      s3Client.putObject(requestBuilder.build(), RequestBody.fromBytes(data));
      dbHelper.updateFileUri(appName, userId, sessionId, filename, version, fileUri);
      publishKafkaEvent(
          appName, userId, sessionId, filename, version, mimeType, metadata, fileUri, data.length);
    } catch (S3Exception | SQLException e) {
      // Fail-open: Postgres save already succeeded; log and continue.
      System.err.println("⚠️  Failed to persist artifact to S3/Kafka: " + e.getMessage());
    }
  }

  private void publishKafkaEvent(
      String appName,
      String userId,
      String sessionId,
      String filename,
      int version,
      String mimeType,
      @Nullable String metadata,
      String fileUri,
      int sizeBytes) {
    if (!kafkaEnabled || kafkaTopic == null || kafkaTopic.isBlank()) {
      return;
    }
    JSONObject payload = new JSONObject();
    payload.put("type", "artifact");
    payload.put("appName", appName);
    payload.put("userId", userId);
    payload.put("sessionId", sessionId);
    payload.put("filename", filename);
    payload.put("version", version);
    payload.put("mimeType", mimeType);
    payload.put("fileUri", fileUri);
    payload.put("sizeBytes", sizeBytes);
    if (metadata != null) {
      payload.put("metadata", metadata);
    }
    String key = appName + ":" + userId + ":" + sessionId + ":" + filename + ":" + version;
    try {
      KafkaWriter.PublishWithStatus(kafkaTopic, key, payload.toString());
    } catch (Exception e) {
      System.err.println("⚠️  Failed to publish artifact event to Kafka: " + e.getMessage());
    }
  }

  private String buildObjectKey(
      String appName, String userId, String sessionId, String filename, int version) {
    String key;
    if (filename != null && filename.startsWith("user:")) {
      key = String.format("%s/%s/user/%s/%d", appName, userId, filename, version);
    } else {
      key = String.format("%s/%s/%s/%s/%d", appName, userId, sessionId, filename, version);
    }

    if (s3BasePath != null && !s3BasePath.isBlank()) {
      // Ensure the base path doesn't end with a slash to avoid double slashes
      String cleanBasePath =
          s3BasePath.endsWith("/") ? s3BasePath.substring(0, s3BasePath.length() - 1) : s3BasePath;
      return cleanBasePath + "/" + key;
    }
    return key;
  }

  private static @Nullable String getenvOrNull(String key) {
    String value = System.getenv(key);
    return value == null || value.isBlank() ? null : value;
  }

  private static S3Client buildS3Client() {
    var builder = S3Client.builder();
    String region = getenvOrNull("S3_REGION");
    if (region != null) {
      builder.region(Region.of(region));
    }
    String endpoint = getenvOrNull("S3_ENDPOINT");
    if (endpoint != null) {
      builder.endpointOverride(URI.create(endpoint));
    }
    String pathStyle = getenvOrNull("S3_PATH_STYLE");
    if (pathStyle != null && Boolean.parseBoolean(pathStyle)) {
      builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }
    return builder.build();
  }

  /** Closes the database connection pool. Call this when shutting down the application. */
  public void close() {
    dbHelper.close();
    if (s3Client != null) {
      s3Client.close();
    }
  }
}
