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

import com.google.adk.artifacts.BaseArtifactService as JavaBaseArtifactService
import com.google.adk.kt.artifacts.ArtifactService as KtArtifactService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Part as KtPart
import com.google.adk.tokt.codecs.PartCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitSingleOrNull
import kotlinx.coroutines.withContext

/**
 * A Kotlin [KtArtifactService] backed by an ADK Java [JavaBaseArtifactService] - the reverse of
 * [KtArtifactServiceToJava] - so the Kotlin runner can drive a Java app's own artifact service when
 * a Java `Runner` runs on the Kotlin. Parts are converted with [PartCodec]. All Java-service calls
 * run on Dispatchers.IO since a user's artifact service may block on subscribe.
 */
internal class JavaArtifactServiceToKt(internal val service: JavaBaseArtifactService) :
  KtArtifactService {

  override suspend fun saveArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: KtPart,
  ): Int =
    withContext(Dispatchers.IO) {
      service
        .saveArtifact(
          sessionKey.appName,
          sessionKey.userId,
          id(sessionKey),
          filename,
          toJava(artifact),
        )
        .await()
    }

  override suspend fun saveAndReloadArtifact(
    sessionKey: SessionKey,
    filename: String,
    artifact: KtPart,
  ): KtPart =
    withContext(Dispatchers.IO) {
      toKotlin(
        service
          .saveAndReloadArtifact(
            sessionKey.appName,
            sessionKey.userId,
            id(sessionKey),
            filename,
            toJava(artifact),
          )
          .await()
      )
    }

  override suspend fun loadArtifact(
    sessionKey: SessionKey,
    filename: String,
    version: Int?,
  ): KtPart? =
    withContext(Dispatchers.IO) {
      service
        .loadArtifact(sessionKey.appName, sessionKey.userId, id(sessionKey), filename, version)
        .awaitSingleOrNull()
        ?.let { PartCodec.toKotlin(it) }
    }

  override suspend fun listArtifactKeys(sessionKey: SessionKey): List<String> =
    withContext(Dispatchers.IO) {
      service
        .listArtifactKeys(sessionKey.appName, sessionKey.userId, id(sessionKey))
        .await()
        .filenames()
    }

  override suspend fun deleteArtifact(sessionKey: SessionKey, filename: String) {
    withContext(Dispatchers.IO) {
      service
        .deleteArtifact(sessionKey.appName, sessionKey.userId, id(sessionKey), filename)
        .await()
    }
  }

  override suspend fun listVersions(sessionKey: SessionKey, filename: String): List<Int> =
    withContext(Dispatchers.IO) {
      service.listVersions(sessionKey.appName, sessionKey.userId, id(sessionKey), filename).await()
    }

  private fun id(key: SessionKey): String =
    requireNotNull(key.id) { "SessionKey.id must not be null for artifact operations" }

  private fun toJava(part: KtPart) =
    requireNotNull(PartCodec.toJava(part)) { "Artifact part is empty or unmapped" }

  private fun toKotlin(part: com.google.genai.types.Part): KtPart =
    requireNotNull(PartCodec.toKotlin(part)) { "Artifact part is empty or unmapped" }
}
