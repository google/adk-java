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

package com.google.adk.flows.llmflows;

import com.google.common.collect.ImmutableList;

/**
 * Represents an automatic flow in the Large Language Model (LLM) system,
 * extending the capabilities of a {@link SingleFlow}.
 * This flow is designed to automatically handle agent transfers, indicating
 * a more sophisticated or automated processing sequence compared to a basic single flow.
 * It primarily reuses request processors from {@link SingleFlow} and adds an
 * {@link AgentTransfer} processor.
 */
public class AutoFlow extends SingleFlow {

  /**
   * An immutable list of {@link RequestProcessor} instances defining the processing pipeline
   * for incoming requests in this AutoFlow.
   * This list extends the processors from {@link SingleFlow} by adding an {@link AgentTransfer}
   * processor, enabling automated agent-to-agent transitions or handling.
   */
  private static final ImmutableList<RequestProcessor> REQUEST_PROCESSORS =
      ImmutableList.<RequestProcessor>builder()
          .addAll(SingleFlow.REQUEST_PROCESSORS)
          .add(new AgentTransfer())
          .build();

  /**
   * An immutable list of {@link ResponseProcessor} instances for this AutoFlow.
   * In this specific implementation, no custom response processors are defined,
   * meaning response handling might rely on default behaviors or inherited processors
   * from {@link SingleFlow} if any.
   */
  private static final ImmutableList<ResponseProcessor> RESPONSE_PROCESSORS = ImmutableList.of();

  /**
   * Constructs a new {@code AutoFlow} instance.
   * Initializes the flow by passing its predefined {@link #REQUEST_PROCESSORS} and
   * {@link #RESPONSE_PROCESSORS} to the superclass constructor.
   */
  public AutoFlow() {
    super(REQUEST_PROCESSORS, RESPONSE_PROCESSORS);
  }
}
