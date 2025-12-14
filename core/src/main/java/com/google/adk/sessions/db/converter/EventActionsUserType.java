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
package com.google.adk.sessions.db.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.EventActions;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EventActionsUserType implements UserType<EventActions> {

  private static final Logger logger = LoggerFactory.getLogger(EventActionsUserType.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.findAndRegisterModules();
  }

  @Override
  public int getSqlType() {
    return Types.OTHER;
  }

  @Override
  public Class<EventActions> returnedClass() {
    return EventActions.class;
  }

  @Override
  public boolean equals(EventActions x, EventActions y) {
    if (x == y) {
      return true;
    }
    if (x == null || y == null) {
      return false;
    }
    return x.equals(y);
  }

  @Override
  public int hashCode(EventActions x) {
    return x == null ? 0 : x.hashCode();
  }

  @Override
  public EventActions nullSafeGet(
      ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
      throws SQLException {

    Object obj = rs.getObject(position);
    if (obj == null) {
      return EventActions.builder().build();
    }

    try {
      if (obj instanceof org.postgresql.util.PGobject) {
        String json = ((org.postgresql.util.PGobject) obj).getValue();
        if (json == null || json.isEmpty()) {
          return EventActions.builder().build();
        }
        return MAPPER.readValue(json, EventActions.class);
      } else if (obj instanceof String) {
        String json = (String) obj;
        if (json.isEmpty()) {
          return EventActions.builder().build();
        }
        return MAPPER.readValue(json, EventActions.class);
      } else if (obj instanceof java.sql.Clob) {
        java.sql.Clob clob = (java.sql.Clob) obj;
        String json = clob.getSubString(1, (int) clob.length());
        if (json == null || json.isEmpty()) {
          return EventActions.builder().build();
        }
        return MAPPER.readValue(json, EventActions.class);
      } else {
        logger.warn("Unexpected type from database: {}", obj.getClass().getName());
        return EventActions.builder().build();
      }
    } catch (Exception e) {
      logger.error("Error deserializing EventActions from database", e);
      return EventActions.builder().build();
    }
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, EventActions value, int index, SharedSessionContractImplementor session)
      throws SQLException {

    if (value == null) {
      st.setNull(index, Types.OTHER);
      return;
    }

    try {
      String json = MAPPER.writeValueAsString(value);

      String databaseProductName = st.getConnection().getMetaData().getDatabaseProductName();

      if (databaseProductName.toLowerCase().contains("postgres")) {
        org.postgresql.util.PGobject pgo = new org.postgresql.util.PGobject();
        pgo.setType("jsonb");
        pgo.setValue(json);
        st.setObject(index, pgo, Types.OTHER);
      } else {
        st.setString(index, json);
      }
    } catch (Exception e) {
      logger.error("Error serializing EventActions to database", e);
      throw new SQLException("Failed to convert EventActions to JSON", e);
    }
  }

  @Override
  public EventActions deepCopy(EventActions value) {
    if (value == null) {
      return null;
    }
    try {
      String json = MAPPER.writeValueAsString(value);
      return MAPPER.readValue(json, EventActions.class);
    } catch (Exception e) {
      logger.error("Error deep copying EventActions", e);
      return value;
    }
  }

  @Override
  public boolean isMutable() {
    return true;
  }

  @Override
  public Serializable disassemble(EventActions value) {
    return (Serializable) deepCopy(value);
  }

  @Override
  public EventActions assemble(Serializable cached, Object owner) {
    return (EventActions) cached;
  }

  @Override
  public EventActions replace(EventActions detached, EventActions managed, Object owner) {
    return deepCopy(detached);
  }
}
