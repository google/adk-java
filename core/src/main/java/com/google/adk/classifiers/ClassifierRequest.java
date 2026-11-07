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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.annotations.Experimental;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import java.util.Map;

/**
 * A request to a {@link Classifier} to answer one or more typed questions about an input.
 *
 * @param input what the questions are about
 * @param questions each caller-chosen question ID mapped to its question; the {@link
 *     ClassifierResponse} answers under the same IDs. There is at least one question, and the
 *     questions keep the map's iteration order.
 */
@Experimental
public record ClassifierRequest(ClassifierInput input, ImmutableMap<String, Question> questions) {

  public ClassifierRequest {
    checkNotNull(input);
    checkArgument(!questions.isEmpty(), "ClassifierRequest requires at least one question.");
  }

  /** Creates a request with a copy of {@code questions}. */
  public ClassifierRequest(ClassifierInput input, Map<String, ? extends Question> questions) {
    this(input, ImmutableMap.copyOf(questions));
  }

  /** Creates a request that classifies plain {@code text}. */
  public ClassifierRequest(String text, Map<String, ? extends Question> questions) {
    this(new ClassifierContent.Text(text), questions);
  }

  /** Creates a request that classifies {@code content}. */
  public ClassifierRequest(Content content, Map<String, ? extends Question> questions) {
    this(new ClassifierInput.Content(content), questions);
  }
}
