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

import com.google.adk.kt.memory.MemoryService as KtMemoryService
import com.google.adk.memory.BaseMemoryService as JavaBaseMemoryService
import com.google.adk.memory.SearchMemoryResponse as JavaSearchMemoryResponse
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.tokt.codecs.MemoryEntryCodec
import com.google.adk.tokt.codecs.SessionCodec
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.rxCompletable
import kotlinx.coroutines.rx3.rxSingle

/**
 * A Java [JavaBaseMemoryService] backed by a Kotlin [KtMemoryService] - the reverse of the Java
 * service wrappers - so a Java agent running under the Kotlin runner sees a Java memory service
 * whose operations are the Kotlin's.
 */
internal class KtMemoryServiceToJava(internal val service: KtMemoryService) :
  JavaBaseMemoryService {

  override fun addSessionToMemory(session: JavaSession): Completable =
    rxCompletable(Dispatchers.IO) { service.addSessionToMemory(SessionCodec.toKotlin(session)) }

  override fun searchMemory(
    appName: String,
    userId: String,
    query: String,
  ): Single<JavaSearchMemoryResponse> =
    rxSingle(Dispatchers.IO) {
      MemoryEntryCodec.toJava(service.searchMemory(appName, userId, query))
    }
}
