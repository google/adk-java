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

/** LLM flow with automatic agent transfer support. */
public class AutoFlow extends SingleFlow {

  /** Adds {@link AgentTransfer} to base request processors. */
  private static final ImmutableList<RequestProcessor> REQUEST_PROCESSORS =
      ImmutableList.<RequestProcessor>builder()
          .addAll(SingleFlow.REQUEST_PROCESSORS)
          .add(new AgentTransfer())
          .build();

  /** No additional response processors. */
  private static final ImmutableList<ResponseProcessor> RESPONSE_PROCESSORS = ImmutableList.of();

  public AutoFlow() {
    super(REQUEST_PROCESSORS, RESPONSE_PROCESSORS);
  }
}
