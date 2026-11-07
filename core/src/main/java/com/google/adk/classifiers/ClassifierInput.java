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

/**
 * What a {@link Classifier} answers questions about: {@link ClassifierContent} (text, or a JSON
 * object or array) or multimodal {@link Content} parts.
 */
@Experimental
public sealed interface ClassifierInput permits ClassifierContent, ClassifierInput.Content {

  /**
   * {@link com.google.genai.types.Content} input. Every classifier accepts text parts; a classifier
   * whose model supports them also accepts other parts, such as images or files.
   */
  record Content(com.google.genai.types.Content content) implements ClassifierInput {
    public Content {
      checkNotNull(content);
    }
  }

  /** Returns {@code text} as plain-text input. */
  static ClassifierInput of(String text) {
    return new ClassifierContent.Text(text);
  }
}
