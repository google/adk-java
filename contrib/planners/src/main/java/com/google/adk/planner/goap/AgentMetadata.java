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

package com.google.adk.planner.goap;

import com.google.common.collect.ImmutableList;

/**
 * Declares what state keys an agent reads (inputs) and writes (output).
 *
 * <p>Used by {@link GoalOrientedPlanner} and {@link com.google.adk.planner.p2p.P2PPlanner} for
 * dependency resolution.
 *
 * @param agentName the name of the agent (must match {@link
 *     com.google.adk.agents.BaseAgent#name()})
 * @param inputKeys the state keys this agent reads as inputs
 * @param outputKey the state key this agent produces as output
 */
public record AgentMetadata(String agentName, ImmutableList<String> inputKeys, String outputKey) {}
