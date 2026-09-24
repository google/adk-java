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
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Reverse interop entry point: [asJavaRunner] wraps an ADK Kotlin-engine `Runner` in a real ADK
 * Java `Runner`, so it can be injected into code written against the Java runner. The wrapper's
 * `runAsync` streams `Event`s backed by the Kotlin engine; live mode is not bridged. The forward
 * direction (Java components onto the Kotlin engine) lives in [com.google.adk.tokt.JavaAdkToKt].
 */
object KotlinAdkToJava {

  /**
   * Exposes a Kotlin-engine [runner] as an ADK Java `Runner`. Its reverse service adapters bridge
   * RxJava calls onto the Kotlin engine via `dispatcher` (default `Dispatchers.IO`). That
   * dispatcher must be able to run nested bridged calls in parallel: if code on one of its threads
   * (such as a bridged call's RxJava callback) blocks on another bridged call, it holds that thread
   * until the second call returns, so a single-threaded or tightly bounded dispatcher can deadlock.
   */
  @JvmStatic
  @JvmOverloads
  fun asJavaRunner(
    runner: KtRunner,
    dispatcher: CoroutineDispatcher = InteropDispatcher,
  ): JavaRunner = KtRunnerToJava(runner, dispatcher)
}
