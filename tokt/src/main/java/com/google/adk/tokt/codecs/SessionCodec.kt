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

package com.google.adk.tokt.codecs

import com.google.adk.kt.sessions.Session as KtSession
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.State
import com.google.adk.sessions.Session as JavaSession
import kotlin.time.toKotlinInstant

/** Converts a Java session (key, state, and event history) to the Kotlin [KtSession]. */
internal object SessionCodec {

  fun toKotlin(session: JavaSession): KtSession =
    KtSession(
      key = SessionKey(appName = session.appName(), userId = session.userId(), id = session.id()),
      // Translate the Java removal sentinel so no foreign sentinel leaks into the Kotlin state.
      state = State(initialState = session.state().mapValues { stateValueFromJava(it.value) }),
      events = session.events().map { EventCodec.fromJava(it) }.toMutableList(),
      lastUpdateTime = session.lastUpdateTime().toKotlinInstant(),
    )
}
