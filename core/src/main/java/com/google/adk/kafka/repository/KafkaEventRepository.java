package com.google.adk.kafka.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.kafka.model.KafkaEventData;
import com.google.adk.utils.PostgresDBHelper;
import java.sql.*;
import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository class for handling database operations related to Kafka events. This class handles the
 * persistence of session data, events, and event content parts to PostgreSQL database tables.
 */
public class KafkaEventRepository {

  private static final Logger logger = LoggerFactory.getLogger(KafkaEventRepository.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  // Table names
  private static final String SESSIONS_TABLE = "sessions";
  private static final String EVENTS_TABLE = "events";
  private static final String EVENT_CONTENT_PARTS_TABLE = "event_content_parts";

  /**
   * Upsert session data into the sessions table.
   *
   * @param eventAttributes The event attributes containing session information
   * @return true if successful, false otherwise
   */
  public boolean upsertSession(KafkaEventData.EventAttributes eventAttributes) {
    Connection conn = null;
    PreparedStatement stmt = null;

    try {
      conn = PostgresDBHelper.getInstance().getConnection();
      conn.setAutoCommit(false); // Start transaction

      String upsertSql =
          String.format(
              "INSERT INTO %s (id, app_name, user_id, state, last_update_time, event_data) "
                  + "VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb) "
                  + "ON CONFLICT (id) DO UPDATE SET "
                  + "    app_name = EXCLUDED.app_name, "
                  + "    user_id = EXCLUDED.user_id, "
                  + "    state = EXCLUDED.state, "
                  + "    last_update_time = EXCLUDED.last_update_time, "
                  + "    event_data = EXCLUDED.event_data",
              SESSIONS_TABLE);

      stmt = conn.prepareStatement(upsertSql);

      // Convert state to JSON string
      String stateJson = "{}";
      if (eventAttributes.getState() != null) {
        try {
          stateJson = objectMapper.writeValueAsString(eventAttributes.getState());
        } catch (JsonProcessingException e) {
          logger.warn("Failed to marshal state to JSON, using empty object: {}", e.getMessage());
        }
      }

      // Convert LastUpdateTime to Timestamp
      Timestamp lastUpdateTime = convertToTimestamp(eventAttributes.getLastUpdateTime());

      stmt.setString(1, eventAttributes.getId());
      stmt.setString(2, eventAttributes.getAppName());
      stmt.setString(3, eventAttributes.getUserId());
      stmt.setString(4, stateJson);
      stmt.setTimestamp(5, lastUpdateTime);
      stmt.setString(6, eventAttributes.getEventData());

      int rowsAffected = stmt.executeUpdate();

      conn.commit();
      logger.info(
          "Successfully upserted session to PostgreSQL, id = {}, rows affected = {}",
          eventAttributes.getId(),
          rowsAffected);

      return true;

    } catch (SQLException e) {
      logger.error("Error upserting session {}: {}", eventAttributes.getId(), e.getMessage());
      if (conn != null) {
        try {
          conn.rollback();
        } catch (SQLException rollbackEx) {
          logger.error("Error rolling back transaction: {}", rollbackEx.getMessage());
        }
      }
      return false;
    } finally {
      closeResources(stmt, conn);
    }
  }

  /**
   * Upsert event data into the events table.
   *
   * @param event The event to upsert
   * @param sessionId The session ID this event belongs to
   * @return true if successful, false otherwise
   */
  public boolean upsertEvent(KafkaEventData.Event event, String sessionId) {
    Connection conn = null;
    PreparedStatement stmt = null;

    try {
      conn = PostgresDBHelper.getInstance().getConnection();
      conn.setAutoCommit(false);

      String upsertSql =
          String.format(
              "INSERT INTO %s (id, session_id, author, actions_state_delta, actions_artifact_delta, "
                  + "actions_requested_auth_configs, actions_transfer_to_agent, content_role, timestamp, invocation_id) "
                  + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?) "
                  + "ON CONFLICT (id) DO UPDATE SET "
                  + "    session_id = EXCLUDED.session_id, "
                  + "    author = EXCLUDED.author, "
                  + "    actions_state_delta = EXCLUDED.actions_state_delta, "
                  + "    actions_artifact_delta = EXCLUDED.actions_artifact_delta, "
                  + "    actions_requested_auth_configs = EXCLUDED.actions_requested_auth_configs, "
                  + "    actions_transfer_to_agent = EXCLUDED.actions_transfer_to_agent, "
                  + "    content_role = EXCLUDED.content_role, "
                  + "    timestamp = EXCLUDED.timestamp, "
                  + "    invocation_id = EXCLUDED.invocation_id",
              EVENTS_TABLE);

      stmt = conn.prepareStatement(upsertSql);

      stmt.setString(1, event.getId());
      stmt.setString(2, sessionId);
      stmt.setString(3, event.getAuthor());
      stmt.setString(4, safeJsonString(event.getActions().getStateDelta()));
      stmt.setString(5, safeJsonString(event.getActions().getArtifactDelta()));
      stmt.setString(6, safeJsonString(event.getActions().getRequestedAuthConfigs()));
      stmt.setString(7, event.getActions().getTransferToAgent());
      stmt.setString(8, event.getContent().getRole());
      stmt.setLong(9, event.getTimestamp());
      stmt.setString(10, event.getInvocationId());

      int rowsAffected = stmt.executeUpdate();

      conn.commit();
      logger.debug(
          "Successfully upserted event {} to PostgreSQL, rows affected = {}",
          event.getId(),
          rowsAffected);

      return true;

    } catch (SQLException e) {
      logger.error("Error upserting event {}: {}", event.getId(), e.getMessage());
      if (conn != null) {
        try {
          conn.rollback();
        } catch (SQLException rollbackEx) {
          logger.error("Error rolling back transaction: {}", rollbackEx.getMessage());
        }
      }
      return false;
    } finally {
      closeResources(stmt, conn);
    }
  }

