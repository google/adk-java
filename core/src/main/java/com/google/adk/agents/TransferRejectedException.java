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

package com.google.adk.agents;

/**
 * Thrown when an {@link LlmAgent} calls {@code transfer_to_agent} with an agent it may not transfer
 * to: one other than its sub-agents, parent, and peers; one its {@code disallowTransferToParent} or
 * {@code disallowTransferToPeers} setting excludes; or itself, outside a live run.
 */
public final class TransferRejectedException extends IllegalStateException {

  private final String agentName;

  /** Creates an exception for a transfer rejected from {@code agentName} (the calling agent). */
  public TransferRejectedException(String agentName, String message) {
    super(message);
    this.agentName = agentName;
  }

  /** Returns the name of the agent whose transfer was rejected. */
  public String agentName() {
    return agentName;
  }
}
