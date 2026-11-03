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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ClassifierInputTest {

  @Test
  public void of_eachKindOfInput_equalsItsRecord() {
    Content content = Content.fromParts(Part.fromText("hello"));
    ImmutableMap<String, Object> jsonMap =
        ImmutableMap.of("ticket", "double charge", "tags", Arrays.asList("billing", 2, true, null));
    ImmutableList<Object> jsonList = ImmutableList.of("first", ImmutableMap.of("score", 0.5f));

    assertThat(ClassifierInput.of("hello")).isEqualTo(new ClassifierInput.Text("hello"));
    assertThat(ClassifierInput.of(jsonMap)).isEqualTo(new ClassifierInput.JsonObject(jsonMap));
    assertThat(ClassifierInput.of(jsonList)).isEqualTo(new ClassifierInput.JsonArray(jsonList));
    assertThat(ClassifierInput.of(content)).isEqualTo(new ClassifierInput.Content(content));
  }

  @Test
  public void of_typedJsonMapAndList_areAccepted() {
    ImmutableMap<String, String> typedMap = ImmutableMap.of("ticket", "refund");
    ImmutableList<String> typedList = ImmutableList.of("low", "high");

    assertThat(((ClassifierInput.JsonObject) ClassifierInput.of(typedMap)).value())
        .containsExactlyEntriesIn(typedMap);
    assertThat(((ClassifierInput.JsonArray) ClassifierInput.of(typedList)).value())
        .containsExactlyElementsIn(typedList)
        .inOrder();
  }

  @Test
  public void jsonObject_keepsAnUnmodifiableDeepCopy() {
    List<Object> tags = new ArrayList<>(ImmutableList.of("billing"));
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("tags", tags);

    ClassifierInput.JsonObject input = new ClassifierInput.JsonObject(source);
    tags.add("urgent");
    source.put("extra", 1);

    Map<String, Object> value = input.value();
    List<?> copiedTags = (List<?>) value.get("tags");
    assertThat(value).containsExactly("tags", ImmutableList.of("billing"));
    assertThrows(UnsupportedOperationException.class, () -> value.put("extra", 1));
    assertThrows(UnsupportedOperationException.class, copiedTags::clear);
  }

  @Test
  public void jsonInputs_valuesThatAreNotJson_throwIllegalArgumentException() {
    ImmutableMap<String, Double> notFinite = ImmutableMap.of("nan", Double.NaN);
    ImmutableList<byte[]> notJson = ImmutableList.of(new byte[] {1, 2});
    Map<String, Object> nonStringKey = rawMap(1, "value");

    assertThrows(IllegalArgumentException.class, () -> ClassifierInput.of(notFinite));
    assertThrows(IllegalArgumentException.class, () -> ClassifierInput.of(notJson));
    assertThrows(
        IllegalArgumentException.class, () -> new ClassifierInput.JsonObject(nonStringKey));
  }

  @Test
  public void textAndContent_nullValue_throwNullPointerException() {
    assertThrows(NullPointerException.class, () -> new ClassifierInput.Text(null));
    assertThrows(NullPointerException.class, () -> new ClassifierInput.Content(null));
  }

  // A raw map bypasses the declared String key type, as a caller with an unchecked map can.
  @SuppressWarnings({"rawtypes", "unchecked"})
  private static Map<String, Object> rawMap(Object key, Object value) {
    return (Map) ImmutableMap.of(key, value);
  }
}
