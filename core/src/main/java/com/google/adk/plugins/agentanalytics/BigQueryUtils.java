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
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.stream.Collectors.toCollection;

import com.google.adk.Version;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.Table;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Utility for managing BigQuery schema upgrades and analytics views. */
final class BigQueryUtils {
  private static final Logger logger = Logger.getLogger(BigQueryUtils.class.getName());

  static final String A2A_PREFIX = "a2a:";
  static final String A2A_REQUEST_KEY = "a2a:request";
  static final String A2A_RESPONSE_KEY = "a2a:response";
  static final String A2A_TASK_ID_KEY = "a2a:task_id";
  static final String A2A_CONTEXT_ID_KEY = "a2a:context_id";

  private static final ImmutableList<String> VIEW_COMMON_COLUMNS =
      ImmutableList.of(
          "timestamp",
          "event_type",
          "agent",
          "session_id",
          "invocation_id",
          "user_id",
          "trace_id",
          "span_id",
          "parent_span_id",
          "status",
          "error_message",
          "is_truncated");

  // Per-event-type column extractions. Each value is a list of ``"SQL_EXPR AS alias"`` strings that
  // will be appended after the common columns in the view SELECT.
  private static final ImmutableMap<String, ImmutableList<String>> EVENT_VIEW_DEFS =
      ImmutableMap.<String, ImmutableList<String>>builder()
          .put("USER_MESSAGE_RECEIVED", ImmutableList.of())
          .put(
              "LLM_REQUEST",
              ImmutableList.of(
                  "JSON_VALUE(attributes, '$.model') AS model",
                  "content AS request_content",
                  "JSON_QUERY(attributes, '$.llm_config') AS llm_config",
                  "JSON_QUERY(attributes, '$.tools') AS tools"))
          .put(
              "LLM_RESPONSE",
              ImmutableList.of(
                  "JSON_QUERY(content, '$.response') AS response",
                  "CAST(JSON_VALUE(content, '$.usage.prompt') AS INT64) AS usage_prompt_tokens",
                  "CAST(JSON_VALUE(content, '$.usage.completion') AS INT64) AS"
                      + " usage_completion_tokens",
                  "CAST(JSON_VALUE(content, '$.usage.total') AS INT64) AS usage_total_tokens",
                  "CAST(JSON_VALUE(attributes, '$.usage_metadata.cached_content_token_count') AS"
                      + " INT64) AS usage_cached_tokens",
                  "SAFE_DIVIDE(CAST(JSON_VALUE(attributes,"
                      + " '$.usage_metadata.cached_content_token_count') AS INT64),"
                      + "CAST(JSON_VALUE(content, '$.usage.prompt') AS INT64)) AS"
                      + " context_cache_hit_rate",
                  "CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms",
                  "CAST(JSON_VALUE(latency_ms, '$.time_to_first_token_ms') AS INT64) AS ttft_ms",
                  "JSON_VALUE(attributes, '$.model_version') AS model_version",
                  "JSON_QUERY(attributes, '$.usage_metadata') AS usage_metadata"))
          .put(
              "LLM_ERROR",
              ImmutableList.of("CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms"))
          .put(
              "TOOL_STARTING",
              ImmutableList.of(
                  "JSON_VALUE(content, '$.tool') AS tool_name",
                  "JSON_QUERY(content, '$.args') AS tool_args",
                  "JSON_VALUE(content, '$.tool_origin') AS tool_origin"))
          .put(
              "TOOL_COMPLETED",
              ImmutableList.of(
                  "JSON_VALUE(content, '$.tool') AS tool_name",
                  "JSON_QUERY(content, '$.result') AS tool_result",
                  "JSON_VALUE(content, '$.tool_origin') AS tool_origin",
                  "CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms"))
          .put(
              "TOOL_ERROR",
              ImmutableList.of(
                  "JSON_VALUE(content, '$.tool') AS tool_name",
                  "JSON_QUERY(content, '$.args') AS tool_args",
                  "JSON_VALUE(content, '$.tool_origin') AS tool_origin",
                  "CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms"))
          .put(
              "AGENT_STARTING",
              ImmutableList.of("JSON_VALUE(content, '$.text_summary') AS agent_instruction"))
          .put(
              "AGENT_COMPLETED",
              ImmutableList.of("CAST(JSON_VALUE(latency_ms, '$.total_ms') AS INT64) AS total_ms"))
          .put("INVOCATION_STARTING", ImmutableList.of())
          .put("INVOCATION_COMPLETED", ImmutableList.of())
          .put(
              "STATE_DELTA",
              ImmutableList.of("JSON_QUERY(attributes, '$.state_delta') AS state_delta"))
          .put(
              "HITL_CREDENTIAL_REQUEST",
              ImmutableList.of(
                  "JSON_VALUE(content, '$.tool') AS tool_name",
                  "JSON_QUERY(content, '$.args') AS tool_args"))
          .put(
              "HITL_CONFIRMATION_REQUEST",
              ImmutableList.of(
                  "JSON_VALUE(content, '$.tool') AS tool_name",
                  "JSON_QUERY(content, '$.args') AS tool_args"))
          .put(
              "HITL_INPUT_REQUEST",
              ImmutableList.of(
                  "JSON_VALUE(content, '$.tool') AS tool_name",
                  "JSON_QUERY(content, '$.args') AS tool_args"))
          .put(
              "A2A_INTERACTION",
              ImmutableList.of(
                  "content AS response_content",
                  "JSON_VALUE(attributes, '$.a2a_metadata.\""
                      + A2A_TASK_ID_KEY
                      + "\"') AS"
                      + " a2a_task_id",
                  "JSON_VALUE(attributes, '$.a2a_metadata.\""
                      + A2A_CONTEXT_ID_KEY
                      + "\"') AS"
                      + " a2a_context_id",
                  "JSON_QUERY(attributes, '$.a2a_metadata.\""
                      + A2A_REQUEST_KEY
                      + "\"') AS"
                      + " a2a_request"))
          .put(
              "AGENT_RESPONSE",
              ImmutableList.of(
                  "JSON_VALUE(content, '$.text_summary') AS text_summary",
                  "JSON_VALUE(attributes, '$.source_event_id') AS source_event_id",
                  "JSON_VALUE(attributes, '$.source_event_author') AS source_event_author",
                  "JSON_VALUE(attributes, '$.source_event_branch') AS source_event_branch"))
          .buildOrThrow();

