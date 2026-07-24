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
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.SessionCodec
import com.google.adk.tokt.codecs.ktSessionToJava
import java.util.Optional
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitSingleOrNull
import kotlinx.coroutines.withContext

/**
 * A Kotlin [KtSessionService] backed by an ADK Java [JavaBaseSessionService] - the reverse of
 * [KtSessionServiceToJava]. It lets the Kotlin runner drive a Java app's own session service
 * (in-memory, Vertex AI, custom, ...) when a Java `Runner` runs on the Kotlin. Each call converts
 * arguments, suspends on the Java service's RxJava result, and converts back.
 *
 * `appendEvent` persists through the Java service (keyed by appName/userId/id) and then keeps the
 * in-memory Kotlin [session] the runner holds in sync via the default implementation.
 */
internal class JavaSessionServiceToKt(internal val service: JavaBaseSessionService) :
  KtSessionService {

  // All Java-service calls are awaited on Dispatchers.IO: a user's Java service (in-memory, Vertex,
  // custom) may be blocking-on-subscribe, so it must not run on the coroutine driving the agent
  // loop.

  override suspend fun createSession(key: SessionKey, state: Map<String, Any>?): KtSession =
    withContext(Dispatchers.IO) {
      SessionCodec.toKotlin(service.createSession(key.appName, key.userId, state, key.id).await())
    }

  override suspend fun getSession(key: SessionKey, config: KtGetSessionConfig?): KtSession? =
    withContext(Dispatchers.IO) {
      service
        .getSession(
          key.appName,
          key.userId,
          requireNotNull(key.id) { "SessionKey.id must not be null for getSession" },
          Optional.ofNullable(config?.toJava()),
        )
        .awaitSingleOrNull()
        ?.let { SessionCodec.toKotlin(it) }
    }

  override suspend fun listSessions(appName: String, userId: String): KtListSessionsResponse =
    withContext(Dispatchers.IO) {
      KtListSessionsResponse(
        sessions =
          service.listSessions(appName, userId).await().sessions().map { SessionCodec.toKotlin(it) }
      )
    }

  override suspend fun deleteSession(key: SessionKey) {
    withContext(Dispatchers.IO) {
      service
        .deleteSession(
          key.appName,
          key.userId,
          requireNotNull(key.id) { "SessionKey.id must not be null for deleteSession" },
        )
        .await()
    }
  }

  override suspend fun listEvents(key: SessionKey): KtListEventsResponse {
    // A well-behaved Java service always returns a response; tolerate a null Single/response (e.g.
    // an unstubbed test double) as "no events" rather than crashing the Kotlin run.
    val response =
      withContext(Dispatchers.IO) {
        service
          .listEvents(
            key.appName,
            key.userId,
            requireNotNull(key.id) { "SessionKey.id must not be null for listEvents" },
          )
          ?.await()
      }
    return KtListEventsResponse(
      events = response?.events().orEmpty().map { EventCodec.fromJava(it) }
    )
  }

  override suspend fun appendEvent(session: KtSession, event: KtEvent): KtEvent {
    // Persist through the Java service first: the converted Java session carries the prior events,
    // so the service appends and persists this event (keyed by appName/userId/id).
    val javaSession = ktSessionToJava(session)
    withContext(Dispatchers.IO) {
      service.appendEvent(javaSession, EventCodec.toJava(event)).await()
    }
    // Keep the in-memory Kotlin session the runner holds in sync (state delta + event list).
    val appended = super.appendEvent(session, event)
    // The Java service is authoritative for lastUpdateTime (it may follow a different policy than
    // the Kotlin's event-timestamp default), so mirror its value onto the in-memory session.
    session.lastUpdateTime = javaSession.lastUpdateTime().toKotlinInstant()
    return appended
  }

  private fun KtGetSessionConfig.toJava(): JavaGetSessionConfig {
    val builder = JavaGetSessionConfig.builder()
    numRecentEvents?.let { builder.numRecentEvents(it) }
    afterTimestamp?.let { builder.afterTimestamp(it.toJavaInstant()) }
    return builder.build()
  }
}
