/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS language governing permissions and
 * limitations under the License.
 */
package com.google.adk.apps;

/**
 * An app contains Resumability configuration for the agents.
 *
 * @param isResumable Whether the app is resumable.
 */
public record ResumabilityConfig(boolean isResumable) {

  /** Creates a new {@code ResumabilityConfig} with resumability disabled. */
  public ResumabilityConfig() {
    this(false);
  }
}
