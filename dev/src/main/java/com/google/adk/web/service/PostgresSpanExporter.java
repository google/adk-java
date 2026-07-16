package com.google.adk.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A SpanExporter that writes OpenTelemetry span data to a PostgreSQL database. Stores spans in the
 * 'spans' table.
 */
public class PostgresSpanExporter implements SpanExporter {
  private static final Logger logger = LoggerFactory.getLogger(PostgresSpanExporter.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private final String dbUrl;
  private final String dbUser;
  private final String dbPassword;
  private final String serviceName;

  public PostgresSpanExporter(String dbUrl, String dbUser, String dbPassword, String serviceName) {
    this.dbUrl = dbUrl;
    this.dbUser = dbUser;
    this.dbPassword = dbPassword;
    this.serviceName = serviceName;
    logger.info("PostgresSpanExporter initialized for service: {}", serviceName);
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    if (spans == null || spans.isEmpty()) {
      return CompletableResultCode.ofSuccess();
    }

    logger.debug("Exporting {} spans to PostgreSQL", spans.size());

    try (Connection conn = getConnection()) {
      conn.setAutoCommit(false);

      String insertSql =
          "INSERT INTO spans ("
              + "trace_id, span_id, parent_span_id, user_id, name, "
              + "start_time, end_time, duration, "
              + "gen_ai_request_model, event_id, "
              + "status_code, status_message, "
              + "attributes"
              + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
              + "ON CONFLICT (trace_id, span_id) DO UPDATE SET "
              + "parent_span_id = EXCLUDED.parent_span_id, "
              + "user_id = EXCLUDED.user_id, "
              + "name = EXCLUDED.name, "
              + "start_time = EXCLUDED.start_time, "
              + "end_time = EXCLUDED.end_time, "
              + "duration = EXCLUDED.duration, "
              + "gen_ai_request_model = EXCLUDED.gen_ai_request_model, "
              + "event_id = EXCLUDED.event_id, "
              + "status_code = EXCLUDED.status_code, "
              + "status_message = EXCLUDED.status_message, "
              + "attributes = EXCLUDED.attributes";

      try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
        for (SpanData span : spans) {
          try {
            setSpanParameters(pstmt, span);
            pstmt.addBatch();
          } catch (Exception e) {
            logger.error("Error preparing span for batch insert: {}", span.getName(), e);
          }
        }

        int[] results = pstmt.executeBatch();
        conn.commit();

        int successCount = 0;
        for (int result : results) {
          if (result >= 0 || result == PreparedStatement.SUCCESS_NO_INFO) {
            successCount++;
          }
        }

        logger.debug("Successfully exported {}/{} spans to PostgreSQL", successCount, spans.size());
        return CompletableResultCode.ofSuccess();

      } catch (SQLException e) {
        conn.rollback();
        logger.error("Error executing batch insert for spans", e);
        return CompletableResultCode.ofFailure();
      }

    } catch (SQLException e) {
      logger.error("Error connecting to PostgreSQL database", e);
      return CompletableResultCode.ofFailure();
    }
  }

  private void setSpanParameters(PreparedStatement pstmt, SpanData span) throws SQLException {
    Attributes attributes = span.getAttributes();

    // Extract common attributes
    String sessionId = getStringAttribute(attributes, "gcp.vertex.agent.session_id");
    String genAiRequestModel = getStringAttribute(attributes, "gen_ai.request.model");
    String eventId = getStringAttribute(attributes, "gcp.vertex.agent.event_id");

    // Convert attributes to JSON
    String attributesJson = convertAttributesToJson(attributes);

    // Get parent span ID (may be invalid)
    String parentSpanId = span.getParentSpanId();
    if (!io.opentelemetry.api.trace.SpanId.isValid(parentSpanId)) {
      parentSpanId = null;
    }

    // Convert timestamps
    long startTimeNanos = span.getStartEpochNanos();
    long endTimeNanos = span.getEndEpochNanos();

    // Calculate duration in seconds (rounded to 2 decimal places)
    double durationSeconds =
        Math.round((endTimeNanos - startTimeNanos) / 1_000_000_000.0 * 100.0) / 100.0;

    // Get status
    StatusData status = span.getStatus();
    String statusCode = status.getStatusCode().name();
    String statusMessage = status.getDescription();

    // Set parameters
    int paramIndex = 1;
    pstmt.setString(paramIndex++, span.getSpanContext().getTraceId()); // trace_id
    pstmt.setString(paramIndex++, span.getSpanContext().getSpanId()); // span_id
    pstmt.setString(paramIndex++, parentSpanId); // parent_span_id
    pstmt.setString(paramIndex++, sessionId); // session_id
    pstmt.setString(paramIndex++, span.getName()); // name
    pstmt.setLong(paramIndex++, startTimeNanos); // start_time_nanos
    pstmt.setLong(paramIndex++, endTimeNanos); // end_time_nanos
    pstmt.setDouble(paramIndex++, durationSeconds); // duration (in seconds)
    pstmt.setString(paramIndex++, genAiRequestModel); // gen_ai.request.model
    pstmt.setString(paramIndex++, eventId); // event_id
    pstmt.setString(paramIndex++, statusCode); // status_code
    pstmt.setString(paramIndex++, statusMessage); // status_message
    pstmt.setString(paramIndex++, attributesJson); // attributes (JSONB)
  }

  private String determineSpanType(String spanName) {
    if (spanName == null) {
      return "other";
    }
    if (spanName.startsWith("invocation")) {
      return "invocation";
    } else if (spanName.startsWith("agent_run")) {
      return "agent_run";
    } else if (spanName.equals("call_llm")) {
      return "call_llm";
    } else if (spanName.startsWith("tool_call")) {
      return "tool_call";
    } else if (spanName.startsWith("tool_response")) {
      return "tool_response";
    } else if (spanName.equals("send_data")) {
      return "send_data";
    }
    return "other";
  }

  private String getStringAttribute(Attributes attributes, String key) {
    Object value = attributes.get(AttributeKey.stringKey(key));
    return value != null ? value.toString() : null;
  }

  private String convertAttributesToJson(Attributes attributes) {
    try {
      Map<String, Object> attributeMap = new HashMap<>();
      attributes.forEach(
          (key, value) -> {
            attributeMap.put(key.getKey(), value);
          });
      return objectMapper.writeValueAsString(attributeMap);
    } catch (Exception e) {
      logger.warn("Error converting attributes to JSON", e);
      return "{}";
    }
  }

  private Connection getConnection() throws SQLException {
    DriverManager.setLoginTimeout(10);
    Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
    if (conn == null) {
      throw new SQLException("Failed to establish a connection to the database.");
    }
    return conn;
  }

  @Override
  public CompletableResultCode flush() {
    logger.debug("Flush called on PostgresSpanExporter");
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    logger.info("Shutting down PostgresSpanExporter");
    return CompletableResultCode.ofSuccess();
  }
}