  private static final String FRAMEWORK_PREFIX = "google-adk-bq-logger-java";

  /** Returns the telemetry header value. */
  static String getVersionHeaderValue() {
    return FRAMEWORK_PREFIX + "/" + Version.JAVA_ADK_VERSION;
  }

  private static final java.util.regex.Pattern SAFE_IDENTIFIER =
      java.util.regex.Pattern.compile("[A-Za-z0-9_\\-]+");

  private static boolean isSafeIdentifier(String id) {
    return id != null && SAFE_IDENTIFIER.matcher(id).matches();
  }

  /** Creates and/or replaces the analytics views in BigQuery. */
  static void createAnalyticsViews(BigQuery bigQuery, BigQueryLoggerConfig config) {
    // View DDL is assembled by string interpolation; refuse to build it if any operator-supplied
    // identifier contains characters (backticks, quotes, dots, semicolons) that could break or
    // redirect the statement.
    if (!isSafeIdentifier(config.projectId())
        || !isSafeIdentifier(config.datasetId())
        || !isSafeIdentifier(config.tableName())
        || !isSafeIdentifier(config.viewPrefix())) {
      logger.warning(
          "Skipping analytics view creation: project/dataset/table/viewPrefix contains characters"
              + " that are unsafe to interpolate into DDL.");
      return;
    }
    for (Map.Entry<String, ImmutableList<String>> entry : EVENT_VIEW_DEFS.entrySet()) {
      String eventType = entry.getKey();
      ImmutableList<String> extraCols = entry.getValue();

      String viewName = config.viewPrefix() + "_" + eventType.toLowerCase(Locale.ROOT);
      ImmutableList<String> allCols =
          ImmutableList.<String>builder().addAll(VIEW_COMMON_COLUMNS).addAll(extraCols).build();

      String columns = String.join(",\n  ", allCols);
      String sql =
          String.format(
              "CREATE OR REPLACE VIEW `%s.%s.%s` AS\nSELECT\n  %s\nFROM\n  "
                  + "`%s.%s.%s` \nWHERE\n  event_type = '%s'",
              config.projectId(),
              config.datasetId(),
              viewName,
              columns,
              config.projectId(),
              config.datasetId(),
              config.tableName(),
              eventType);

      try {
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(sql).build();
        var unused = bigQuery.query(queryConfig);
      } catch (BigQueryException | InterruptedException e) {
        logger.log(Level.WARNING, "Failed to create or update view " + viewName, e);
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  /**
   * Adds missing columns to an existing table if the actual schema is behind the desired schema.
   */
  static boolean maybeUpgradeSchema(BigQuery bigQuery, Table existingTable) {
    // Always diff the actual table schema against the desired schema rather than trusting the
    // stored version label alone: a table stamped with the current label can still be missing
    // columns (e.g. it was created by an older build), and those must be reconciled.
    SchemaDiff diff =
        schemaFieldsMatch(
            existingTable.getDefinition().getSchema().getFields(),
            BigQuerySchema.getEventsSchema().getFields());

    if (diff.newTopLevelFields().isEmpty() && diff.updatedRecordFields().isEmpty()) {
      // Nothing to reconcile; the table already satisfies the desired schema.
      return true;
    }

    {
      ImmutableMap<String, Field> updatedFields =
          diff.updatedRecordFields().stream().collect(toImmutableMap(Field::getName, f -> f));
      ImmutableSet<String> updatedNames = updatedFields.keySet();

      List<Field> mergedFields = new ArrayList<>();
      for (Field f : existingTable.getDefinition().getSchema().getFields()) {
        if (updatedNames.contains(f.getName())) {
          mergedFields.add(updatedFields.get(f.getName()));
        } else {
          mergedFields.add(f);
        }
      }
      mergedFields.addAll(diff.newTopLevelFields());

      logger.info(
          String.format(
              "Auto-upgrading table %s: new columns %s, updated RECORD fields %s",
              existingTable.getTableId(),
              diff.newTopLevelFields().stream().map(Field::getName).collect(toImmutableList()),
              diff.updatedRecordFields().stream()
                  .map(Field::getName)
                  .collect(toCollection(ArrayList::new))));

      try {
        Map<String, String> labels =
            new HashMap<>(Optional.ofNullable(existingTable.getLabels()).orElse(ImmutableMap.of()));
        labels.put(BigQuerySchema.SCHEMA_VERSION_LABEL_KEY, BigQuerySchema.SCHEMA_VERSION);

        Table updatedTable =
            existingTable.toBuilder()
                .setDefinition(
                    existingTable.getDefinition().toBuilder()
                        .setSchema(Schema.of(mergedFields))
                        .build())
                .setLabels(labels)
                .build();

        var unused = bigQuery.update(updatedTable);
        return true;
      } catch (BigQueryException e) {
        logger.log(
            Level.WARNING, "Schema auto-upgrade failed for " + existingTable.getTableId(), e);
        return false;
      }
    }
  }

  private static SchemaDiff schemaFieldsMatch(FieldList existing, FieldList desired) {
    ImmutableMap<String, Field> existingByName =
        existing == null
            ? ImmutableMap.of()
            : existing.stream().collect(toImmutableMap(Field::getName, f -> f));
    List<Field> newFields = new ArrayList<>();
    List<Field> updatedRecords = new ArrayList<>();

    for (Field desiredField : desired) {
      Field existingField = existingByName.get(desiredField.getName());
      if (existingField == null) {
        newFields.add(desiredField);
      } else if (desiredField.getType().getStandardType().equals(StandardSQLTypeName.STRUCT)
          && existingField.getType().getStandardType().equals(StandardSQLTypeName.STRUCT)
          && desiredField.getSubFields() != null) {
        // Mode drift on the STRUCT column itself (e.g. NULLABLE vs REPEATED) is just as
        // un-upgradeable as on a scalar; check it before recursing into subfields.
        warnOnIncompatibleDrift(existingField, desiredField);

        SchemaDiff subDiff =
            schemaFieldsMatch(existingField.getSubFields(), desiredField.getSubFields());

        if (!subDiff.newTopLevelFields().isEmpty() || !subDiff.updatedRecordFields().isEmpty()) {
          List<Field> mergedSub = new ArrayList<>(existingField.getSubFields());
          ImmutableMap<String, Field> updatedSubFields =
              subDiff.updatedRecordFields().stream()
                  .collect(toImmutableMap(Field::getName, f -> f));

          for (int i = 0; i < mergedSub.size(); i++) {
            Field f = mergedSub.get(i);
            if (updatedSubFields.containsKey(f.getName())) {
              mergedSub.set(i, updatedSubFields.get(f.getName()));
            }
          }
          mergedSub.addAll(subDiff.newTopLevelFields());
          updatedRecords.add(
              existingField.toBuilder()
                  .setType(StandardSQLTypeName.STRUCT, FieldList.of(mergedSub))
                  .build());
        }
      } else {
        warnOnIncompatibleDrift(existingField, desiredField);
      }
    }
    return new SchemaDiff(ImmutableList.copyOf(newFields), ImmutableList.copyOf(updatedRecords));
  }

  // Additive auto-upgrade cannot reconcile a type or mode change on an existing column
  // (including nested non-STRUCT fields, since schemaFieldsMatch recurses into STRUCTs). Surface
  // it instead of silently ignoring it, since it otherwise appears later as opaque Storage
  // Write append failures.
  private static void warnOnIncompatibleDrift(Field existingField, Field desiredField) {
    boolean typeDrift =
        !desiredField.getType().getStandardType().equals(existingField.getType().getStandardType());
    boolean modeDrift = !modesEqual(existingField.getMode(), desiredField.getMode());
    if (typeDrift || modeDrift) {
      logger.warning(
          String.format(
              "Incompatible schema drift on column '%s': table has %s/%s but the plugin expects"
                  + " %s/%s. This cannot be auto-upgraded; writes may fail until the column is"
                  + " fixed manually.",
              desiredField.getName(),
              existingField.getType().getStandardType(),
              normalizeMode(existingField.getMode()),
              desiredField.getType().getStandardType(),
              normalizeMode(desiredField.getMode())));
    }
  }

  // BigQuery leaves Field.getMode() null to mean NULLABLE; normalize before comparing.
  private static Field.Mode normalizeMode(Field.Mode mode) {
    return mode == null ? Field.Mode.NULLABLE : mode;
  }

  private static boolean modesEqual(Field.Mode a, Field.Mode b) {
    return normalizeMode(a) == normalizeMode(b);
  }

  private record SchemaDiff(
      ImmutableList<Field> newTopLevelFields, ImmutableList<Field> updatedRecordFields) {}

  private BigQueryUtils() {}
}
