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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.annotations.Experimental;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link BaseClassifier} answers questions about: plain {@link Text}, structured JSON as a
 * {@link JsonObject} or a {@link JsonArray}, or {@link Content} parts.
 */
@Experimental
public sealed interface ClassifierInput
    permits ClassifierInput.Text,
        ClassifierInput.JsonObject,
        ClassifierInput.JsonArray,
        ClassifierInput.Content {

  /** Plain-text input. */
  record Text(String text) implements ClassifierInput {
    public Text {
      checkNotNull(text);
    }
  }

  /**
   * A JSON object as input, held as an unmodifiable copy. Creating one throws {@link
   * IllegalArgumentException} if a value is not JSON content, as {@link Question} describes.
   */
  record JsonObject(Map<String, @Nullable Object> value) implements ClassifierInput {
    public JsonObject {
      value = ClassifierValidation.jsonObject(value);
    }
  }

  /**
   * A JSON array as input, held as an unmodifiable copy. Creating one throws {@link
   * IllegalArgumentException} if a value is not JSON content, as {@link Question} describes.
   */
  record JsonArray(List<@Nullable Object> value) implements ClassifierInput {
    public JsonArray {
      value = ClassifierValidation.jsonArray(value);
    }
  }

  /**
   * {@link com.google.genai.types.Content} input. Every classifier accepts text parts; a classifier
   * whose model supports them also accepts other parts, such as images or files.
   */
  record Content(com.google.genai.types.Content content) implements ClassifierInput {
    public Content {
      checkNotNull(content);
    }
  }

  /** Returns {@code text} as input. */
  static ClassifierInput of(String text) {
    return new Text(text);
  }

  /** Returns a copy of the JSON object {@code value} as input. */
  static ClassifierInput of(Map<String, ?> value) {
    return new JsonObject(Collections.unmodifiableMap(value));
  }

  /** Returns a copy of the JSON array {@code value} as input. */
  static ClassifierInput of(List<?> value) {
    return new JsonArray(Collections.unmodifiableList(value));
  }

  /** Returns {@code content} as input. */
  static ClassifierInput of(com.google.genai.types.Content content) {
    return new Content(content);
  }
}
