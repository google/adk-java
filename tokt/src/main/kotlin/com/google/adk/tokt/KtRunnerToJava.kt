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

@file:OptIn(ExperimentalLiveApi::class)

package com.google.adk.tokt

import com.google.adk.agents.LiveRequestQueue
import com.google.adk.agents.RunConfig as JavaRunConfig
import com.google.adk.annotations.Experimental
import com.google.adk.artifacts.BaseArtifactService as JavaArtifactService
import com.google.adk.artifacts.InMemoryArtifactService as JavaInMemoryArtifactService
import com.google.adk.events.Event as JavaEvent
import com.google.adk.kt.agents.LiveRequestQueue as KtLiveRequestQueue
import com.google.adk.kt.annotations.ExperimentalLiveApi
import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.models.RealtimeInput
import com.google.adk.kt.runners.Runner as KtRunner
import com.google.adk.kt.types.Content as KtContent
import com.google.adk.kt.types.Role as KtRole
import com.google.adk.plugins.PluginManager as JavaPluginManager
import com.google.adk.runner.Runner as JavaRunner
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.tokt.adapters.ktAgentAsJava
import com.google.adk.tokt.adapters.ktPluginManagerAsJava
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.PartCodec
import com.google.adk.tokt.codecs.RunConfigCodec
import com.google.adk.tokt.codecs.stateDeltaFromJava
import com.google.adk.tokt.services.ktArtifactServiceAsJava
import com.google.adk.tokt.services.ktMemoryServiceAsJava
import com.google.adk.tokt.services.ktSessionServiceAsJava
import com.google.genai.types.Blob as GenaiBlob
import com.google.genai.types.Content as GenaiContent
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Flowable
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.rx3.asFlowable

/**
 * A [JavaRunner] that delegates to a Kotlin-engine [KtRunner], so a Kotlin runner can be dropped
 * into code written against the ADK Java [JavaRunner]; `runAsync` and [runLive] run on the Kotlin
 * engine against its own services and plugins. It honors the Java `RunConfig.autoCreateSession`
 * contract, but per-session request sequencing follows the Kotlin engine, not the Java runner.
 * [agent], [sessionService], [memoryService], [artifactService] and [pluginManager] read the Kotlin
 * runner's own components back through reverse adapters on `dispatcher` ([memoryService] and
 * [artifactService] return `null` when absent; [pluginManager] is read-only).
 */
