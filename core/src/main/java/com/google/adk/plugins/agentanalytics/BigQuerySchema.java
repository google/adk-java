/*
 * Copyright 2026 Google LLC
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

package com.google.adk.plugins.agentanalytics;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.storage.v1.TableFieldSchema;
import com.google.cloud.bigquery.storage.v1.TableFieldSchema.Mode;
import com.google.cloud.bigquery.storage.v1.TableSchema;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import org.apache.arrow.vector.ipc.WriteChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.FieldType;

/** Utility for defining the BigQuery events table schema. */
public final class BigQuerySchema {

  private BigQuerySchema() {}

  /**
   * The version of the BigQuery schema. Each time the schema is changed(new fields are added), this
   * should be incremented.
   */
  static final String SCHEMA_VERSION = "1";

  static final String SCHEMA_VERSION_LABEL_KEY = "adk_schema_version";

  private static final ImmutableMap<StandardSQLTypeName, ImmutableMap<String, String>>
      FIELD_TYPE_TO_ARROW_FIELD_METADATA =
          ImmutableMap.of(
              StandardSQLTypeName.JSON,
              ImmutableMap.of("ARROW:extension:name", "google:sqlType:json"),
              StandardSQLTypeName.DATETIME,
              ImmutableMap.of("ARROW:extension:name", "google:sqlType:datetime"),
              StandardSQLTypeName.GEOGRAPHY,
              ImmutableMap.of(
                  "ARROW:extension:name",
                  "google:sqlType:geography",
                  "ARROW:extension:metadata",
                  "{\"encoding\": \"WKT\"}"));

