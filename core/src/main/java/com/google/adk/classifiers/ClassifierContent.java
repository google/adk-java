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

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.annotations.Experimental;

/**
 * Text or structured data that a {@link Classifier} reads as the input or as a question's
 * instructions or criteria: plain {@link Text}, or a {@link JsonObject} or {@link JsonArray}. It
 * mirrors Python ADK's {@code JSONContent}.
 */
@Experimental
public sealed interface ClassifierContent extends ClassifierInput {

  /** Plain text. */
  record Text(String text) implements ClassifierContent {
    public Text {
      checkNotNull(text);
    }
  }

  /**
   * A JSON object. Creating one throws {@link IllegalArgumentException} if {@code json} nests more
   * than 200 levels deep or holds a non-finite number, an integer beyond a {@code long}, or a value
   * that is not JSON.
   */
  record JsonObject(ObjectNode json) implements ClassifierContent {
    public JsonObject {
      json = (ObjectNode) ClassifierValidation.normalizedJson(json);
    }

    /**
     * Returns a copy of the object, with each number written as the long or double it parses to.
     */
    @Override
    public ObjectNode json() {
      return json.deepCopy();
    }
  }

  /**
   * A JSON array. Creating one throws {@link IllegalArgumentException} under the same conditions as
   * {@link JsonObject}.
   */
  record JsonArray(ArrayNode json) implements ClassifierContent {
    public JsonArray {
      json = (ArrayNode) ClassifierValidation.normalizedJson(json);
    }

    /** Returns a copy of the array, with each number written as the long or double it parses to. */
    @Override
    public ArrayNode json() {
      return json.deepCopy();
    }
  }

  /** Returns {@code text} as plain-text content. */
  static ClassifierContent of(String text) {
    return new Text(text);
  }

  /**
   * Parses {@code json}, the text of a JSON object or array, into a {@link JsonObject} or a {@link
   * JsonArray}.
   *
   * @throws IllegalArgumentException if {@code json} is not a JSON object or array, or fails the
   *     checks of the {@link JsonObject} or {@link JsonArray} it would create
   */
  static ClassifierContent parse(String json) {
    return ClassifierValidation.parseContent(json);
  }
}
