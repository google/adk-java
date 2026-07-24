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
import com.google.adk.kt.memory.SearchMemoryResponse as KtSearchMemoryResponse
import com.google.adk.kt.sessions.Session as KtSession
import com.google.adk.memory.BaseMemoryService as JavaBaseMemoryService
import com.google.adk.tokt.codecs.MemoryEntryCodec
import com.google.adk.tokt.codecs.ktSessionToJava
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext

/**
 * A Kotlin [KtMemoryService] backed by an ADK Java [JavaBaseMemoryService] - the reverse of
 * [KtMemoryServiceToJava] - so the Kotlin runner can drive a Java app's own memory service when a
 * Java `Runner` runs on the Kotlin.
 */
internal class JavaMemoryServiceToKt(internal val service: JavaBaseMemoryService) :
  KtMemoryService {

  override suspend fun addSessionToMemory(session: KtSession) {
    // On Dispatchers.IO: a user's Java memory service may block on subscribe.
    withContext(Dispatchers.IO) { service.addSessionToMemory(ktSessionToJava(session)).await() }
  }

  override suspend fun searchMemory(
    appName: String,
    userId: String,
    query: String,
  ): KtSearchMemoryResponse =
    withContext(Dispatchers.IO) {
      MemoryEntryCodec.toKotlin(service.searchMemory(appName, userId, query).await())
    }
}
