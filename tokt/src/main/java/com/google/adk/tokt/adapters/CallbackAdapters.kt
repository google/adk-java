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
import com.google.adk.agents.Callbacks.AfterAgentCallback as JavaAfterAgentCallback
import com.google.adk.agents.Callbacks.AfterModelCallback as JavaAfterModelCallback
import com.google.adk.agents.Callbacks.AfterToolCallback as JavaAfterToolCallback
import com.google.adk.agents.Callbacks.BeforeAgentCallback as JavaBeforeAgentCallback
import com.google.adk.agents.Callbacks.BeforeModelCallback as JavaBeforeModelCallback
import com.google.adk.agents.Callbacks.BeforeToolCallback as JavaBeforeToolCallback
import com.google.adk.agents.Callbacks.OnModelErrorCallback as JavaOnModelErrorCallback
import com.google.adk.agents.Callbacks.OnToolErrorCallback as JavaOnToolErrorCallback
import com.google.adk.kt.callbacks.AfterAgentCallback as KtAfterAgentCallback
import com.google.adk.kt.callbacks.AfterModelCallback as KtAfterModelCallback
import com.google.adk.kt.callbacks.AfterToolCallback as KtAfterToolCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback as KtBeforeAgentCallback
import com.google.adk.kt.callbacks.BeforeModelCallback as KtBeforeModelCallback
import com.google.adk.kt.callbacks.BeforeToolCallback as KtBeforeToolCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.callbacks.OnModelErrorCallback as KtOnModelErrorCallback
import com.google.adk.kt.callbacks.OnToolErrorCallback as KtOnToolErrorCallback
import com.google.adk.kt.events.EventActions as KtEventActions
import com.google.adk.tokt.InteropDispatcher
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.LlmRequestCodec
import com.google.adk.tokt.codecs.LlmResponseCodec
import com.google.adk.tokt.codecs.reconcileRemovedSentinels
import com.google.adk.tokt.context.KtInvocationContextToJavaView
import com.google.adk.tokt.context.ktCallbackContextToJava
import com.google.adk.tokt.context.ktToolContextToJava
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.withContext

/**
 * Engine -> Java callback bridge: adapts a user's ADK Java agent, model, and tool callbacks to
 * engine callbacks (so a natively translated [JavaLlmAgent][com.google.adk.agents.LlmAgent] keeps
 * running them), presenting the engine context as a Java one and awaiting the callback's RxJava
 * `Maybe` off the engine dispatcher since it may block. State and artifact deltas write straight
 * through the live context views, so each translator reconciles the Java removal sentinel to the
 * Kotlin one; tool callbacks apply only to engine-backed Java tools ([JavaToolToKt]).
 */
internal fun JavaBeforeAgentCallback.toEngine(javaAgent: JavaBaseAgent): KtBeforeAgentCallback =
  KtBeforeAgentCallback { context ->
    val override =
      withContext(InteropDispatcher) {
        call(ktCallbackContextToJava(context, javaAgent)).toFlowable().awaitFirstOrNull()
      }
    reconcileRemovedSentinels(context.eventActions.stateDelta)
    if (override != null) CallbackChoice.Break(ContentCodec.fromJava(override))
    else CallbackChoice.Continue(KtEventActions())
  }

internal fun JavaAfterAgentCallback.toEngine(javaAgent: JavaBaseAgent): KtAfterAgentCallback =
  KtAfterAgentCallback { context ->
    val override =
      withContext(InteropDispatcher) {
        call(ktCallbackContextToJava(context, javaAgent)).toFlowable().awaitFirstOrNull()
      }
    reconcileRemovedSentinels(context.eventActions.stateDelta)
    if (override != null) CallbackChoice.Break(ContentCodec.fromJava(override))
    else CallbackChoice.Continue(Unit)
  }

