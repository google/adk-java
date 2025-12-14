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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsonUserType implements UserType<Map<String, Object>> {

  private static final Logger logger = LoggerFactory.getLogger(JsonUserType.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static {
    MAPPER.findAndRegisterModules();
  }

  @Override
  public int getSqlType() {
    return Types.OTHER;
  }

  @Override
  public Class<Map<String, Object>> returnedClass() {
    return (Class) Map.class;
  }

  @Override
  public boolean equals(Map<String, Object> x, Map<String, Object> y) {
    if (x == y) {
      return true;
    }
    if (x == null || y == null) {
      return false;
    }
    return x.equals(y);
  }

  @Override
  public int hashCode(Map<String, Object> x) {
    return x == null ? 0 : x.hashCode();
  }

  @Override
  public Map<String, Object> nullSafeGet(
      ResultSet rs, int position, SharedSessionContractImplementor session, Object owner)
      throws SQLException {

    Object obj = rs.getObject(position);
    if (obj == null) {
      return new HashMap<>();
    }

    try {
      if (obj instanceof org.postgresql.util.PGobject) {
        String json = ((org.postgresql.util.PGobject) obj).getValue();
        if (json == null || json.isEmpty()) {
          return new HashMap<>();
        }
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
      } else if (obj instanceof String) {
        String json = (String) obj;
        if (json.isEmpty()) {
          return new HashMap<>();
        }
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
      } else if (obj instanceof java.sql.Clob) {
        java.sql.Clob clob = (java.sql.Clob) obj;
        String json = clob.getSubString(1, (int) clob.length());
        if (json == null || json.isEmpty()) {
          return new HashMap<>();
        }
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
      } else {
        logger.warn("Unexpected type from database: {}", obj.getClass().getName());
        return new HashMap<>();
      }
    } catch (Exception e) {
      logger.error("Error deserializing JSON from database", e);
      return new HashMap<>();
    }
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st,
      Map<String, Object> value,
      int index,
      SharedSessionContractImplementor session)
      throws SQLException {

    if (value == null || value.isEmpty()) {
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
      logger.error("Error serializing JSON to database", e);
      throw new SQLException("Failed to convert Map to JSON", e);
    }
  }

  @Override
  public Map<String, Object> deepCopy(Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    try {
      String json = MAPPER.writeValueAsString(value);
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new HibernateException("Failed to deep copy map state", e);
    }
  }

  @Override
  public boolean isMutable() {
    return true;
  }

  @Override
  public Serializable disassemble(Map<String, Object> value) {
    return (Serializable) deepCopy(value);
  }

  @Override
  public Map<String, Object> assemble(Serializable cached, Object owner) {
    return (Map<String, Object>) cached;
  }

  @Override
  public Map<String, Object> replace(
      Map<String, Object> detached, Map<String, Object> managed, Object owner) {
    return deepCopy(detached);
  }
}
