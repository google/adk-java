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
package com.google.adk.plugins;

import com.google.adk.plugins.recordings.Recordings;
import java.util.HashMap;
import java.util.Map;

/** Per-invocation replay state to isolate concurrent runs. */
class InvocationReplayState {
  private final String testCasePath;
  private final int userMessageIndex;
  private final Recordings recordings;

  // Per-agent replay indices for parallel execution
  // key: agent_name -> current replay index for that agent
  private final Map<String, Integer> agentReplayIndices;

  public InvocationReplayState(String testCasePath, int userMessageIndex, Recordings recordings) {
    this.testCasePath = testCasePath;
    this.userMessageIndex = userMessageIndex;
    this.recordings = recordings;
    this.agentReplayIndices = new HashMap<>();
  }

  public String getTestCasePath() {
    return testCasePath;
  }

  public int getUserMessageIndex() {
    return userMessageIndex;
  }

  public Recordings getRecordings() {
    return recordings;
  }

  public int getAgentReplayIndex(String agentName) {
    return agentReplayIndices.getOrDefault(agentName, 0);
  }

  public void setAgentReplayIndex(String agentName, int index) {
    agentReplayIndices.put(agentName, index);
  }

  public void incrementAgentReplayIndex(String agentName) {
    int currentIndex = getAgentReplayIndex(agentName);
    setAgentReplayIndex(agentName, currentIndex + 1);
  }
}
