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

/**
 * Service adapter factories that unwrap a round-tripped service rather than stacking adapters:
 * exposing a Kotlin service that is itself a wrapped Java service returns the original Java service
 * (and vice versa), so a Java -> Kt -> Java round-trip collapses to one reference with no extra
 * hop.
 */
internal fun ktSessionServiceAsJava(service: KtSessionService): JavaBaseSessionService =
  (service as? JavaSessionServiceToKt)?.service ?: KtSessionServiceToJava(service)

internal fun javaSessionServiceAsKt(service: JavaBaseSessionService): KtSessionService =
  (service as? KtSessionServiceToJava)?.service ?: JavaSessionServiceToKt(service)

internal fun ktArtifactServiceAsJava(service: KtArtifactService): JavaBaseArtifactService =
  (service as? JavaArtifactServiceToKt)?.service ?: KtArtifactServiceToJava(service)

internal fun javaArtifactServiceAsKt(service: JavaBaseArtifactService): KtArtifactService =
  (service as? KtArtifactServiceToJava)?.service ?: JavaArtifactServiceToKt(service)

internal fun ktMemoryServiceAsJava(service: KtMemoryService): JavaBaseMemoryService =
  (service as? JavaMemoryServiceToKt)?.service ?: KtMemoryServiceToJava(service)

internal fun javaMemoryServiceAsKt(service: JavaBaseMemoryService): KtMemoryService =
  (service as? KtMemoryServiceToJava)?.service ?: JavaMemoryServiceToKt(service)
