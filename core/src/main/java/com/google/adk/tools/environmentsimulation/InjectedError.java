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

package com.google.adk.tools.environmentsimulation;

import com.google.auto.value.AutoValue;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * An error to be injected into a tool call.
 *
 * <p>Experimental. The shape of this type may change.
 */
@AutoValue
public abstract class InjectedError {

  /**
   * The HTTP error code to inject into the tool call. Reaches the model as {@code error_code} in
   * the tool response.
   */
  public abstract int injectedHttpErrorCode();

  /**
   * The error message to inject into the tool call. Reaches the model as {@code error_message} in
   * the tool response.
   */
  public abstract String errorMessage();

  public static Builder builder() {
    return new AutoValue_InjectedError.Builder();
  }

  public abstract Builder toBuilder();

  /** Builder for {@link InjectedError}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @CanIgnoreReturnValue
    public abstract Builder injectedHttpErrorCode(int injectedHttpErrorCode);

    @CanIgnoreReturnValue
    public abstract Builder errorMessage(String errorMessage);

    public abstract InjectedError build();
  }
}
