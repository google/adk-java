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

package com.google.adk.tokt.adapters

import com.google.adk.agents.BaseAgent as JavaBaseAgent
import com.google.adk.agents.LlmAgent as JavaLlmAgent
import com.google.adk.agents.LoopAgent as JavaLoopAgent
import com.google.adk.agents.ParallelAgent as JavaParallelAgent
import com.google.adk.agents.SequentialAgent as JavaSequentialAgent
import com.google.adk.kt.agents.BaseAgent as KtBaseAgent
import com.google.adk.kt.agents.LoopAgent as KtLoopAgent
import com.google.adk.kt.agents.ParallelAgent as KtParallelAgent
import com.google.adk.kt.agents.SequentialAgent as KtSequentialAgent

/**
 * Translates an ADK Java agent to its ADK Kotlin engine equivalent: the exact `LlmAgent`
 * ([LlmAgentCodec]) and the exact `Sequential` / `Parallel` / `Loop` workflow agents become native
 * Kotlin agents; a subclass of those, or any other agent, is wrapped in the opaque [JavaAgentToKt]
 * so any behavior it overrides is never lost. A `ParallelAgent` is native only when its whole
 * subtree is, since the engine's parallel merge would break an opaque multi-step sub-agent's
 * persist handshake.
 */
internal object AgentCodec {

  /** Returns the engine agent equivalent of the Java [agent]. */
  fun toKotlin(agent: JavaBaseAgent): KtBaseAgent =
    when {
      // Exact class only: a subclass may override behavior (the run methods are not final), so it
      // runs opaquely to keep that behavior rather than being translated away.
      agent is JavaLlmAgent &&
        agent.javaClass == JavaLlmAgent::class.java &&
        LlmAgentCodec.translatable(agent) -> LlmAgentCodec.toKotlin(agent)
      agent.javaClass == JavaSequentialAgent::class.java ->
        KtSequentialAgent(
          name = agent.name(),
          description = agent.description() ?: "",
          subAgents = subAgents(agent),
          beforeAgentCallbacks = beforeAgentCallbacks(agent),
          afterAgentCallbacks = afterAgentCallbacks(agent),
        )
      agent.javaClass == JavaParallelAgent::class.java && subtreeFullyTranslatable(agent) ->
        KtParallelAgent(
          name = agent.name(),
          description = agent.description() ?: "",
          subAgents = subAgents(agent),
          beforeAgentCallbacks = beforeAgentCallbacks(agent),
          afterAgentCallbacks = afterAgentCallbacks(agent),
        )
      agent.javaClass == JavaLoopAgent::class.java ->
        KtLoopAgent(
          name = agent.name(),
          maxIterations = (agent as JavaLoopAgent).maxIterations(),
          description = agent.description() ?: "",
          subAgents = subAgents(agent),
          beforeAgentCallbacks = beforeAgentCallbacks(agent),
          afterAgentCallbacks = afterAgentCallbacks(agent),
        )
      else -> JavaAgentToKt(agent)
    }

  /** Recursively translates [agent]'s sub-agents. */
  internal fun subAgents(agent: JavaBaseAgent): List<KtBaseAgent> =
    (agent.subAgents() ?: emptyList<JavaBaseAgent>()).map { toKotlin(it) }

  /** Whether [agent] itself (ignoring its sub-agents) becomes a native engine agent. */
  private fun translatesToNative(agent: JavaBaseAgent): Boolean =
    (agent is JavaLlmAgent &&
      agent.javaClass == JavaLlmAgent::class.java &&
      LlmAgentCodec.translatable(agent)) ||
      agent.javaClass == JavaSequentialAgent::class.java ||
      agent.javaClass == JavaParallelAgent::class.java ||
      agent.javaClass == JavaLoopAgent::class.java

  /**
   * Whether [agent] and every agent under it translate natively (no opaque wrap in the subtree).
   */
  private fun subtreeFullyTranslatable(agent: JavaBaseAgent): Boolean =
    translatesToNative(agent) &&
      (agent.subAgents() ?: emptyList<JavaBaseAgent>()).all { subtreeFullyTranslatable(it) }

  private fun beforeAgentCallbacks(agent: JavaBaseAgent) =
    agent.beforeAgentCallback().map { it.toEngine(agent) }

  private fun afterAgentCallbacks(agent: JavaBaseAgent) =
    agent.afterAgentCallback().map { it.toEngine(agent) }
}
