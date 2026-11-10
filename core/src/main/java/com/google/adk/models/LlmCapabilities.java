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

package com.google.adk.models;

import com.google.auto.value.AutoValue;

/**
 * What a model supports, as reported by {@link BaseLlm#capabilities()}.
 *
 * <p>The builder sets every capability to unsupported unless told otherwise.
 */
@AutoValue
public abstract class LlmCapabilities {

  LlmCapabilities() {}

  /**
   * Whether the model can take a response schema in the same request as function declarations. When
   * false, an agent with an output schema and tools asks for its final answer through the {@code
   * set_model_response} tool instead.
   */
  public abstract boolean outputSchemaAndTools();

  public static Builder builder() {
    return new AutoValue_LlmCapabilities.Builder().outputSchemaAndTools(false);
  }

  public abstract Builder toBuilder();

  /** Builder for {@link LlmCapabilities}. */
  @AutoValue.Builder
  public abstract static class Builder {

    Builder() {}

    public abstract Builder outputSchemaAndTools(boolean outputSchemaAndTools);

    public abstract LlmCapabilities build();
  }
}
