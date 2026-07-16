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

package com.google.adk.artifacts.migration;

import com.google.adk.kafka.consumer.KafkaWriter;
import com.google.adk.utils.PropertiesHelper;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Migrates artifacts from Postgres to S3 and publishes metadata to Kafka.
 *
 * <p>Required environment variables:
 *
 * <ul>
 *   <li>DBURL
 *   <li>DBUSER
 *   <li>DBPASSWORD
 *   <li>S3_BUCKET
 * </ul>
 *
 * <p>Optional environment variables:
 *
 * <ul>
 *   <li>ARTIFACTS_TABLE (defaults to "artifacts")
 *   <li>S3_REGION (uses SDK default provider if unset)
 *   <li>S3_ENDPOINT (for S3-compatible storage)
 *   <li>S3_PATH_STYLE (true/false, for S3-compatible storage)
 *   <li>KAFKA_TOPIC (defaults to kafka_topic in PropertiesHelper)
 * </ul>
 *
 * <p>Kafka message fields:
 *
 * <ul>
 *   <li>appName
 *   <li>userId
 *   <li>sessionId
 *   <li>filename
 *   <li>version
 *   <li>metadata
 *   <li>mimeType
 *   <li>createdAt
 *   <li>path (S3 URI)
 * </ul>
 */
public final class PostgresToS3KafkaMigrator {
  private static final Logger logger = LoggerFactory.getLogger(PostgresToS3KafkaMigrator.class);
  private static final String DEFAULT_TABLE_NAME = "artifacts";

  public static void main(String[] args) throws SQLException {
    String dbUrl = requiredEnv("DBURL");
    String dbUser = requiredEnv("DBUSER");
    String dbPassword = requiredEnv("DBPASSWORD");
    String bucketName = requiredEnv("S3_BUCKET");
    String s3Prefix = Optional.ofNullable(System.getenv("S3_PREFIX")).orElse("");
    String tableName =
        Optional.ofNullable(System.getenv("ARTIFACTS_TABLE")).orElse(DEFAULT_TABLE_NAME);
    int rowLimit = parseRowLimit(System.getenv("ROW_LIMIT"));
    String kafkaTopic =
        Optional.ofNullable(System.getenv("KAFKA_TOPIC"))
            .orElse(PropertiesHelper.getInstance().getValue("kafka_topic"));
    if (kafkaTopic == null || kafkaTopic.isBlank()) {
      throw new IllegalStateException(
          "Kafka topic not configured. Set KAFKA_TOPIC or kafka_topic.");
    }

    try (S3Client s3Client = buildS3Client();
        Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
      connection.setAutoCommit(false);
      String sql =
          String.format(
              "SELECT app_name, user_id, session_id, filename, version, mime_type, data, metadata, created_at "
                  + "FROM %s ORDER BY app_name, user_id, session_id, filename, version",
              tableName);
      if (rowLimit > 0) {
        sql = sql + " LIMIT " + rowLimit;
        logger.info("Limiting migration to {} rows.", rowLimit);
      }
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setFetchSize(500);
        int migrated = 0;
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) {
            String appName = rs.getString("app_name");
            String userId = rs.getString("user_id");
            String sessionId = rs.getString("session_id");
            String filename = rs.getString("filename");
            int version = rs.getInt("version");
            String mimeType = rs.getString("mime_type");
            byte[] data = rs.getBytes("data");
            String metadataJson = rs.getString("metadata");
            Timestamp createdAt = rs.getTimestamp("created_at");

            String key = buildObjectKey(s3Prefix, appName, userId, sessionId, filename, version);
            PutObjectRequest.Builder requestBuilder =
                PutObjectRequest.builder().bucket(bucketName).key(key);
            if (mimeType != null && !mimeType.isBlank()) {
              requestBuilder.contentType(mimeType);
            }

            try {
              s3Client.putObject(requestBuilder.build(), RequestBody.fromBytes(data));
            } catch (S3Exception e) {
              throw new RuntimeException("Failed to upload artifact to S3 for key: " + key, e);
            }

            String s3Uri = "s3://" + bucketName + "/" + key;
            JSONObject payload = new JSONObject();
            payload.put("appName", appName);
            payload.put("userId", userId);
            payload.put("sessionId", sessionId);
            payload.put("filename", filename);
            payload.put("version", version);
            if (metadataJson != null) {
              payload.put("metadata", metadataJson);
            }
            if (mimeType != null) {
              payload.put("mimeType", mimeType);
            }
            payload.put("createdAt", formatTimestamp(createdAt));
            payload.put("path", s3Uri);

            String keyForKafka =
                appName + ":" + userId + ":" + sessionId + ":" + filename + ":" + version;
            try {
              KafkaWriter.PublishWithStatus(kafkaTopic, keyForKafka, payload.toString());
            } catch (Exception e) {
              throw new RuntimeException(
                  "Failed to publish Kafka event for key: " + keyForKafka, e);
            }

            migrated++;
            if (migrated % 500 == 0) {
              logger.info("Migrated {} artifacts so far...", migrated);
            }
          }
        }
        logger.info("Migration complete. Total artifacts migrated: {}", migrated);
      }
    }
  }

  private static S3Client buildS3Client() {
    var builder = S3Client.builder();
    String region = System.getenv("S3_REGION");
    if (region != null && !region.isBlank()) {
      builder.region(Region.of(region));
    }
    String endpoint = System.getenv("S3_ENDPOINT");
    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    String pathStyle = System.getenv("S3_PATH_STYLE");
    if (pathStyle != null && Boolean.parseBoolean(pathStyle)) {

      builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }
    return builder.build();
  }

  private static String requiredEnv(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required environment variable: " + key);
    }
    return value;
  }

  private static boolean fileHasUserNamespace(String filename) {
    return filename != null && filename.startsWith("user:");
  }

  private static String buildObjectKey(
      String prefix,
      String appName,
      String userId,
      String sessionId,
      String filename,
      int version) {
    if (fileHasUserNamespace(filename)) {
      return String.format(
          "%s%s/%s/user/%s/%d", normalizePrefix(prefix), appName, userId, filename, version);
    }
    return String.format(
        "%s%s/%s/%s/%s/%d", normalizePrefix(prefix), appName, userId, sessionId, filename, version);
  }

  private static String formatTimestamp(Timestamp createdAt) {
    if (createdAt == null) {
      return Instant.EPOCH.toString();
    }
    return createdAt.toInstant().toString();
  }

  private static int parseRowLimit(String rawLimit) {
    if (rawLimit == null || rawLimit.isBlank()) {
      return 0;
    }
    try {
      return Integer.parseInt(rawLimit.trim());
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("ROW_LIMIT must be an integer.", ex);
    }
  }

  private static String normalizePrefix(String prefix) {
    if (prefix == null || prefix.isBlank()) {
      return "";
    }
    String trimmed = prefix.trim();
    if (trimmed.endsWith("/")) {
      return trimmed;
    }
    return trimmed + "/";
  }

  private PostgresToS3KafkaMigrator() {}
}
