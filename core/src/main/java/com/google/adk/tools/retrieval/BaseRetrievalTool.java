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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.tools.retrieval;

import com.google.adk.tools.BaseTool;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import java.util.Collections;
import java.util.Optional;

/** Base class for retrieval tools. */
public abstract class BaseRetrievalTool extends BaseTool {
  public BaseRetrievalTool(String name, String description) {
    super(name, description);
  }

  public BaseRetrievalTool(String name, String description, boolean isLongRunning) {
    super(name, description, isLongRunning);
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    Schema querySchema =
        Schema.builder().type("STRING").description("The query to retrieve.").build();
    Schema parametersSchema =
        Schema.builder()
            .type("OBJECT")
            .properties(Collections.singletonMap("query", querySchema))
            .build();

    return Optional.of(
        FunctionDeclaration.builder()
            .name(this.name())
            .description(this.description())
            .parameters(parametersSchema)
            .build());
  }
}
