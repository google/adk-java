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
import com.google.adk.agents.InvocationContext as JavaInvocationContext
import com.google.adk.events.Event as JavaEvent
import com.google.adk.kt.agents.BaseAgent as KtBaseAgent
import io.reactivex.rxjava3.core.Flowable

/**
 * An inspection-only Java view of a Kotlin [KtBaseAgent]. It exists solely so Java plugin callbacks
 * (handed the running engine agent via [ktAgentAsJava]) can read it as an ADK Java [JavaBaseAgent]:
 * name, description, and the sub-agent tree (each child wrapped the same way, so `subAgents()` /
 * `parentAgent()` / `rootAgent()` / `findAgent()` reflect the real tree). It is not meant to be
 * executed: the Kotlin runner is the sole driver of agent execution, so both [runAsyncImpl] and
 * [runLiveImpl] throw. A plugin must not run the agent it is given. The agent's own callbacks are
 * intentionally not exposed (they are lifecycle hooks, not inspection data).
 */
internal class KtAgentToJava(ktAgent: KtBaseAgent) :
  JavaBaseAgent(
    ktAgent.name,
    ktAgent.description,
    ktAgent.subAgents.map { KtAgentToJava(it) },
    null,
    null,
  ) {

  override fun runAsyncImpl(invocationContext: JavaInvocationContext): Flowable<JavaEvent> =
    Flowable.error(unsupported())

  override fun runLiveImpl(invocationContext: JavaInvocationContext): Flowable<JavaEvent> =
    Flowable.error(unsupported())

  private fun unsupported() =
    UnsupportedOperationException(
      "This is an inspection-only view of a Kotlin engine agent, handed to Java plugin callbacks " +
        "via ktAgentAsJava; the Kotlin runner is the sole driver of agent execution, so it cannot " +
        "be run from Java."
    )
}

/**
 * Wraps a Kotlin [ktAgent] as an inspection-only Java [JavaBaseAgent] view for plugin callbacks.
 * The result exposes the agent's metadata but must not be executed (see [KtAgentToJava]).
 */
internal fun ktAgentAsJava(ktAgent: KtBaseAgent): JavaBaseAgent = KtAgentToJava(ktAgent)
