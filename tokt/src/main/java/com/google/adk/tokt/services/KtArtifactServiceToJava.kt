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
import com.google.adk.artifacts.ListArtifactsResponse as JavaListArtifactsResponse
import com.google.adk.kt.artifacts.ArtifactService as KtArtifactService
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.types.Part as KtPart
import com.google.adk.tokt.codecs.PartCodec
import com.google.common.collect.ImmutableList
import com.google.genai.types.Part as GenaiPart
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.rxCompletable
import kotlinx.coroutines.rx3.rxMaybe
import kotlinx.coroutines.rx3.rxSingle

/**
 * A Java [JavaBaseArtifactService] backed by a Kotlin [KtArtifactService] - the reverse of the Java
 * service wrappers - so a Java agent running under the Kotlin runner sees a Java artifact service
 * whose operations are the Kotlin's. Artifacts are converted with [PartCodec].
 */
internal class KtArtifactServiceToJava(internal val service: KtArtifactService) :
  JavaBaseArtifactService {

  override fun saveArtifact(
    appName: String,
    userId: String,
    sessionId: String,
    filename: String,
    artifact: GenaiPart,
  ): Single<Int> =
    rxSingle(Dispatchers.IO) {
      service.saveArtifact(SessionKey(appName, userId, sessionId), filename, toKotlin(artifact))
    }

  // Override so a Java caller gets the Kotlin service's single-shot save-and-reload rather than the
  // Java default (a separate saveArtifact + loadArtifact round-trip).
  override fun saveAndReloadArtifact(
    appName: String,
    userId: String,
    sessionId: String,
    filename: String,
    artifact: GenaiPart,
  ): Single<GenaiPart> =
    rxSingle(Dispatchers.IO) {
      toJava(
        service.saveAndReloadArtifact(
          SessionKey(appName, userId, sessionId),
          filename,
          toKotlin(artifact),
        )
      )
    }

  override fun loadArtifact(
    appName: String,
    userId: String,
    sessionId: String,
    filename: String,
    version: Int?,
  ): Maybe<GenaiPart> =
    rxMaybe(Dispatchers.IO) {
      service.loadArtifact(SessionKey(appName, userId, sessionId), filename, version)?.let {
        PartCodec.toJava(it)
      }
    }

  // Build a Guava ImmutableList directly to match the Java service's return type.
  @Suppress("PreferKotlinApi")
  override fun listArtifactKeys(
    appName: String,
    userId: String,
    sessionId: String,
  ): Single<JavaListArtifactsResponse> =
    rxSingle(Dispatchers.IO) {
      JavaListArtifactsResponse.builder()
        .filenames(
          ImmutableList.copyOf(service.listArtifactKeys(SessionKey(appName, userId, sessionId)))
        )
        .build()
    }

  override fun deleteArtifact(
    appName: String,
    userId: String,
    sessionId: String,
    filename: String,
  ): Completable =
    rxCompletable(Dispatchers.IO) {
      service.deleteArtifact(SessionKey(appName, userId, sessionId), filename)
    }

  // Build a Guava ImmutableList directly to match the Java service's return type.
  @Suppress("PreferKotlinApi")
  override fun listVersions(
    appName: String,
    userId: String,
    sessionId: String,
    filename: String,
  ): Single<ImmutableList<Int>> =
    rxSingle(Dispatchers.IO) {
      ImmutableList.copyOf(service.listVersions(SessionKey(appName, userId, sessionId), filename))
    }

  // Fail fast on an empty/unmapped part rather than persist an empty artifact (symmetric with
  // JavaArtifactServiceToKt).
  private fun toKotlin(part: GenaiPart): KtPart =
    requireNotNull(PartCodec.toKotlin(part)) { "Artifact part is empty or unmapped" }

  private fun toJava(part: KtPart): GenaiPart =
    requireNotNull(PartCodec.toJava(part)) { "Artifact part is empty or unmapped" }
}
