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
import com.google.adk.annotations.Experimental;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The answers that one {@link BaseClassifier#classify} call produced. The ADK object mapper reads
 * and writes a response in Python ADK's JSON form.
 */
@Experimental
@AutoValue
@JsonSerialize(using = ClassifierJson.ResponseSerializer.class)
@JsonDeserialize(using = ClassifierJson.ResponseDeserializer.class)
public abstract class ClassifierResponse {

  ClassifierResponse() {}

  /** Returns each question ID of the {@link ClassifyRequest} mapped to its answer. */
  public abstract ImmutableMap<String, Answer> answers();

  /** Returns the concrete model version that the provider reported, if any. */
  public abstract Optional<String> modelVersion();

  public static Builder builder() {
    return new AutoValue_ClassifierResponse.Builder();
  }

  public abstract Builder toBuilder();

  /** Builder for {@link ClassifierResponse}. Only the answers are required. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder answers(Map<String, ? extends Answer> answers);

    @CanIgnoreReturnValue
    public abstract Builder modelVersion(@Nullable String modelVersion);

    public abstract ClassifierResponse build();
  }
}
