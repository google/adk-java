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
package com.google.adk.sessions;

import com.google.adk.sessions.DatabaseSessionService.Dialect;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * UTC wall times in the common SQL range, without the JVM or connection time zone.
 *
 * <p>The time columns have no time zone, as in the Python v1 schema, and hold UTC by convention. An
 * {@link Instant} is therefore written as the date and time it has in UTC, through {@link
 * LocalDateTime}. A {@code java.sql.Timestamp} would not do: drivers convert it using the time zone
 * of the JVM or of the connection, and the stored value would then depend on where the application
 * runs.
 *
 * <p>Precision is microseconds, the finest that all four engines keep.
 */
final class DatabaseTime {
  /** Earliest time every engine stores; MySQL's {@code DATETIME} starts at the year 1000. */
  static final Instant MIN = Instant.parse("1000-01-01T00:00:00Z");

  /** Latest time every engine stores, at the last microsecond of the year 9999. */
  static final Instant MAX = Instant.parse("9999-12-31T23:59:59.999999Z");

  /**
   * SQLite has no time type, so times are text. All six fraction digits are always written: text of
   * one fixed width sorts and compares in the order of the times it stands for, which the {@code
   * ORDER BY} and {@code >=} of the store rely on. It is also the form in which SQLAlchemy, and
   * with it the Python service, writes its SQLite times.
   */
  private static final DateTimeFormatter SQLITE_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS", Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);

  private DatabaseTime() {}

  /**
   * Returns the time as it will be stored, cut to microseconds, so that a caller holds the same
   * value that a later read returns.
   *
   * @throws SessionException if the time is {@code null} or outside {@link #MIN} to {@link #MAX}
   */
  static Instant forStorage(@Nullable Instant time) {
    if (time == null || time.isBefore(MIN) || time.truncatedTo(ChronoUnit.MICROS).isAfter(MAX)) {
      throw new SessionException("Database session times must be in UTC years 1000 through 9999.");
    }
    return time.truncatedTo(ChronoUnit.MICROS);
  }

  /**
   * Rounds a time up to the next microsecond unless it is already on one.
   *
   * <p>For a lower bound of a query. Stored times are whole microseconds, so "at or after the
   * cursor" is the same set of rows as "at or after the cursor rounded up", while rounding down
   * would let in a row from just before the cursor.
   */
  static Instant ceilToMicros(Instant cursor) {
    Instant floor = cursor.truncatedTo(ChronoUnit.MICROS);
    return floor.equals(cursor) ? floor : floor.plus(1, ChronoUnit.MICROS);
  }

  /** Binds a time as its UTC date and time: as text for SQLite, as a zone-free value otherwise. */
  static void bind(PreparedStatement statement, int index, Instant time, Dialect dialect)
      throws SQLException {
    LocalDateTime utc = LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    if (dialect == Dialect.SQLITE) {
      statement.setString(index, SQLITE_FORMAT.format(utc));
    } else {
      statement.setObject(index, utc);
    }
  }

  /** Reads a time column, taking the stored date and time to be UTC. */
  static Instant read(ResultSet rows, String column, Dialect dialect) throws SQLException {
    LocalDateTime utc =
        dialect == Dialect.SQLITE
            ? LocalDateTime.parse(rows.getString(column), SQLITE_FORMAT)
            : rows.getObject(column, LocalDateTime.class);
    return utc.toInstant(ZoneOffset.UTC);
  }
}
