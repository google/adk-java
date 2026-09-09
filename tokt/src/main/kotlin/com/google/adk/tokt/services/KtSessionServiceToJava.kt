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
import com.google.adk.tokt.InteropDispatcher
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.KtBackedEventsView
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
import kotlinx.coroutines.rx3.rxCompletable
import kotlinx.coroutines.rx3.rxMaybe
import kotlinx.coroutines.rx3.rxSingle

/**
 * A Java [JavaBaseSessionService] backed by a Kotlin [KtSessionService] (reverse of the Java
 * service wrappers): a Java agent running on the Kotlin runner sees a Java session service whose
 * operations run on the Kotlin service.
 */
internal class KtSessionServiceToJava(internal val service: KtSessionService) :
  JavaBaseSessionService {

  @Deprecated("Deprecated in BaseSessionService")
  override fun createSession(
    appName: String,
    userId: String,
    state: ConcurrentMap<String, Any>?,
    sessionId: String?,
  ): Single<JavaSession> =
    rxSingle(InteropDispatcher) {
      ktSessionToJava(service.createSession(SessionKey(appName, userId, sessionId), state))
    }

  override fun getSession(
    appName: String,
    userId: String,
    sessionId: String,
    config: Optional<JavaGetSessionConfig>,
  ): Maybe<JavaSession> =
    rxMaybe(InteropDispatcher) {
      service
        .getSession(SessionKey(appName, userId, sessionId), config.getOrNull()?.toKotlin())
        ?.let { ktSessionToJava(it) }
    }

  override fun listSessions(appName: String, userId: String): Single<JavaListSessionsResponse> =
    rxSingle(InteropDispatcher) {
      val response = service.listSessions(appName, userId)
      JavaListSessionsResponse.builder()
        .sessions(response.sessions.map { ktSessionToJava(it) })
        .build()
    }

  override fun closeSession(session: JavaSession): Completable =
    rxCompletable(InteropDispatcher) { service.closeSession(SessionCodec.fromJava(session)) }

  override fun deleteSession(appName: String, userId: String, sessionId: String): Completable =
    rxCompletable(InteropDispatcher) {
      service.deleteSession(SessionKey(appName, userId, sessionId))
    }

  override fun listEvents(
    appName: String,
    userId: String,
    sessionId: String,
  ): Single<JavaListEventsResponse> =
    rxSingle(InteropDispatcher) {
      val response = service.listEvents(SessionKey(appName, userId, sessionId))
      val builder =
        JavaListEventsResponse.builder().events(response.events.map { EventCodec.toJava(it) })
      response.nextPageToken?.let { builder.nextPageToken(it) }
      builder.build()
    }

  /**
   * Appends [event] to [session] on the Kotlin service, then mirrors its stored session (merged
   * state, events, and last-update time) back into the caller's Java [session] so ADK Java's
   * `Runner` keeps observing the appended state in place.
   */
  override fun appendEvent(session: JavaSession, event: JavaEvent): Single<JavaEvent> =
    rxSingle(InteropDispatcher) {
      val key = SessionKey(session.appName(), session.userId(), session.id())
      service.appendEvent(SessionCodec.fromJava(session), EventCodec.fromJava(event))
      service.getSession(key)?.let { stored ->
        // Convert before touching the caller's session, then refill under the list's monitor so a
        // concurrent reader never observes a transiently-empty event list. Session.events() is a
        // Collections.synchronizedList, so its own monitor is the correct lock to hold here.
        // Mirror only into a session we can actually write. The live view this module hands out
        // (ktSessionToJavaLive, e.g. invocationContext.session()) is already backed by the Kotlin
        // session: its events() is a read-only converting view that throws on clear/addAll, and
        // its state() writes straight through, so it needs no mirroring and must not be mutated
        // here. A plain snapshot session (ktSessionToJava) does.
        if (session.events() is MutableList<*> && session.events() !is KtBackedEventsView) {
          val storedEvents = stored.events.map { EventCodec.toJava(it) }
          synchronized(session.events()) {
            session.events().clear()
            session.events().addAll(storedEvents)
          }
          // No lock: State is a ConcurrentMap, so readers never take this monitor. Drop stale keys
          // and overwrite in place, leaving no transiently-empty window.
          session.state().keys.retainAll(stored.state.keys)
          session.state().putAll(stored.state)
        }
        session.lastUpdateTime(stored.lastUpdateTime.toJavaInstant())
      }
      event
    }

  private fun JavaGetSessionConfig.toKotlin(): KtGetSessionConfig =
    KtGetSessionConfig(
      numRecentEvents = numRecentEvents().getOrNull(),
      afterTimestamp = afterTimestamp().getOrNull()?.toKotlinInstant(),
    )
}
