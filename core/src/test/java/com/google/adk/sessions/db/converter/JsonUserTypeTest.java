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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Clob;
import java.sql.ResultSet;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

public class JsonUserTypeTest {

  private JsonUserType jsonUserType;

  @BeforeEach
  public void setUp() {
    jsonUserType = new JsonUserType();
  }

  @Test
  public void testNullSafeGet_PostgreSQL_PGobject() throws Exception {
    PGobject pgObject = new PGobject();
    pgObject.setType("jsonb");
    pgObject.setValue("{\"key\":\"value\"}");

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject(0)).thenReturn(pgObject);

    Map<String, Object> result = (Map<String, Object>) jsonUserType.nullSafeGet(rs, 0, null, null);

    assertNotNull(result);
    assertEquals("value", result.get("key"));
  }

  @Test
  public void testNullSafeGet_MySQL_String() throws Exception {
    String jsonString = "{\"key\":\"value\",\"number\":123}";

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject(0)).thenReturn(jsonString);

    Map<String, Object> result = (Map<String, Object>) jsonUserType.nullSafeGet(rs, 0, null, null);

    assertNotNull(result);
    assertEquals("value", result.get("key"));
    assertEquals(123, result.get("number"));
  }

  @Test
  public void testNullSafeGet_H2_Clob() throws Exception {
    String jsonString = "{\"key\":\"value\"}";
    Clob clob = mock(Clob.class);
    when(clob.length()).thenReturn((long) jsonString.length());
    when(clob.getSubString(1, jsonString.length())).thenReturn(jsonString);

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject(0)).thenReturn(clob);

    Map<String, Object> result = (Map<String, Object>) jsonUserType.nullSafeGet(rs, 0, null, null);

    assertNotNull(result);
    assertEquals("value", result.get("key"));
  }

  @Test
  public void testNullSafeGet_EmptyJson() throws Exception {
    String emptyJson = "{}";

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject(0)).thenReturn(emptyJson);

    Map<String, Object> result = (Map<String, Object>) jsonUserType.nullSafeGet(rs, 0, null, null);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testNullSafeGet_Null() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject(0)).thenReturn(null);

    Map<String, Object> result = (Map<String, Object>) jsonUserType.nullSafeGet(rs, 0, null, null);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testNullSafeGet_ComplexNestedJson() throws Exception {
    String complexJson = "{\"level1\":{\"level2\":{\"level3\":\"deep\"}},\"array\":[1,2,3]}";

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject(0)).thenReturn(complexJson);

    Map<String, Object> result = (Map<String, Object>) jsonUserType.nullSafeGet(rs, 0, null, null);

    assertNotNull(result);
    assertTrue(result.containsKey("level1"));
    assertTrue(result.containsKey("array"));
  }

  @Test
  public void testNullSafeGet_EmptyClob() throws Exception {
    Clob clob = mock(Clob.class);
    when(clob.length()).thenReturn(0L);
    when(clob.getSubString(1, 0)).thenReturn("");

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject(0)).thenReturn(clob);

    Map<String, Object> result = (Map<String, Object>) jsonUserType.nullSafeGet(rs, 0, null, null);

    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testNullSafeGet_JsonWithSpecialCharacters() throws Exception {
    String jsonWithSpecial = "{\"emoji\":\"🚀\",\"quote\":\"He said \\\"hello\\\"\"}";

    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject(0)).thenReturn(jsonWithSpecial);

    Map<String, Object> result = (Map<String, Object>) jsonUserType.nullSafeGet(rs, 0, null, null);

    assertNotNull(result);
    assertEquals("🚀", result.get("emoji"));
  }
}
