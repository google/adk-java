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

import com.google.adk.kt.events.Event as KtEvent
import com.google.adk.kt.sessions.GetSessionConfig as KtGetSessionConfig
import com.google.adk.kt.sessions.ListEventsResponse as KtListEventsResponse
import com.google.adk.kt.sessions.ListSessionsResponse as KtListSessionsResponse
import com.google.adk.kt.sessions.Session as KtSession
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService as KtSessionService
import com.google.adk.sessions.BaseSessionService as JavaBaseSessionService
import com.google.adk.sessions.GetSessionConfig as JavaGetSessionConfig
import com.google.adk.tokt.InteropDispatcher
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.SessionCodec
import com.google.adk.tokt.codecs.ktSessionToJava
import com.google.adk.tokt.codecs.sessionId
import java.util.Optional
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitSingleOrNull
import kotlinx.coroutines.withContext

/**
 * A Kotlin [KtSessionService] backed by an ADK Java [JavaBaseSessionService] - the reverse of
 * [KtSessionServiceToJava]. It lets the Kotlin runner drive a Java app's own session service
 * (in-memory, Vertex AI, custom, ...) when a Java `Runner` runs on the Kotlin engine. Each call
 * converts arguments, suspends on the Java service's RxJava result, and converts back.
 *
 * `appendEvent` persists through the Java service (keyed by appName/userId/id) and then keeps the
 * in-memory Kotlin [session] the runner holds in sync via the default implementation.
 */
internal class JavaSessionServiceToKt(internal val service: JavaBaseSessionService) :
  KtSessionService {

  // All Java-service calls are awaited on InteropDispatcher: a user's Java service (in-memory,
  // Vertex,
  // custom) may be blocking-on-subscribe, so it must not run on the coroutine driving the agent
  // loop.

  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): KtSession =
    withContext(InteropDispatcher) {
      SessionCodec.fromJava(service.createSession(key.appName, key.userId, state, key.id).await())
    }

  override suspend fun getSession(key: SessionKey, config: KtGetSessionConfig?): KtSession? =
    withContext(InteropDispatcher) {
      service
        .getSession(key.appName, key.userId, sessionId(key), Optional.ofNullable(config?.toJava()))
        .awaitSingleOrNull()
        ?.let { SessionCodec.fromJava(it) }
    }

  override suspend fun listSessions(appName: String, userId: String): KtListSessionsResponse =
    withContext(InteropDispatcher) {
      KtListSessionsResponse(
        sessions =
          service.listSessions(appName, userId).await().sessions().map { SessionCodec.fromJava(it) }
      )
    }

  override suspend fun closeSession(session: KtSession) {
    withContext(InteropDispatcher) { service.closeSession(ktSessionToJava(session)).await() }
  }

  override suspend fun deleteSession(key: SessionKey) {
    withContext(InteropDispatcher) {
      service.deleteSession(key.appName, key.userId, sessionId(key)).await()
    }
  }

  override suspend fun listEvents(key: SessionKey): KtListEventsResponse {
    // A well-behaved Java service always returns a response; tolerate a null Single/response (e.g.
    // an unstubbed test double) as "no events" rather than crashing the Kotlin run.
    val response =
      withContext(InteropDispatcher) {
        service.listEvents(key.appName, key.userId, sessionId(key))?.await()
      }
    return KtListEventsResponse(
      events = response?.events().orEmpty().map { EventCodec.fromJava(it) },
      nextPageToken = response?.nextPageToken()?.orElse(null),
    )
  }

  override suspend fun appendEvent(session: KtSession, event: KtEvent): KtEvent {
    // Persist through the Java service first: the converted Java session carries the prior events,
    // so the service appends and persists this event (keyed by appName/userId/id).
    val javaSession = ktSessionToJava(session)
    withContext(InteropDispatcher) {
      service.appendEvent(javaSession, EventCodec.toJava(event)).await()
    }
    // Keep the in-memory Kotlin session the runner holds in sync (state delta + event list); this
    // also advances lastUpdateTime to the event timestamp.
    val appended = super.appendEvent(session, event)
    // Mirror the Java service's lastUpdateTime only when it moved further forward: `javaSession` is
    // the pre-append snapshot, and BaseSessionService.appendEvent does not touch the field, so
    // assigning it unconditionally would rewind the session for every service that leaves it alone.
    javaSession
      .lastUpdateTime()
      .toKotlinInstant()
      .takeIf { it > session.lastUpdateTime }
      ?.let { session.lastUpdateTime = it }
    return appended
  }

  private fun KtGetSessionConfig.toJava(): JavaGetSessionConfig {
    val builder = JavaGetSessionConfig.builder()
    numRecentEvents?.let { builder.numRecentEvents(it) }
    afterTimestamp?.let { builder.afterTimestamp(it.toJavaInstant()) }
    return builder.build()
  }
}