// Subclassing Runner via its @Deprecated 8-arg super-constructor is intended here.
@Suppress("DEPRECATION")
internal class KtRunnerToJava(
  private val ktRunner: KtRunner,
  private val dispatcher: CoroutineDispatcher,
) :
  JavaRunner(
    // A Java view of the Kotlin runner's agent so agent() reads back; never run (see runAsync).
    ktAgentAsJava(ktRunner.agent),
    ktRunner.appName,
    // Non-null for the Java Runner field; artifactService() returns this bridge when present and
    // null when the Kotlin runner has none (the run then uses no artifact service).
    ktRunner.artifactService?.let { ktArtifactServiceAsJava(it, dispatcher) }
      ?: JavaInMemoryArtifactService(),
    ktSessionServiceAsJava(ktRunner.sessionService, dispatcher),
    ktRunner.memoryService?.let { ktMemoryServiceAsJava(it, dispatcher) },
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
    runAsync(
      userId,
      sessionId,
      invocationId = null,
      newMessage = newMessage,
      runConfig = runConfig,
      stateDelta = stateDelta,
    )

  /**
   * Resumes on the Kotlin engine with its semantics instead of the Java runner's. A non-resumable
   * app runs [newMessage] as a new invocation under [invocationId], as Python 2.x does, instead of
   * throwing. ADK Kotlin 1.1.0 and earlier drop [stateDelta] when [newMessage] is null.
   */
  @Experimental
  override fun runAsync(
    userId: String,
    sessionId: String,
    invocationId: String?,
    newMessage: GenaiContent?,
    runConfig: JavaRunConfig,
    stateDelta: MutableMap<String, Any>?,
  ): Flowable<JavaEvent> {
    // Defer so a request-conversion or RunConfig rejection surfaces via onError, not at the call
    // site - matching the base Runner and this class's own runLive.
    return Flowable.defer {
      val events =
        ktRunner
          .runAsync(
            userId = userId,
            sessionId = sessionId,
            invocationId = invocationId,
            newMessage = newMessage?.let { withUserRole(ContentCodec.fromJava(it)) },
            // Translate the Java REMOVED sentinel so a caller-passed deletion deletes the key
            // rather than storing it as a value (identity-matched by the engine).
            stateDelta = stateDelta?.let { stateDeltaFromJava(it) },
            runConfig = RunConfigCodec.fromJava(runConfig),
          )
          .map { EventCodec.toJava(it) }
          .asFlowable()
      honoringAutoCreate(userId, sessionId, runConfig, events)
    }
  }

  /**
   * Runs [session]'s conversation live on the Kotlin engine, which reads the session from its own
   * service by app, user and id, and writes events to that stored copy rather than [session]. A
   * session of another app, or one that service lacks, fails the stream even when [runConfig] sets
   * `autoCreateSession`.
   */
  override fun runLive(
    session: JavaSession,
    liveRequestQueue: LiveRequestQueue,
    runConfig: JavaRunConfig,
  ): Flowable<JavaEvent> = Flowable.defer {
    require(session.appName() == appName()) { SESSION_NOT_HELD }
    whenSessionExists(
      session.userId(),
      session.id(),
      SESSION_NOT_HELD,
      liveEvents(session.userId(), session.id(), liveRequestQueue, runConfig),
    )
  }

  /**
   * Runs a live conversation on the Kotlin engine: the Java queue's requests feed it, and its
   * events arrive on `dispatcher`'s threads, never the sender's, until the queue closes or the
   * stream is cancelled, which closes the connection.
   */
  override fun runLive(
    userId: String,
    sessionId: String,
    liveRequestQueue: LiveRequestQueue,
    runConfig: JavaRunConfig,
  ): Flowable<JavaEvent> = Flowable.defer {
    honoringAutoCreate(
      userId,
      sessionId,
      runConfig,
      liveEvents(userId, sessionId, liveRequestQueue, runConfig),
    )
  }

  /**
   * Returns [events], first checking that the session exists unless [runConfig] auto-creates it:
   * the Kotlin engine always creates a missing session, while the Java Runner errors unless told
   * otherwise.
   */
  private fun honoringAutoCreate(
    userId: String,
    sessionId: String,
    runConfig: JavaRunConfig,
    events: Flowable<JavaEvent>,
  ): Flowable<JavaEvent> =
    if (runConfig.autoCreateSession()) {
      events
    } else {
      whenSessionExists(userId, sessionId, SESSION_MISSING, events)
    }

  /**
   * Returns [events] if the Kotlin runner's service holds the session, else a stream failing with
   * [notFoundMessage].
   */
  private fun whenSessionExists(
    userId: String,
    sessionId: String,
    notFoundMessage: String,
    events: Flowable<JavaEvent>,
  ): Flowable<JavaEvent> =
    sessionService()
      .getSession(appName(), userId, sessionId, Optional.empty())
      .map { true }
      .defaultIfEmpty(false)
      .flatMapPublisher { exists ->
        if (exists) events else Flowable.error(IllegalArgumentException(notFoundMessage))
      }

  private fun liveEvents(
    userId: String,
    sessionId: String,
    javaQueue: LiveRequestQueue,
    runConfig: JavaRunConfig,
  ): Flowable<JavaEvent> {
    val ktRunConfig = RunConfigCodec.fromJavaLive(runConfig)
    val ktQueue = KtLiveRequestQueue()
    // Called at subscribe time so the Kotlin runner captures the subscriber's trace context.
    val ktEvents = ktRunner.runLive(userId, sessionId, ktQueue, ktRunConfig)
    return flow {
        coroutineScope {
          // Safe: both queues are thread-safe, and the Kotlin one never blocks the pump.
          @Suppress("UnsafeCoroutineCrossing")
          val pump = launch { pumpRequests(javaQueue, ktQueue) }
          try {
            emitAll(ktEvents.map { EventCodec.toJava(it) })
          } finally {
            pump.cancel()
            ktQueue.close()
          }
        }
      }
      // The runner's dispatcher, not Unconfined, so a Java sender never runs the live loop.
      .asFlowable(dispatcher)
  }

  /**
   * Feeds the Java queue's requests to [ktQueue], closing it once the Java queue closes or ends.
   */
  private suspend fun pumpRequests(javaQueue: LiveRequestQueue, ktQueue: KtLiveRequestQueue) {
    try {
      // Unbounded, so a burst can't overflow the Java queue.
      javaQueue.get().asFlow().buffer(Channel.UNLIMITED).collect { request ->
        val content = request.content().getOrNull()
        val blob = request.blob().getOrNull()
        // Content wins over a blob, and an empty request closes the run.
        when {
          content != null -> {
            val ktContent = withUserRole(ContentCodec.fromJava(content))
            // A function response goes out as a tool response, which has no partial form.
            val partial =
              request.isPartial() && ktContent.parts.none { it.functionResponse != null }
            ktQueue.sendContent(ktContent, partial)
          }
          blob != null -> blob.toRealtimeInput()?.let { ktQueue.sendRealtime(it) }
          else -> ktQueue.close()
        }
        if (request.shouldClose()) ktQueue.close()
      }
    } finally {
      ktQueue.close()
    }
  }

  /**
   * Maps a blob with an `audio/` MIME type to audio input and one with `image/` to a video frame,
   * or drops any other blob with a warning.
   */
  private fun GenaiBlob.toRealtimeInput(): RealtimeInput? {
    val ktBlob = PartCodec.blobFromJava(this)
    val mimeType = ktBlob.mimeType.orEmpty()
    return when {
      mimeType.startsWith("audio/") -> RealtimeInput.Audio(ktBlob)
      mimeType.startsWith("image/") -> RealtimeInput.Video(ktBlob)
      else -> {
        logger.warn { "Live input blob not sent, MIME type not supported: \"$mimeType\"" }
        null
      }
    }
  }

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

  /**
   * Defaults a role-less message to the user role, as Python does, since model requests omit
   * role-less content.
   */
  private fun withUserRole(content: KtContent): KtContent =
    if (content.role.isNullOrEmpty()) content.copy(role = KtRole.USER) else content

  companion object {
    private val logger = LoggerFactory.getLogger(KtRunnerToJava::class)
    private const val SESSION_MISSING = "Session not found and autoCreateSession=false"
    private const val SESSION_NOT_HELD =
      "Session not found under this runner's app in the Kotlin runner's session service"
  }
}
