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
import com.google.adk.agents.RunConfig as JavaRunConfig
import com.google.adk.apps.ResumabilityConfig as JavaResumabilityConfig
import com.google.adk.flows.llmflows.PersistBarrier
import com.google.adk.kt.agents.BaseAgent as KtBaseAgent
import com.google.adk.kt.agents.InvocationContext as KtInvocationContext
import com.google.adk.kt.events.Event as KtEvent
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.context.KtInvocationContextToJavaView
import com.google.adk.tokt.context.ktInvocationContextJavaBuilder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow

/**
 * Adapts an ADK Java [javaAgent] into an ADK Kotlin [KtBaseAgent] so it runs on the Kotlin engine.
 *
 * The Java agent runs its OWN flow -- model calls, tool execution, its own before/after callbacks,
 * and sub-agent transfers within its Java subtree -- against a Java invocation context; this
 * adapter bridges the context in and converts the emitted events back to Kotlin. It is exposed as
 * an opaque leaf (no Kotlin sub-agents), so the engine does not double-manage the Java tree.
 *
 * The bridged context's session is a **live** Java view of the Kotlin one, so this adapter never
 * persists or copies events itself; the Kotlin runner stays the sole persister. Multi-step flows
 * (model -> tool -> model) then rely on the Java `BaseLlmFlow`'s own step synchronization rather
 * than bespoke handling here: it builds each request from `session.events()` and waits on a
 * [PersistBarrier]. This adapter enables that barrier and releases each event once the runner has
 * appended it, so the next step reads a current session.
 *
 * The invocation's resumability is carried across too: the Java `BaseLlmFlow` pauses on a pending
 * long-running call (HITL, and the `adk_request_confirmation` a `requireConfirmation` tool raises)
 * only when its context reports `isResumable()`, and would otherwise loop back to the model and
 * re-issue the request. Resuming replays the answer through the session, not a checkpoint: Java
 * events carry no Kotlin `agentState`, so a position inside the Java agent is not restorable.
 *
 * Limitations, because the Java agent is opaque to the engine:
 * - The Java context carries no plugin manager, so plugins see only the run / agent / event-level
 *   callbacks the Kotlin runner drives, not the model and tool calls made inside.
 * - Of the Kotlin `RunConfig` only `maxLlmCalls` is carried, and it always runs non-streaming. The
 *   Java side then enforces that budget against its own counter, so a mixed tree allows the limit
 *   once per engine, and the Java counter restarts every time the agent is re-entered (a Kotlin
 *   `LoopAgent` iteration, say). `eventsCompactionConfig` is not carried either.
 * - Under a Kotlin `ParallelAgent` the step barrier above is unreliable: `merge()` buffers, so
 *   `emit` returns before the runner appended and a multi-step Java agent may build its next
 *   request from a session missing the previous step. Keep an adapted multi-step agent out of a
 *   `ParallelAgent`, or use it as a leaf under `Sequential`/`Loop`/the root, which are unbuffered.
 * - It marks itself finished for resumability, but the engine cannot route a *follow-up* user turn
 *   back into it: `isTransferableAcrossAgentTree` requires every agent in the chain to be a Kotlin
 *   `LlmAgent`, and this is a `BaseAgent` leaf, so the next turn starts at the root. Names inside
 *   the Java subtree are likewise not resolvable by `findAgent`. Resuming a pending long-running
 *   call is unaffected: that routes by matching function-call author.
 */
internal class JavaAgentToKt(internal val javaAgent: JavaBaseAgent) :
  // A Java agent's description may be null; the Kotlin engine uses "" for "no description".
  KtBaseAgent(name = javaAgent.name(), description = javaAgent.description() ?: "") {

  override fun runAsyncImpl(context: KtInvocationContext): Flow<KtEvent> = flow {
    val javaContext = buildJavaContext(context)
    PersistBarrier.enable(javaContext)
    var shouldPause = false
    javaAgent
      .runAsync(javaContext)
      .asFlow()
      // Convert inside the flow, not with Flowable.map, so flowOn actually governs the codec.
      .map { EventCodec.fromJava(it) }
      .flowOn(Dispatchers.IO)
      .collect { event ->
        // emit() suspends until the runner appended this event -- except under a buffering
        // collector, see the ParallelAgent limitation above -- so releasing the barrier after it
        // is what lets the Java flow's next step read a current session.
        try {
          emit(event)
        } catch (t: Throwable) {
          // Cancellation is not a persist failure, and markFailed would leave the barrier holding
          // a terminal error for an awaiting Java step.
          if (t !is CancellationException) PersistBarrier.markFailed(javaContext, event.id, t)
          throw t
        }
        PersistBarrier.markPersisted(javaContext, event.id)
        if (context.shouldPauseInvocation(event)) shouldPause = true
      }
    if (shouldPause) return@flow

    // Record completion the way every native Kotlin agent does: nothing on the Java side sets
    // EventActions.endOfAgent, and without it a resumable ParallelAgent both re-runs this agent
    // after a sibling pauses and never marks itself done. Mirrors LlmAgent.
    if (context.isResumable && context.agent == this@JavaAgentToKt) {
      context.setAgentState(name, endOfAgent = true)
      emitEndOfAgent(context)
    }
  }

  /**
   * The shared Kt -> Java context view, with the real Java agent in place of the inspection-only
   * one and the two invocation settings the Java flow needs. Returning the view rather than a plain
   * context keeps `branch()` reading the Kotlin branch and `setEndInvocation` writing back, which
   * `BaseAgent.runAsync` uses when a Java before-agent callback short-circuits.
   *
   * That write-through reaches this agent only. `BaseAgent.createInvocationContext` copies the
   * context for each Java sub-agent via `toBuilder()`, and the copy is a plain `InvocationContext`,
   * so a nested Java agent's `setEndInvocation` writes nowhere. Builder-set fields do survive the
   * copy, which is why `callbackContextData` -- where [PersistBarrier] keeps its state -- is set on
   * the builder rather than only overridden.
   */
  @Suppress("DEPRECATION") // JavaInvocationContext still plumbs the deprecated ResumabilityConfig.
  private fun buildJavaContext(context: KtInvocationContext): JavaInvocationContext {
    val builder =
      ktInvocationContextJavaBuilder(context, javaAgent)
        // Without this the Java flow keeps calling the model after a pending long-running call
        // instead of pausing on it (see the class doc).
        .resumabilityConfig(JavaResumabilityConfig.builder().resumable(context.isResumable).build())
    context.runConfig?.let {
      builder.runConfig(JavaRunConfig.builder().setMaxLlmCalls(it.maxLlmCalls).build())
    }
    return KtInvocationContextToJavaView(builder, context)
  }
}
