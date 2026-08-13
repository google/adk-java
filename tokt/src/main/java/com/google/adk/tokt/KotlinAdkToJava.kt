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

package com.google.adk.tokt

import com.google.adk.kt.runners.Runner as KtRunner
import com.google.adk.runner.Runner as JavaRunner

/**
 * Reverse interop entry point: exposes an ADK Kotlin-engine [KtRunner] through the ADK Java
 * [JavaRunner] surface, so it can be injected into code written against the Java runner. The
 * returned runner is a real [JavaRunner] whose `runAsync` streams `Event`s backed by the Kotlin
 * engine; live mode is not bridged. The forward direction (Java components onto the Kotlin engine)
 * lives in [com.google.adk.tokt.JavaAdkToKt].
 */
object KotlinAdkToJava {

  /** Exposes a Kotlin-engine [runner] as an ADK Java [JavaRunner]. */
  @JvmStatic
  fun asJavaRunner(runner: KtRunner): JavaRunner = KtRunnerToJava(runner)
}
