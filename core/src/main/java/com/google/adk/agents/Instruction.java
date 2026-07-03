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

package com.google.adk.agents;

import io.reactivex.rxjava3.core.Single;
import java.util.function.Function;

/**
 * Represents an instruction that can be provided to an agent to guide its behavior.
 *
 * <p>In the instructions, you should describe concisely what the agent will do, when it should
 * defer to other agents/tools, and how it should respond to the user.
 *
 * <p>Templating is supported using placeholders like {@code {variable_name}} or {@code
 * {artifact.artifact_name}}. These are replaced with values from the agent's session state or
 * loaded artifacts, respectively. For example, an instruction like {@code "Translate the following
 * text to {language}: {user_query}"} would substitute {@code {language}} and {@code {user_query}}
 * with their corresponding values from the session state.
 *
 * <p>Instructions can also be dynamically constructed using {@link Instruction.Provider}. This
 * allows for more complex logic where the instruction text is generated based on the current {@link
 * ReadonlyContext}. Additionally, an instruction could be built to include specific information
 * based on based on some external factors fetched during the Provider call like the current time,
 * the result of some API call, etc.
 */
public sealed interface Instruction permits Instruction.Static, Instruction.Provider {
  /** Plain instruction directly provided to the agent. */
  record Static(String instruction) implements Instruction {}

  /** Returns an instruction dynamically constructed from the given context. */
  record Provider(Function<ReadonlyContext, Single<String>> getInstruction)
      implements Instruction {}
}
