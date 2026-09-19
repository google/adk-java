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

package com.google.adk.tools;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.google.adk.JsonBaseModel;

/**
 * The base class for the config of a single tool.
 *
 * <p>A tool that takes arguments in a config document gives them a type by extending this class
 * with one property per argument, and filling it from the {@link BaseTool.ToolArgsConfig} of the
 * entry that named the tool:
 *
 * <pre>{@code
 * MyToolConfig config =
 *     JsonBaseModel.getMapper().convertValue(args.getAdditionalProperties(), MyToolConfig.class);
 * }</pre>
 *
 * <p>An argument the subclass does not declare is refused there rather than dropped, so a
 * misspelled key in the document reaches the author as an error instead of as an argument that
 * silently went missing.
 *
 * <p>Experimental. The shape of this type may change.
 */
public abstract class BaseToolConfig extends JsonBaseModel {

  protected BaseToolConfig() {}

  // A key that matches no property of the subclass arrives here, which is where it can be refused:
  // the shared mapper skips unknown keys rather than failing on them.
  @JsonAnySetter
  private void refuseUndeclaredArg(String name, Object value) {
    throw new IllegalArgumentException(
        String.format("Unknown arg \"%s\" for tool config %s.", name, getClass().getName()));
  }
}
