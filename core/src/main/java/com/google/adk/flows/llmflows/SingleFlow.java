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
import java.util.List;

/**
 * Represents a single, basic flow for Large Language Model (LLM) processing,
 * extending {@link BaseLlmFlow}.
 * This class defines a standard sequence of request processors for preparing
 * LLM requests and an empty set of response processors for handling LLM responses
 * by default. It can be extended or configured with custom processor lists.
 */
public class SingleFlow extends BaseLlmFlow {

  /**
   * An immutable list of {@link RequestProcessor} instances that define the default
   * processing pipeline for incoming requests in a single LLM flow.
   * This list includes processors for basic request handling, instructions, identity,
   * content, and examples.
   */
  protected static final ImmutableList<RequestProcessor> REQUEST_PROCESSORS =
      ImmutableList.of(
          new Basic(), new Instructions(), new Identity(), new Contents(), new Examples());
  
  /**
   * An immutable list of {@link ResponseProcessor} instances for handling responses
   * in a single LLM flow.
   * By default, this list is empty, indicating that no specific response post-processing
   * is performed at this level unless overridden or supplied.
   */
  protected static final ImmutableList<ResponseProcessor> RESPONSE_PROCESSORS = ImmutableList.of();

  /**
   * Constructs a new {@code SingleFlow} instance with the default request and response processors.
   * This constructor initializes the flow using the predefined {@link #REQUEST_PROCESSORS}
   * and {@link #RESPONSE_PROCESSORS}.
   */
  public SingleFlow() {
    super(REQUEST_PROCESSORS, RESPONSE_PROCESSORS);
  }

  /**
   * Constructs a new {@code SingleFlow} instance with custom lists of request and response processors.
   * This constructor allows for more flexible configuration of the flow's processing pipeline.
   *
   * @param requestProcessors A {@link List} of {@link RequestProcessor} instances to be used for
   * processing incoming requests.
   * @param responseProcessors A {@link List} of {@link ResponseProcessor} instances to be used for
   * processing responses from the LLM.
   */
  protected SingleFlow(
      List<RequestProcessor> requestProcessors, List<ResponseProcessor> responseProcessors) {
    super(requestProcessors, responseProcessors);
  }
}
