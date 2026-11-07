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

package com.google.adk.classifiers;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.POJONode;
import com.google.adk.JsonBaseModel;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ClassifierContentTest {

  private static final String SECRET = "s3cret-payload";
  private static final ObjectMapper mapper = JsonBaseModel.getMapper();
  private static final JsonNodeFactory nodes = JsonNodeFactory.instance;

  @Test
  public void of_text_isPlainText() {
    assertThat(ClassifierContent.of("hello")).isEqualTo(new ClassifierContent.Text("hello"));
  }

  @Test
  public void text_nullValue_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> new ClassifierContent.Text(null));
  }

  @Test
  public void jsonObject_writesEveryNumberAsTheLongOrDoubleItParsesTo() {
    ObjectNode source = nodes.objectNode();
    source.put("int", 1).put("long", 2L).put("short", (short) 3).put("float", 0.1f);
    source.put("double", 0.25).put("big", new BigInteger("4294967296"));
    source.put("decimal", new BigDecimal("12.50")).put("whole", new BigDecimal("7"));
    source.put("text", "x").put("flag", true).putNull("none");

    ClassifierContent.JsonObject object = new ClassifierContent.JsonObject(source);

    assertThat(object.json().toString())
        .isEqualTo(
            "{\"int\":1,\"long\":2,\"short\":3,\"float\":0.1,\"double\":0.25,"
                + "\"big\":4294967296,\"decimal\":12.5,\"whole\":7,\"text\":\"x\","
                + "\"flag\":true,\"none\":null}");
    assertThat(object.json().get("int").isLong()).isTrue();
    assertThat(object.json().get("whole").isLong()).isTrue();
    assertThat(object.json().get("float").isDouble()).isTrue();
    assertThat(object).isEqualTo(ClassifierContent.parse(object.json().toString()));
  }

  @Test
  public void jsonObject_equalsAnotherWithTheSameValuesWhateverTheirNumberTypes() {
    ObjectNode ints = nodes.objectNode().put("n", 1).put("x", 0.5f);
    ObjectNode longs = nodes.objectNode().put("n", 1L).put("x", 0.5);

    assertThat(new ClassifierContent.JsonObject(ints))
        .isEqualTo(new ClassifierContent.JsonObject(longs));
    assertThat(new ClassifierContent.JsonObject(ints).hashCode())
        .isEqualTo(new ClassifierContent.JsonObject(longs).hashCode());
  }

  @Test
  public void jsonObject_isCopiedSoLaterChangesDoNotReachIt() {
    ObjectNode source = nodes.objectNode().put("a", 1);
    ClassifierContent.JsonObject object = new ClassifierContent.JsonObject(source);

    source.put("b", 2);
    object.json().put("c", 3);

    assertThat(object.json().size()).isEqualTo(1);
    assertThat(object.json().has("a")).isTrue();
  }

  @Test
  public void jsonArray_keepsItsElementsInOrder() {
    ArrayNode source = nodes.arrayNode().add("first").add(2).add((JsonNode) null);

    ClassifierContent.JsonArray array = new ClassifierContent.JsonArray(source);

    assertThat(array.json()).hasSize(3);
    assertThat(array.json().get(0).textValue()).isEqualTo("first");
    assertThat(array.json().get(1).longValue()).isEqualTo(2);
    assertThat(array.json().get(2).isNull()).isTrue();
    assertThat(array.toString()).isEqualTo("JsonArray[json=[\"first\",2,null]]");
  }

  @Test
  public void parse_objectAndArray_returnTheMatchingContent() {
    assertThat(ClassifierContent.parse("{\"a\": [1, 2.5]}"))
        .isEqualTo(new ClassifierContent.JsonObject(parseObject("{\"a\":[1,2.5]}")));
    assertThat(ClassifierContent.parse("[\"x\", {\"y\": null}]"))
        .isEqualTo(new ClassifierContent.JsonArray((ArrayNode) parseNode("[\"x\",{\"y\":null}]")));
  }

  @Test
  public void parse_textThatIsNotAnObjectOrArray_throwsWithoutQuotingIt() {
    for (String bad :
        new String[] {
          "\"" + SECRET + "\"", "42", "true", "null", "", "{\"a\":1} " + SECRET, "{" + SECRET
        }) {
      IllegalArgumentException thrown =
          assertThrows(IllegalArgumentException.class, () -> ClassifierContent.parse(bad));

      assertThat(thrown).hasMessageThat().startsWith("ClassifierContent must be ");
      assertThat(thrown).hasMessageThat().doesNotContain(SECRET);
    }
  }

  @Test
  public void jsonArrays_nestedToTheLimit_areAcceptedAndDeeper_throw() {
    ArrayNode atLimit = nodes.arrayNode().add("leaf");
    for (int i = 1; i < ClassifierValidation.MAX_JSON_CONTENT_DEPTH; i++) {
      atLimit = nodes.arrayNode().add(atLimit);
    }
    ObjectNode overLimit = nodes.objectNode().set("nested", atLimit);
    ArrayNode acceptable = atLimit;

    assertThat(new ClassifierContent.JsonArray(acceptable).json()).isEqualTo(acceptable);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new ClassifierContent.JsonObject(overLimit));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("JSON content exceeds maximum nesting depth of 200.");
  }

  @Test
  public void jsonObjects_nestedToTheLimit_areAcceptedAndDeeper_throw() {
    ObjectNode atLimit = nodes.objectNode().put("leaf", "value");
    for (int i = 1; i < ClassifierValidation.MAX_JSON_CONTENT_DEPTH; i++) {
      atLimit = nodes.objectNode().set("nested", atLimit);
    }
    ObjectNode overLimit = nodes.objectNode().set("nested", atLimit);
    ObjectNode acceptable = atLimit;

    assertThat(new ClassifierContent.JsonObject(acceptable).json()).isEqualTo(acceptable);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> new ClassifierContent.JsonObject(overLimit));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("JSON content exceeds maximum nesting depth of 200.");
  }

  @Test
  public void json_integerBeyondLong_throws() {
    for (ObjectNode tooBig :
        new ObjectNode[] {
          nodes.objectNode().put("n", new BigInteger("99999999999999999999")),
          nodes.objectNode().put("n", new BigDecimal("99999999999999999999"))
        }) {
      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> new ClassifierContent.JsonObject(tooBig));

      assertThat(thrown).hasMessageThat().isEqualTo("JSON content integers must fit in a long.");
    }
  }

  @Test
  public void json_nonFiniteNumber_throws() {
    for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
      ArrayNode nonFinite = nodes.arrayNode().add(bad);

      IllegalArgumentException thrown =
          assertThrows(
              IllegalArgumentException.class, () -> new ClassifierContent.JsonArray(nonFinite));

      assertThat(thrown).hasMessageThat().isEqualTo("JSON content numbers must be finite.");
    }
  }

  @Test
  public void json_valueThatIsNotJson_throwsWithoutQuotingIt() {
    ObjectNode pojo = nodes.objectNode().set("raw", new POJONode(SECRET));

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> new ClassifierContent.JsonObject(pojo));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("JSON content contains a value that is not JSON: POJO.");
  }

  private static ObjectNode parseObject(String json) {
    return (ObjectNode) parseNode(json);
  }

  private static JsonNode parseNode(String json) {
    try {
      return mapper.readTree(json);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
