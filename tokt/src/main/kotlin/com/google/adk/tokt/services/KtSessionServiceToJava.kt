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

package com.google.adk.tokt.services

import com.google.adk.events.Event as JavaEvent
import com.google.adk.kt.sessions.GetSessionConfig as KtGetSessionConfig
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService as KtSessionService
import com.google.adk.sessions.BaseSessionService as JavaBaseSessionService
import com.google.adk.sessions.GetSessionConfig as JavaGetSessionConfig
import com.google.adk.sessions.ListEventsResponse as JavaListEventsResponse
import com.google.adk.sessions.ListSessionsResponse as JavaListSessionsResponse
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.sessions.State as JavaState
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.KtBackedEventsMutableView
import com.google.adk.tokt.codecs.SessionCodec
import com.google.adk.tokt.codecs.ktSessionToJava
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import java.util.Optional
import java.util.concurrent.ConcurrentMap
import kotlin.jvm.optionals.getOrNull
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.rx3.rxCompletable
import kotlinx.coroutines.rx3.rxMaybe
import kotlinx.coroutines.rx3.rxSingle

/**
 * A Java [JavaBaseSessionService] backed by a Kotlin [KtSessionService] (reverse of the Java
 * service wrappers): a Java agent running on the Kotlin runner sees a Java session service whose
 * operations run on the Kotlin service, bridged on `dispatcher`.
 */
internal class KtSessionServiceToJava(
  internal val service: KtSessionService,
  private val dispatcher: CoroutineDispatcher,
) : JavaBaseSessionService {

  @Deprecated("Deprecated in BaseSessionService")
  override fun createSession(
    appName: String,
    userId: String,
    state: ConcurrentMap<String, Any>?,
    sessionId: String?,
  ): Single<JavaSession> =
    rxSingle(dispatcher) {
      ktSessionToJava(service.createSession(SessionKey(appName, userId, sessionId), state))
    }

  override fun getSession(
    appName: String,
    userId: String,
    sessionId: String,
    config: Optional<JavaGetSessionConfig>,
  ): Maybe<JavaSession> =
    rxMaybe(dispatcher) {
      service
        .getSession(SessionKey(appName, userId, sessionId), config.getOrNull()?.toKotlin())
        ?.let { ktSessionToJava(it) }
    }

  override fun listSessions(appName: String, userId: String): Single<JavaListSessionsResponse> =
    rxSingle(dispatcher) {
      val response = service.listSessions(appName, userId)
      JavaListSessionsResponse.builder()
        .sessions(response.sessions.map { ktSessionToJava(it) })
        .build()
    }

  override fun closeSession(session: JavaSession): Completable =
    rxCompletable(dispatcher) { service.closeSession(SessionCodec.fromJava(session)) }

  override fun deleteSession(appName: String, userId: String, sessionId: String): Completable =
    rxCompletable(dispatcher) { service.deleteSession(SessionKey(appName, userId, sessionId)) }

  override fun listEvents(
    appName: String,
    userId: String,
    sessionId: String,
  ): Single<JavaListEventsResponse> =
    rxSingle(dispatcher) {
      val response = service.listEvents(SessionKey(appName, userId, sessionId))
      val builder =
        JavaListEventsResponse.builder().events(response.events.map { EventCodec.toJava(it) })
      response.nextPageToken?.let { builder.nextPageToken(it) }
      builder.build()
    }

  /**
   * Appends [event] on the Kotlin service. For a live view ([KtBackedEventsMutableView]), the
   * service appends directly to the running Kotlin session, keeping that session's `lastUpdateTime`
   * in step with the store; any other [session] is updated in place by
   * [JavaBaseSessionService.appendEvent], plus the `temp:` state it skips. In both cases,
   * [session]'s `lastUpdateTime` is refreshed from the Kotlin session the service updated.
   */
  override fun appendEvent(session: JavaSession, event: JavaEvent): Single<JavaEvent> =
    rxSingle(dispatcher) {
      // ADK base session services ignore partial events, so the in-place update must too.
      if (event.partial().getOrNull() == true) return@rxSingle event
      val backing = (session.events() as? KtBackedEventsMutableView)?.session
      val ktSession = backing ?: SessionCodec.fromJava(session)
      service.appendEvent(ktSession, EventCodec.fromJava(event))
      if (backing == null) {
        super.appendEvent(session, event)
        applyTempState(session, event)
      }
      session.lastUpdateTime(ktSession.lastUpdateTime.toJavaInstant())
      event
    }

  /**
   * Applies [event]'s `temp:` state to [session] the way Kotlin's `State.applyTempDelta` does for a
   * Kotlin session, since the Java base skips it.
   */
  private fun applyTempState(session: JavaSession, event: JavaEvent) {
    val delta = event.actions()?.stateDelta() ?: return
    for ((key, value) in delta) {
      if (!key.startsWith(JavaState.TEMP_PREFIX)) continue
      if (value === JavaState.REMOVED) {
        session.state().remove(key)
      } else {
        session.state()[key] = value
      }
    }
  }

  private fun JavaGetSessionConfig.toKotlin(): KtGetSessionConfig =
    KtGetSessionConfig(
      numRecentEvents = numRecentEvents().getOrNull(),
      afterTimestamp = afterTimestamp().getOrNull()?.toKotlinInstant(),
    )
}