  /** Returns the BigQuery schema for the events table. */
  // TODO(b/491848381): Rely on the same schema defined for python plugin.
  public static Schema getEventsSchema() {
    return Schema.of(
        Field.newBuilder("timestamp", StandardSQLTypeName.TIMESTAMP)
            .setMode(Field.Mode.REQUIRED)
            .setDescription("The UTC timestamp when the event occurred.")
            .build(),
        Field.newBuilder("event_type", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("The category of the event.")
            .build(),
        Field.newBuilder("agent", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("The name of the agent that generated this event.")
            .build(),
        Field.newBuilder("session_id", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("A unique identifier for the entire conversation session.")
            .build(),
        Field.newBuilder("invocation_id", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("A unique identifier for a single turn or execution.")
            .build(),
        Field.newBuilder("user_id", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("The identifier of the end-user.")
            .build(),
        Field.newBuilder("trace_id", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("OpenTelemetry trace ID.")
            .build(),
        Field.newBuilder("span_id", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("OpenTelemetry span ID.")
            .build(),
        Field.newBuilder("parent_span_id", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("OpenTelemetry parent span ID.")
            .build(),
        Field.newBuilder("content", StandardSQLTypeName.JSON)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("The primary payload of the event.")
            .build(),
        Field.newBuilder(
                "content_parts",
                StandardSQLTypeName.STRUCT,
                FieldList.of(
                    Field.newBuilder("mime_type", StandardSQLTypeName.STRING)
                        .setMode(Field.Mode.NULLABLE)
                        .setDescription("The MIME type of the content part.")
                        .build(),
                    Field.newBuilder("uri", StandardSQLTypeName.STRING)
                        .setMode(Field.Mode.NULLABLE)
                        .setDescription("The URI of the content part if stored externally.")
                        .build(),
                    Field.newBuilder(
                            "object_ref",
                            StandardSQLTypeName.STRUCT,
                            FieldList.of(
                                Field.newBuilder("uri", StandardSQLTypeName.STRING)
                                    .setMode(Field.Mode.NULLABLE)
                                    .build(),
                                Field.newBuilder("version", StandardSQLTypeName.STRING)
                                    .setMode(Field.Mode.NULLABLE)
                                    .build(),
                                Field.newBuilder("authorizer", StandardSQLTypeName.STRING)
                                    .setMode(Field.Mode.NULLABLE)
                                    .build(),
                                Field.newBuilder("details", StandardSQLTypeName.JSON)
                                    .setMode(Field.Mode.NULLABLE)
                                    .build()))
                        .setMode(Field.Mode.NULLABLE)
                        .setDescription("The ObjectRef of the content part if stored externally.")
                        .build(),
                    Field.newBuilder("text", StandardSQLTypeName.STRING)
                        .setMode(Field.Mode.NULLABLE)
                        .setDescription("The raw text content.")
                        .build(),
                    Field.newBuilder("part_index", StandardSQLTypeName.INT64)
                        .setMode(Field.Mode.NULLABLE)
                        .setDescription("The zero-based index of this part.")
                        .build(),
                    Field.newBuilder("part_attributes", StandardSQLTypeName.STRING)
                        .setMode(Field.Mode.NULLABLE)
                        .setDescription("Additional metadata as a JSON object string.")
                        .build(),
                    Field.newBuilder("storage_mode", StandardSQLTypeName.STRING)
                        .setMode(Field.Mode.NULLABLE)
                        .setDescription("Indicates how the content part is stored.")
                        .build()))
            .setMode(Field.Mode.REPEATED)
            .setDescription("Multi-modal events content parts.")
            .build(),
        Field.newBuilder("attributes", StandardSQLTypeName.JSON)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("A JSON object containing arbitrary key-value pairs.")
            .build(),
        Field.newBuilder("latency_ms", StandardSQLTypeName.JSON)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("A JSON object containing latency measurements.")
            .build(),
        Field.newBuilder("status", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("The outcome of the event.")
            .build(),
        Field.newBuilder("error_message", StandardSQLTypeName.STRING)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("Detailed error message if the status is 'ERROR'.")
            .build(),
        Field.newBuilder("is_truncated", StandardSQLTypeName.BOOL)
            .setMode(Field.Mode.NULLABLE)
            .setDescription("Indicates if the 'content' field was truncated.")
            .build());
  }

  /** Returns the Arrow schema for the events table. */
  public static org.apache.arrow.vector.types.pojo.Schema getArrowSchema() {
    return new org.apache.arrow.vector.types.pojo.Schema(
        getEventsSchema().getFields().stream()
            .map(BigQuerySchema::convertToArrowField)
            .collect(toImmutableList()));
  }

  /** Returns the serialized Arrow schema for the events table. */
  public static ByteString getSerializedArrowSchema() {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      MessageSerializer.serialize(new WriteChannel(Channels.newChannel(out)), getArrowSchema());
      return ByteString.copyFrom(out.toByteArray());
    } catch (IOException e) {
      throw new VerifyException("Failed to serialize arrow schema", e);
    }
  }

  private static org.apache.arrow.vector.types.pojo.Field convertToArrowField(Field field) {
    ArrowType arrowType = convertTypeToArrow(field.getType().getStandardType());
    ImmutableList<org.apache.arrow.vector.types.pojo.Field> children = null;
    if (field.getSubFields() != null) {
      children =
          field.getSubFields().stream()
              .map(BigQuerySchema::convertToArrowField)
              .collect(toImmutableList());
    }

    ImmutableMap<String, String> metadata =
        FIELD_TYPE_TO_ARROW_FIELD_METADATA.get(field.getType().getStandardType());

    FieldType fieldType =
        new FieldType(field.getMode() != Field.Mode.REQUIRED, arrowType, null, metadata);
    org.apache.arrow.vector.types.pojo.Field arrowField =
        new org.apache.arrow.vector.types.pojo.Field(field.getName(), fieldType, children);

    if (field.getMode() == Field.Mode.REPEATED) {
      return new org.apache.arrow.vector.types.pojo.Field(
          field.getName(),
          new FieldType(false, new ArrowType.List(), null),
          ImmutableList.of(
              new org.apache.arrow.vector.types.pojo.Field(
                  "element", arrowField.getFieldType(), arrowField.getChildren())));
    }
    return arrowField;
  }

  private static ArrowType convertTypeToArrow(StandardSQLTypeName type) {
    return switch (type) {
      case BOOL -> new ArrowType.Bool();
      case BYTES -> new ArrowType.Binary();
      case DATE -> new ArrowType.Date(DateUnit.DAY);
      case DATETIME ->
          // Arrow doesn't have a direct DATETIME, often mapped to Timestamp or Utf8
          new ArrowType.Utf8();
      case FLOAT64 -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
      case INT64 -> new ArrowType.Int(64, true);
      case NUMERIC, BIGNUMERIC -> new ArrowType.Decimal(38, 9, 128);
      case GEOGRAPHY, STRING, JSON -> new ArrowType.Utf8();
      case STRUCT -> new ArrowType.Struct();
      case TIME -> new ArrowType.Time(TimeUnit.MICROSECOND, 64);
      case TIMESTAMP -> new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC");
      default -> new ArrowType.Null();
    };
  }

  /** Returns names of fields to cluster by default. */
  public static ImmutableList<String> getDefaultClusteringFields() {
    return ImmutableList.of("event_type", "agent", "user_id");
  }

  /** Returns the BigQuery TableSchema for the events table (Storage Write API). */
  public static TableSchema getEventsTableSchema() {
    return convertTableSchema(getEventsSchema());
  }

  private static TableSchema convertTableSchema(Schema schema) {
    TableSchema.Builder result = TableSchema.newBuilder();
    for (int i = 0; i < schema.getFields().size(); i++) {
      result.addFields(i, convertFieldSchema(schema.getFields().get(i)));
    }
    return result.build();
  }

  private static TableFieldSchema convertFieldSchema(Field field) {
    TableFieldSchema.Builder result = TableFieldSchema.newBuilder();
    Field.Mode mode = field.getMode() != null ? field.getMode() : Field.Mode.NULLABLE;

    Mode resultMode = Mode.valueOf(mode.name());
    result.setMode(resultMode).setName(field.getName());

    StandardSQLTypeName standardType = field.getType().getStandardType();
    TableFieldSchema.Type resultType = convertType(standardType);
    result.setType(resultType);

    if (field.getDescription() != null) {
      result.setDescription(field.getDescription());
    }
    if (field.getSubFields() != null) {
      for (int i = 0; i < field.getSubFields().size(); i++) {
        result.addFields(i, convertFieldSchema(field.getSubFields().get(i)));
      }
    }
    return result.build();
  }

  private static TableFieldSchema.Type convertType(StandardSQLTypeName type) {
    return switch (type) {
      case BOOL -> TableFieldSchema.Type.BOOL;
      case BYTES -> TableFieldSchema.Type.BYTES;
      case DATE -> TableFieldSchema.Type.DATE;
      case DATETIME -> TableFieldSchema.Type.DATETIME;
      case FLOAT64 -> TableFieldSchema.Type.DOUBLE;
      case GEOGRAPHY -> TableFieldSchema.Type.GEOGRAPHY;
      case INT64 -> TableFieldSchema.Type.INT64;
      case NUMERIC -> TableFieldSchema.Type.NUMERIC;
      case STRING -> TableFieldSchema.Type.STRING;
      case STRUCT -> TableFieldSchema.Type.STRUCT;
      case TIME -> TableFieldSchema.Type.TIME;
      case TIMESTAMP -> TableFieldSchema.Type.TIMESTAMP;
      case BIGNUMERIC -> TableFieldSchema.Type.BIGNUMERIC;
      case JSON -> TableFieldSchema.Type.JSON;
      case INTERVAL -> TableFieldSchema.Type.INTERVAL;
      default -> TableFieldSchema.Type.TYPE_UNSPECIFIED;
    };
  }
}
