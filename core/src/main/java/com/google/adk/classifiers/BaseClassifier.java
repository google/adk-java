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
import com.google.adk.models.BaseLlm;
import io.reactivex.rxjava3.core.Single;

/**
 * Base class for decision models, which answer typed questions with probability distributions.
 *
 * <p>Unlike a {@link BaseLlm}, a classifier does not generate text, stream, or call tools.
 * Implementations are stateless and safe for concurrent calls, so a request must include any
 * context from earlier turns in its input.
 */
@Experimental
public abstract class BaseClassifier {

  private final String model;

  /** Creates a classifier backed by the provider's model {@code model}. */
  protected BaseClassifier(String model) {
    this.model = checkNotNull(model);
  }

  /** Returns the provider's model name, such as {@code "gemini-3.8-flash"}. */
  public final String model() {
    return model;
  }

  /**
   * Answers every question in {@code request} about its input, with one {@link Answer} under each
   * question ID. Every implementation accepts {@link ClassifierInput.Text}, {@link
   * ClassifierInput.JsonObject}, {@link ClassifierInput.JsonArray}, and text-only {@link
   * ClassifierInput.Content}; for input its model does not support, such as images, the returned
   * {@code Single} fails with {@link IllegalArgumentException}.
   */
  public abstract Single<ClassifierResponse> classify(ClassifyRequest request);
}