  /**
   * Upsert event content parts into the event_content_parts table.
   *
   * @param eventId The event ID these parts belong to
   * @param sessionId The session ID
   * @param parts The list of content parts
   * @return true if successful, false otherwise
   */
  public boolean upsertEventContentParts(
      String eventId, String sessionId, List<KafkaEventData.Part> parts) {
    if (parts == null || parts.isEmpty()) {
      return true; // Nothing to insert
    }

    Connection conn = null;
    PreparedStatement stmt = null;

    try {
      conn = PostgresDBHelper.getInstance().getConnection();
      conn.setAutoCommit(false);

      String upsertSql =
          String.format(
              "INSERT INTO %s (event_id, session_id, part_type, text_content, function_call_id, "
                  + "function_call_name, function_call_args, function_response_id, function_response_name, function_response_data) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb) "
                  + "ON CONFLICT (event_id) DO UPDATE SET "
                  + "    session_id = EXCLUDED.session_id, "
                  + "    part_type = EXCLUDED.part_type, "
                  + "    text_content = EXCLUDED.text_content, "
                  + "    function_call_id = EXCLUDED.function_call_id, "
                  + "    function_call_name = EXCLUDED.function_call_name, "
                  + "    function_call_args = EXCLUDED.function_call_args, "
                  + "    function_response_id = EXCLUDED.function_response_id, "
                  + "    function_response_name = EXCLUDED.function_response_name, "
                  + "    function_response_data = EXCLUDED.function_response_data",
              EVENT_CONTENT_PARTS_TABLE);

      stmt = conn.prepareStatement(upsertSql);

      for (KafkaEventData.Part part : parts) {
        String partType = part.getPartType();
        String textContent = null;
        String functionCallId = null;
        String functionCallName = null;
        String functionCallArgs = null;
        String functionResponseId = null;
        String functionResponseName = null;
        String functionResponseData = null;

        switch (partType) {
          case "text":
            textContent = part.getText();
            break;
          case "functionCall":
            if (part.getFunctionCall() != null) {
              functionCallId = part.getFunctionCall().getId();
              functionCallName = part.getFunctionCall().getName();
              functionCallArgs = part.getFunctionCall().getArgs();
            }
            break;
          case "functionResponse":
            if (part.getFunctionResponse() != null) {
              functionResponseId = part.getFunctionResponse().getId();
              functionResponseName = part.getFunctionResponse().getName();
              functionResponseData = part.getFunctionResponse().getResponse();
            }
            break;
          default:
            logger.warn("Unknown content part type for event ID: {} - {}", eventId, partType);
            continue;
        }

        stmt.setString(1, eventId);
        stmt.setString(2, sessionId);
        stmt.setString(3, partType);
        stmt.setString(4, textContent);
        stmt.setString(5, functionCallId);
        stmt.setString(6, functionCallName);
        stmt.setString(7, safeJsonString(functionCallArgs));
        stmt.setString(8, functionResponseId);
        stmt.setString(9, functionResponseName);
        stmt.setString(10, safeJsonString(functionResponseData));

        stmt.addBatch();
      }

      int[] rowsAffected = stmt.executeBatch();
      conn.commit();

      logger.debug(
          "Successfully upserted {} content parts for event {} to PostgreSQL",
          rowsAffected.length,
          eventId);

      return true;

    } catch (SQLException e) {
      logger.error("Error upserting content parts for event {}: {}", eventId, e.getMessage());
      if (conn != null) {
        try {
          conn.rollback();
        } catch (SQLException rollbackEx) {
          logger.error("Error rolling back transaction: {}", rollbackEx.getMessage());
        }
      }
      return false;
    } finally {
      closeResources(stmt, conn);
    }
  }

