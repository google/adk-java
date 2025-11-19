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

import java.util.List;

/** Configuration for ParallelAgent. */
public class ParallelAgentConfig extends BaseAgentConfig {

  // Callback configuration (names resolved via ComponentRegistry)
  private List<CallbackRef> beforeAgentCallbacks;
  private List<CallbackRef> afterAgentCallbacks;

  /** Reference to a callback stored in the ComponentRegistry. */
  public static class CallbackRef {
    private String name;

    public CallbackRef() {}

    public CallbackRef(String name) {
      this.name = name;
    }

    public String name() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  public ParallelAgentConfig() {
    super();
    setAgentClass("ParallelAgent");
  }

  public List<CallbackRef> beforeAgentCallbacks() {
    return beforeAgentCallbacks;
  }

  public void setBeforeAgentCallbacks(List<CallbackRef> beforeAgentCallbacks) {
    this.beforeAgentCallbacks = beforeAgentCallbacks;
  }

  public List<CallbackRef> afterAgentCallbacks() {
    return afterAgentCallbacks;
  }

  public void setAfterAgentCallbacks(List<CallbackRef> afterAgentCallbacks) {
    this.afterAgentCallbacks = afterAgentCallbacks;
  }
}
