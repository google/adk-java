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
import com.google.adk.kt.memory.MemoryService as KtMemoryService
import com.google.adk.kt.sessions.SessionService as KtSessionService
import com.google.adk.memory.BaseMemoryService as JavaBaseMemoryService
import com.google.adk.sessions.BaseSessionService as JavaBaseSessionService
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Service adapter factories that unwrap a round-tripped service rather than stacking adapters:
 * exposing a Kotlin service that is itself a wrapped Java service returns the original Java service
 * (and vice versa), so a Java -> Kt -> Java round-trip collapses to one reference with no extra
 * hop. A freshly wrapped service crosses on `dispatcher`; an unwrapped one is returned directly,
 * with no adapter and so no dispatcher hop.
 */
internal fun ktSessionServiceAsJava(
  service: KtSessionService,
  dispatcher: CoroutineDispatcher,
): JavaBaseSessionService =
  (service as? JavaSessionServiceToKt)?.service ?: KtSessionServiceToJava(service, dispatcher)

internal fun javaSessionServiceAsKt(
  service: JavaBaseSessionService,
  dispatcher: CoroutineDispatcher,
): KtSessionService =
  (service as? KtSessionServiceToJava)?.service ?: JavaSessionServiceToKt(service, dispatcher)

internal fun ktArtifactServiceAsJava(
  service: KtArtifactService,
  dispatcher: CoroutineDispatcher,
): JavaBaseArtifactService =
  (service as? JavaArtifactServiceToKt)?.service ?: KtArtifactServiceToJava(service, dispatcher)

internal fun javaArtifactServiceAsKt(
  service: JavaBaseArtifactService,
  dispatcher: CoroutineDispatcher,
): KtArtifactService =
  (service as? KtArtifactServiceToJava)?.service ?: JavaArtifactServiceToKt(service, dispatcher)

internal fun ktMemoryServiceAsJava(
  service: KtMemoryService,
  dispatcher: CoroutineDispatcher,
): JavaBaseMemoryService =
  (service as? JavaMemoryServiceToKt)?.service ?: KtMemoryServiceToJava(service, dispatcher)

internal fun javaMemoryServiceAsKt(
  service: JavaBaseMemoryService,
  dispatcher: CoroutineDispatcher,
): KtMemoryService =
  (service as? KtMemoryServiceToJava)?.service ?: JavaMemoryServiceToKt(service, dispatcher)
