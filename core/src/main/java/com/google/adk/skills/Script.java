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

package com.google.adk.skills;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;

/** Wrapper for script content. */
@AutoValue
public abstract class Script extends JsonBaseModel {

  @JsonProperty("src")
  public abstract String src();

  @JsonCreator
  public static Script create(@JsonProperty("src") String src) {
    return new AutoValue_Script(src);
  }

  @Override
  public final String toString() {
    return src();
  }
}
