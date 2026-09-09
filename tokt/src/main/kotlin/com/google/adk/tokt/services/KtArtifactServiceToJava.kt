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
import com.google.adk.tokt.InteropDispatcher
import com.google.adk.tokt.codecs.PartCodec
import com.google.common.collect.ImmutableList
import com.google.genai.types.Part as GenaiPart
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.rx3.rxCompletable
import kotlinx.coroutines.rx3.rxMaybe
import kotlinx.coroutines.rx3.rxSingle

/**
 * A Java [JavaBaseArtifactService] backed by a Kotlin [KtArtifactService] - the reverse of the Java
 * service wrappers - so a Java agent running under the Kotlin runner sees a Java artifact service
 * backed by the Kotlin service. Artifacts are converted with [PartCodec].
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
    rxSingle(InteropDispatcher) {
      service.saveArtifact(
        SessionKey(appName, userId, sessionId),
        filename,
        PartCodec.fromJavaOrThrow(artifact),
      )
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
    rxSingle(InteropDispatcher) {
      PartCodec.toJavaOrThrow(
        service.saveAndReloadArtifact(
          SessionKey(appName, userId, sessionId),
          filename,
          PartCodec.fromJavaOrThrow(artifact),
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
    rxMaybe(InteropDispatcher) {
      // toJavaOrThrow, not toJava: an artifact that exists but cannot be converted must be
      // rejected, not reported as absent.
      service.loadArtifact(SessionKey(appName, userId, sessionId), filename, version)?.let {
        PartCodec.toJavaOrThrow(it)
      }
    }

  // Suppressed: the Java service's return type is a Guava ImmutableList, so building one
  // directly avoids a copy the Kotlin API would force.
  @Suppress("PreferKotlinApi")
  override fun listArtifactKeys(
    appName: String,
    userId: String,
    sessionId: String,
  ): Single<JavaListArtifactsResponse> =
    rxSingle(InteropDispatcher) {
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
    rxCompletable(InteropDispatcher) {
      service.deleteArtifact(SessionKey(appName, userId, sessionId), filename)
    }

  @Suppress("PreferKotlinApi")
  override fun listVersions(
    appName: String,
    userId: String,
    sessionId: String,
    filename: String,
  ): Single<ImmutableList<Int>> =
    rxSingle(InteropDispatcher) {
      ImmutableList.copyOf(service.listVersions(SessionKey(appName, userId, sessionId), filename))
    }
}
