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

/** Basic LLM flow with fixed request processors and no response post-processing. */
public class SingleFlow extends BaseLlmFlow {

  protected static final ImmutableList<RequestProcessor> REQUEST_PROCESSORS =
      ImmutableList.of(
          new Basic(), new Instructions(), new Identity(), new Contents(), new Examples());
  
  protected static final ImmutableList<ResponseProcessor> RESPONSE_PROCESSORS = ImmutableList.of();

  public SingleFlow() {
    super(REQUEST_PROCESSORS, RESPONSE_PROCESSORS);
  }

  protected SingleFlow(
      List<RequestProcessor> requestProcessors, List<ResponseProcessor> responseProcessors) {
    super(requestProcessors, responseProcessors);
  }
}
