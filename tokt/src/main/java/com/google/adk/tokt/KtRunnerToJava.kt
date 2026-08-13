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
import com.google.adk.artifacts.BaseArtifactService as JavaArtifactService
import com.google.adk.artifacts.InMemoryArtifactService as JavaInMemoryArtifactService
import com.google.adk.events.Event as JavaEvent
import com.google.adk.kt.runners.Runner as KtRunner
import com.google.adk.plugins.PluginManager as JavaPluginManager
import com.google.adk.runner.Runner as JavaRunner
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.tokt.adapters.ktAgentAsJava
import com.google.adk.tokt.adapters.ktPluginManagerAsJava
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.RunConfigCodec
import com.google.adk.tokt.services.ktArtifactServiceAsJava
import com.google.adk.tokt.services.ktMemoryServiceAsJava
import com.google.adk.tokt.services.ktSessionServiceAsJava
import com.google.genai.types.Content as GenaiContent
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import java.util.Optional
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx3.asFlowable

/**
 * A [JavaRunner] that delegates to a Kotlin-engine [KtRunner], so a Kotlin runner can be dropped
 * into code written against the ADK Java [JavaRunner]: `runAsync` converts the request in and each
 * event out, running on the Kotlin engine against its own services and plugins. It honours the Java
 * `RunConfig.autoCreateSession` contract (erroring on a missing session unless it is set), but
 * per-session request sequencing follows the Kotlin engine, not the Java runner; live mode is not
 * bridged, so [runLive] returns a failed stream. [agent], [sessionService], [memoryService],
 * [artifactService] and [pluginManager] read the Kotlin runner's own components back through the
 * reverse adapters ([memoryService] / [artifactService] return `null` when absent; [pluginManager]
 * is read-only and throws on registration).
 */
// Subclassing Runner via its @Deprecated 8-arg super-constructor is intended here.
@Suppress("DEPRECATION")
internal class KtRunnerToJava(private val ktRunner: KtRunner) :
  JavaRunner(
    // A Java view of the Kotlin runner's agent so agent() reads back; never run (see runAsync).
    ktAgentAsJava(ktRunner.agent),
    ktRunner.appName,
    // Non-null for the Java Runner field; artifactService() returns this bridge when present and
    // null when the Kotlin runner has none (the run then uses no artifact service).
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
  ): Flowable<JavaEvent> {
    val events =
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
    // The Kotlin engine always creates a missing session; the Java Runner errors unless
    // autoCreateSession is set, so honour that contract before delegating rather than inverting it.
    if (runConfig.autoCreateSession()) return events
    return sessionService()
      .getSession(appName(), userId, sessionId, Optional.empty())
      .map { true }
      .defaultIfEmpty(false)
      .flatMapPublisher { exists ->
        if (exists) events
        else
          Flowable.error(IllegalArgumentException("Session not found and autoCreateSession=false"))
      }
  }

  // Live mode is unsupported; surface it through the stream (like the base Runner) rather than
  // throwing eagerly, so callers on the reactive path see it via onError.
  override fun runLive(
    session: JavaSession,
    liveRequestQueue: LiveRequestQueue,
    runConfig: JavaRunConfig,
  ): Flowable<JavaEvent> = Flowable.error(liveUnsupported())

  override fun runLive(
    userId: String,
    sessionId: String,
    liveRequestQueue: LiveRequestQueue,
    runConfig: JavaRunConfig,
  ): Flowable<JavaEvent> = Flowable.error(liveUnsupported())

  // A read-only Java view of the Kotlin runner's plugins (adapted Java plugins unwrapped); the
  // engine's plugins are fixed at construction, so the returned manager throws on registration.
  override fun pluginManager(): JavaPluginManager = ktPluginManagerAsJava(ktRunner.pluginManager)

  /**
   * The bridged artifact service, or `null` when the Kotlin runner has none - unlike a plain
   * [JavaRunner], whose accessor is non-null. Mirrors the Kotlin runner's nullable artifactService,
   * as [memoryService] does for its own absence.
   */
  override fun artifactService(): JavaArtifactService? =
    if (ktRunner.artifactService == null) null else super.artifactService()

  // ktRunner.close() releases the Kotlin runner's plugins and toolsets; super.close() only reaches
  // the Java agent view (which owns none) and the empty plugin manager, but is kept for symmetry.
  override fun close(): Completable =
    Completable.mergeArrayDelayError(super.close(), Completable.fromAction { ktRunner.close() })

  private fun liveUnsupported() =
    UnsupportedOperationException("Live mode is not supported when running on the Kotlin engine.")
}
