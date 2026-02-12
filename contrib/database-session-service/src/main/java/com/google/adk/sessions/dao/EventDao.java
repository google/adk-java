package com.google.adk.sessions.dao;

import com.google.adk.sessions.dialect.SqlDialect;
import com.google.adk.sessions.model.EventRow;
import com.google.adk.sessions.util.JdbcTemplate.JdbcOperations;
import com.google.adk.sessions.util.RowMapper;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventDao {

  private static final Logger logger = LoggerFactory.getLogger(EventDao.class);
  private final SqlDialect dialect;

  public EventDao(SqlDialect dialect) {
    this.dialect = dialect;
    logger.debug("EventDao initialized with {} dialect", dialect.dialectName());
  }

  private static final RowMapper<EventRow> ROW_MAPPER =
      rs -> {
        EventRow row = new EventRow();
        row.setId(rs.getString("id"));
        row.setAppName(rs.getString("app_name"));
        row.setUserId(rs.getString("user_id"));
        row.setSessionId(rs.getString("session_id"));
        row.setInvocationId(rs.getString("invocation_id"));
        row.setEventData(rs.getString("event_data"));

        Timestamp ts = rs.getTimestamp("timestamp");
        row.setTimestamp(ts != null ? ts.toInstant() : null);

        return row;
      };

  public List<EventRow> listEvents(
      JdbcOperations ops, String appName, String userId, String sessionId) throws SQLException {
    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);
    params.put("sessionId", sessionId);

    String sql =
        "SELECT * FROM events "
            + "WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId "
            + "ORDER BY timestamp ASC";

    return ops.query(sql, params, ROW_MAPPER);
  }

  public List<EventRow> listEvents(
      JdbcOperations ops, String appName, String userId, String sessionId, Optional<Integer> limit)
      throws SQLException {
    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);
    params.put("sessionId", sessionId);

    String sql =
        "SELECT * FROM events "
            + "WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId "
            + "ORDER BY timestamp DESC";

    if (limit.isPresent()) {
      sql += " LIMIT :limit";
      params.put("limit", limit.get());
    }

    List<EventRow> events = ops.query(sql, params, ROW_MAPPER);
    Collections.reverse(events);
    return events;
  }

  public List<EventRow> listEventsAfterTimestamp(
      JdbcOperations ops,
      String appName,
      String userId,
      String sessionId,
      java.time.Instant afterTimestamp,
      Optional<Integer> limit,
      int offset)
      throws SQLException {
    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);
    params.put("sessionId", sessionId);
    params.put("afterTimestamp", java.sql.Timestamp.from(afterTimestamp));

    String sql =
        "SELECT * FROM events "
            + "WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId "
            + "AND timestamp > :afterTimestamp "
            + "ORDER BY timestamp ASC";

    if (limit.isPresent()) {
      sql += " LIMIT :limit OFFSET :offset";
      params.put("limit", limit.get());
      params.put("offset", offset);
    }

    return ops.query(sql, params, ROW_MAPPER);
  }

  public void insertEvent(JdbcOperations ops, EventRow event) throws SQLException {
    String sql =
        "INSERT INTO events (id, app_name, user_id, session_id, invocation_id, timestamp, event_data) "
            + "VALUES (:id, :appName, :userId, :sessionId, :invocationId, :timestamp, "
            + dialect.jsonValue(":eventData")
            + ")";

    Map<String, Object> params = new HashMap<>();
    params.put("id", event.getId());
    params.put("appName", event.getAppName());
    params.put("userId", event.getUserId());
    params.put("sessionId", event.getSessionId());
    params.put("invocationId", event.getInvocationId());
    params.put("timestamp", Timestamp.from(event.getTimestamp()));
    params.put("eventData", event.getEventData());

    logger.debug("Appending event: eventId={}, sessionId={}", event.getId(), event.getSessionId());

    ops.update(sql, params);
  }

  public long countEvents(JdbcOperations ops, String appName, String userId, String sessionId)
      throws SQLException {
    String sql =
        "SELECT COUNT(*) as count FROM events "
            + "WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId";

    Map<String, Object> params = new HashMap<>();
    params.put("appName", appName);
    params.put("userId", userId);
    params.put("sessionId", sessionId);

    return ops.queryForObject(sql, params, rs -> rs.getLong("count")).orElse(0L);
  }
}
