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

package com.google.adk.tokt.adapters

import com.google.adk.agents.BaseAgent as JavaBaseAgent
import com.google.adk.kt.agents.BaseAgent as KtBaseAgent

/**
 * The Java view of a Kotlin agent, for handing to a Java plugin callback. An agent that is itself
 * an adapted Java one is unwrapped to the original rather than wrapped again, so a Java plugin
 * pairing callbacks by agent identity sees the instance it knows.
 *
 * Note the unwrapped case returns a *runnable* Java agent, so "a plugin must not run the agent it
 * is given" ([KtAgentToJava]) is convention there rather than something the type enforces.
 */
internal fun ktAgentAsJava(ktAgent: KtBaseAgent): JavaBaseAgent =
  (ktAgent as? JavaAgentToKt)?.javaAgent ?: KtAgentToJava(ktAgent)

/**
 * The Kotlin agent for a Java one, so it can run on the engine ([JavaAgentToKt]). A Java agent that
 * is itself a view of a Kotlin agent is unwrapped, so a Kt -> Java -> Kt round trip collapses to
 * the original instead of running the engine through two adapters.
 */
internal fun javaAgentAsKt(javaAgent: JavaBaseAgent): KtBaseAgent =
  (javaAgent as? KtAgentToJava)?.ktAgent ?: JavaAgentToKt(javaAgent)