  /**
   * Process the complete event data by upserting session, events, and content parts.
   *
   * @param eventData The complete event data
   * @return true if successful, false otherwise
   */
  public boolean processEventData(KafkaEventData eventData) {
    try {
      // First upsert the session
      if (!upsertSession(eventData.getEventAttributes())) {
        return false;
      }

      // Parse the nested event_data JSON string
      KafkaEventData.EventsData eventsData =
          parseEventData(eventData.getEventAttributes().getEventData());
      if (eventsData == null || eventsData.getEvents() == null) {
        logger.warn(
            "No events found in event_data for session: {}",
            eventData.getEventAttributes().getId());
        return true; // Session was saved successfully
      }

      String sessionId = eventData.getEventAttributes().getId();

      // Process each event
      for (KafkaEventData.Event event : eventsData.getEvents()) {
        if (!upsertEvent(event, sessionId)) {
          return false;
        }

        // Process content parts
        if (event.getContent() != null && event.getContent().getParts() != null) {
          if (!upsertEventContentParts(event.getId(), sessionId, event.getContent().getParts())) {
            return false;
          }
        }
      }

      logger.info("Successfully processed event data for session: {}", sessionId);
      return true;

    } catch (Exception e) {
      logger.error(
          "Error processing event data for session {}: {}",
          eventData.getEventAttributes().getId(),
          e.getMessage());
      return false;
    }
  }

  /**
   * Parse the nested event_data JSON string into EventsData object. This handles the complex JSON
   * parsing logic from the Go implementation.
   *
   * @param eventDataJson The JSON string containing events
   * @return EventsData object or null if parsing fails
   */
  private KafkaEventData.EventsData parseEventData(String eventDataJson) {
    try {
      // The event_data field contains a JSON string that needs to be parsed
      // This is similar to the Go implementation's two-pass parsing

      // First, try to parse as a direct JSON object
      try {
        return objectMapper.readValue(eventDataJson, KafkaEventData.EventsData.class);
      } catch (Exception e) {
        logger.debug("Direct parsing failed, trying alternative parsing: {}", e.getMessage());
      }

      // Alternative parsing for malformed JSON (similar to Go implementation)
      // This handles cases where the JSON might be double-encoded or have escaped quotes

      // Remove outer quotes if present and unescape inner quotes
      String cleanedJson = eventDataJson;
      if (cleanedJson.startsWith("\"") && cleanedJson.endsWith("\"")) {
        cleanedJson = cleanedJson.substring(1, cleanedJson.length() - 1);
        cleanedJson = cleanedJson.replace("\\\"", "\"");
      }

      // Create a proper JSON structure
      String correctedJson = "{\"events\":" + cleanedJson + "}";

      return objectMapper.readValue(correctedJson, KafkaEventData.EventsData.class);

    } catch (Exception e) {
      logger.error("Failed to parse event_data JSON: {}", e.getMessage());
      logger.debug("Event data JSON content: {}", eventDataJson);
      return null;
    }
  }

  /**
   * Convert timestamp from milliseconds/seconds to Timestamp object.
   *
   * @param timestamp The timestamp value (could be milliseconds or seconds)
   * @return Timestamp object
   */
  private Timestamp convertToTimestamp(Double timestamp) {
    if (timestamp == null) {
      return new Timestamp(System.currentTimeMillis());
    }

    long timestampMs;
    if (timestamp > 1000000000000.0) {
      // Milliseconds
      timestampMs = timestamp.longValue();
    } else {
      // Seconds
      timestampMs = (long) (timestamp * 1000);
    }

    return new Timestamp(timestampMs);
  }

  /**
   * Safely convert an Object to JSON string, handling null/empty cases.
   *
   * @param obj The object to convert
   * @return Safe JSON string (empty object if null/empty)
   */
  private String safeJsonString(Object obj) {
    if (obj == null) {
      return "{}";
    }

    if (obj instanceof String) {
      String str = (String) obj;
      if (str.trim().isEmpty()) {
        return "{}";
      }
      return str;
    }

    // Convert object to JSON string
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (JsonProcessingException e) {
      logger.warn("Failed to convert object to JSON, using empty object: {}", e.getMessage());
      return "{}";
    }
  }

  /**
   * Safely convert a string to JSON string, handling null/empty cases.
   *
   * @param jsonString The string to convert
   * @return Safe JSON string (empty object if null/empty)
   */
  private String safeJsonString(String jsonString) {
    if (jsonString == null || jsonString.trim().isEmpty()) {
      return "{}";
    }
    return jsonString;
  }

  /**
   * Close database resources safely.
   *
   * @param stmt PreparedStatement to close
   * @param conn Connection to close
   */
  private void closeResources(PreparedStatement stmt, Connection conn) {
    if (stmt != null) {
      try {
        stmt.close();
      } catch (SQLException e) {
        logger.warn("Error closing PreparedStatement: {}", e.getMessage());
      }
    }

    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException e) {
        logger.warn("Error closing Connection: {}", e.getMessage());
      }
    }
  }
}
