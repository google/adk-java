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
import com.google.adk.tokt.codecs.EventCodec
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.rxCompletable
import kotlinx.coroutines.rx3.rxMaybe
import kotlinx.coroutines.rx3.rxSingle

/**
 * A Java [JavaBaseSessionService] backed by a Kotlin [KtSessionService] (reverse of the Java
 * service wrappers): a Java agent running on the Kotlin runner sees a Java session service whose
 * operations run on the Kotlin.
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
    rxSingle(Dispatchers.IO) {
      ktSessionToJava(service.createSession(SessionKey(appName, userId, sessionId), state))
    }

  override fun getSession(
    appName: String,
    userId: String,
    sessionId: String,
    config: Optional<JavaGetSessionConfig>,
  ): Maybe<JavaSession> =
    rxMaybe(Dispatchers.IO) {
      service
        .getSession(SessionKey(appName, userId, sessionId), config.getOrNull()?.toKotlin())
        ?.let { ktSessionToJava(it) }
    }

  override fun listSessions(appName: String, userId: String): Single<JavaListSessionsResponse> =
    rxSingle(Dispatchers.IO) {
      val response = service.listSessions(appName, userId)
      JavaListSessionsResponse.builder()
        .sessions(response.sessions.map { ktSessionToJava(it) })
        .build()
    }

  override fun deleteSession(appName: String, userId: String, sessionId: String): Completable =
    rxCompletable(Dispatchers.IO) { service.deleteSession(SessionKey(appName, userId, sessionId)) }

  override fun listEvents(
    appName: String,
    userId: String,
    sessionId: String,
  ): Single<JavaListEventsResponse> =
    rxSingle(Dispatchers.IO) {
      val response = service.listEvents(SessionKey(appName, userId, sessionId))
      JavaListEventsResponse.builder().events(response.events.map { EventCodec.toJava(it) }).build()
    }

  /**
   * Appends [event] to [session] on the Kotlin, then mirrors the Kotlin's stored session (merged
   * state, events, and last-update time) back into the caller's Java [session] so ADK Java's
   * `Runner` keeps observing the appended state in place.
   */
  override fun appendEvent(session: JavaSession, event: JavaEvent): Single<JavaEvent> =
    rxSingle(Dispatchers.IO) {
      val key = SessionKey(session.appName(), session.userId(), session.id())
      service.appendEvent(SessionCodec.toKotlin(session), EventCodec.fromJava(event))
      service.getSession(key)?.let { stored ->
        // Convert before touching the caller's session, then refill under the list's monitor so a
        // concurrent reader never observes a transiently-empty event list. Session.events() is a
        // Collections.synchronizedList, so its own monitor is the correct lock to hold here.
        val storedEvents = stored.events.map { EventCodec.toJava(it) }
        synchronized(session.events()) {
          session.events().clear()
          session.events().addAll(storedEvents)
        }
        synchronized(session.state()) {
          session.state().clear()
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
