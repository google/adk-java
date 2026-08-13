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

package com.google.adk.tokt

import com.google.adk.agents.LiveRequestQueue
import com.google.adk.agents.RunConfig as JavaRunConfig
import com.google.adk.artifacts.InMemoryArtifactService as JavaInMemoryArtifactService
import com.google.adk.events.Event as JavaEvent
import com.google.adk.kt.runners.Runner as KtRunner
import com.google.adk.runner.Runner as JavaRunner
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.tokt.adapters.ktAgentAsJava
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.RunConfigCodec
import com.google.adk.tokt.services.ktArtifactServiceAsJava
import com.google.adk.tokt.services.ktMemoryServiceAsJava
import com.google.adk.tokt.services.ktSessionServiceAsJava
import com.google.genai.types.Content as GenaiContent
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx3.asFlowable

/**
 * A [JavaRunner] that delegates to a Kotlin-engine [KtRunner], so a Kotlin runner can be dropped
 * into code written against the ADK Java [JavaRunner]: `runAsync` converts the request in and each
 * event out, running on the Kotlin engine against its own services (session and memory are reported
 * back through the reverse adapters). `RunConfig` fields the engines do not share (e.g.
 * `autoCreateSession`) and per-session request sequencing follow the Kotlin engine, not the Java
 * runner; live mode is not bridged so [runLive] throws, [agent] reads back the runner's agent (it
 * is never run through this wrapper), and [pluginManager] is empty (plugins run on the Kotlin
 * engine).
 */
// Subclassing Runner via its @Deprecated 8-arg super-constructor is intended here.
@Suppress("DEPRECATION")
internal class KtRunnerToJava(private val ktRunner: KtRunner) :
  JavaRunner(
    // A Java view of the Kotlin runner's agent so agent() reads back; never run (see runAsync).
    ktAgentAsJava(ktRunner.agent),
    ktRunner.appName,
    // Non-null placeholder; when the Kotlin runner has no artifact service the run uses none.
    ktRunner.artifactService?.let { ktArtifactServiceAsJava(it) } ?: JavaInMemoryArtifactService(),
    ktSessionServiceAsJava(ktRunner.sessionService),
    ktRunner.memoryService?.let { ktMemoryServiceAsJava(it) },
    emptyList(),
    null,
    null,
  ) {

  override fun runAsync(
    userId: String,
    sessionId: String,
    newMessage: GenaiContent,
    runConfig: JavaRunConfig,
    stateDelta: MutableMap<String, Any>?,
  ): Flowable<JavaEvent> =
    ktRunner
      .runAsync(
        userId = userId,
        sessionId = sessionId,
        newMessage = ContentCodec.fromJava(newMessage),
        stateDelta = stateDelta,
        runConfig = RunConfigCodec.fromJava(runConfig),
      )
      .map { EventCodec.toJava(it) }
      .asFlowable()

  override fun runLive(
    session: JavaSession,
    liveRequestQueue: LiveRequestQueue,
    runConfig: JavaRunConfig,
  ): Flowable<JavaEvent> = throw liveUnsupported()

  override fun runLive(
    userId: String,
    sessionId: String,
    liveRequestQueue: LiveRequestQueue,
    runConfig: JavaRunConfig,
  ): Flowable<JavaEvent> = throw liveUnsupported()

  // Close disjoint resources: super closes the agent (an adapted Java agent's toolsets are
  // invisible to the Kotlin runner), and ktRunner closes its own plugins and Kotlin toolsets.
  override fun close(): Completable =
    Completable.mergeArrayDelayError(super.close(), Completable.fromAction { ktRunner.close() })

  private fun liveUnsupported() =
    UnsupportedOperationException("Live mode is not supported when running on the Kotlin engine.")
}