internal fun JavaBeforeModelCallback.toEngine(javaAgent: JavaBaseAgent): KtBeforeModelCallback =
  KtBeforeModelCallback { context, request ->
    val builder = LlmRequestCodec.toJava(request).toBuilder()
    val override =
      withContext(InteropDispatcher) {
        call(ktCallbackContextToJava(context, javaAgent), builder).toFlowable().awaitFirstOrNull()
      }
    reconcileRemovedSentinels(context.eventActions.stateDelta)
    if (override != null) {
      CallbackChoice.Break(LlmResponseCodec.fromJava(override))
    } else {
      // Re-apply onto the original request to preserve engine-only fields (model, toolsDict, cache
      // config); LlmRequestCodec.fromJava would build a fresh request and drop them.
      CallbackChoice.Continue(reapplyJavaRequest(request, builder.build()))
    }
  }

internal fun JavaAfterModelCallback.toEngine(javaAgent: JavaBaseAgent): KtAfterModelCallback =
  KtAfterModelCallback { context, response ->
    val override =
      withContext(InteropDispatcher) {
        call(ktCallbackContextToJava(context, javaAgent), LlmResponseCodec.toJava(response))
          .toFlowable()
          .awaitFirstOrNull()
      }
    reconcileRemovedSentinels(context.eventActions.stateDelta)
    if (override != null) LlmResponseCodec.fromJava(override) else response
  }

internal fun JavaOnModelErrorCallback.toEngine(javaAgent: JavaBaseAgent): KtOnModelErrorCallback =
  KtOnModelErrorCallback { context, request, error ->
    val override =
      withContext(InteropDispatcher) {
        call(
            ktCallbackContextToJava(context, javaAgent),
            LlmRequestCodec.toJava(request),
            error as? Exception ?: RuntimeException(error),
          )
          .toFlowable()
          .awaitFirstOrNull()
      }
    reconcileRemovedSentinels(context.eventActions.stateDelta)
    if (override != null) CallbackChoice.Break(LlmResponseCodec.fromJava(override))
    else CallbackChoice.Continue(Unit)
  }

internal fun JavaBeforeToolCallback.toEngine(): KtBeforeToolCallback =
  KtBeforeToolCallback { context, tool, args ->
    val javaTool = (tool as? JavaToolToKt)?.javaTool
    if (javaTool == null) {
      CallbackChoice.Continue(args)
    } else {
      val mutableArgs = args.toMutableMap()
      val override =
        withContext(InteropDispatcher) {
          call(
              KtInvocationContextToJavaView(context.invocationContext),
              javaTool,
              mutableArgs,
              ktToolContextToJava(context),
            )
            .toFlowable()
            .awaitFirstOrNull()
        }
      reconcileRemovedSentinels(context.actions.stateDelta)
      if (override != null) CallbackChoice.Break(override) else CallbackChoice.Continue(mutableArgs)
    }
  }

internal fun JavaAfterToolCallback.toEngine(): KtAfterToolCallback =
  KtAfterToolCallback { context, tool, args, result ->
    val javaTool = (tool as? JavaToolToKt)?.javaTool
    if (javaTool == null) {
      result
    } else {
      val override =
        withContext(InteropDispatcher) {
          call(
              KtInvocationContextToJavaView(context.invocationContext),
              javaTool,
              args,
              ktToolContextToJava(context),
              result,
            )
            .toFlowable()
            .awaitFirstOrNull()
        }
      reconcileRemovedSentinels(context.actions.stateDelta)
      override ?: result
    }
  }

internal fun JavaOnToolErrorCallback.toEngine(): KtOnToolErrorCallback =
  KtOnToolErrorCallback { context, tool, args, error ->
    val javaTool = (tool as? JavaToolToKt)?.javaTool
    if (javaTool == null) {
      CallbackChoice.Continue(Unit)
    } else {
      val override =
        withContext(InteropDispatcher) {
          call(
              KtInvocationContextToJavaView(context.invocationContext),
              javaTool,
              args,
              ktToolContextToJava(context),
              error as? Exception ?: RuntimeException(error),
            )
            .toFlowable()
            .awaitFirstOrNull()
        }
      reconcileRemovedSentinels(context.actions.stateDelta)
      if (override != null) CallbackChoice.Break(override) else CallbackChoice.Continue(Unit)
    }
  }
