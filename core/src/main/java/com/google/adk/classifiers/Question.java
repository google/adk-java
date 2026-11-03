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

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.adk.JsonBaseModel;
import com.google.adk.annotations.Experimental;
import java.util.Optional;

/**
 * A typed question that a {@link BaseClassifier} answers: a {@link Choice}, a {@link Binary}, or a
 * {@link Score}.
 *
 * <p>A question's instructions and criteria hold an unmodifiable copy of JSON content: a {@code
 * String}, or a {@code Map} with string keys or a {@code List}, whose values are strings, booleans,
 * finite numbers, maps, lists, or null.
 *
 * <p>The ADK object mapper ({@link JsonBaseModel#getMapper()}) reads and writes questions in Python
 * ADK's JSON form, which names each kind in a {@code type} field.
 */
@Experimental
@JsonSerialize(using = ClassifierJson.QuestionSerializer.class)
@JsonDeserialize(using = ClassifierJson.QuestionDeserializer.class)
public sealed interface Question permits Choice, Binary, Score {

  /** Returns what to decide, as text or JSON content, if set. */
  Optional<Object> instructions();
}
