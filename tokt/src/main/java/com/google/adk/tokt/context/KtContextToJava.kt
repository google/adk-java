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

package com.google.adk.tokt.context

import com.google.adk.agents.BaseAgent as JavaBaseAgent
import com.google.adk.agents.InvocationContext as JavaInvocationContext
import com.google.adk.kt.agents.InvocationContext as KtInvocationContext
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.ktSessionToJava
import com.google.adk.tokt.services.ktArtifactServiceAsJava
import com.google.adk.tokt.services.ktMemoryServiceAsJava
import com.google.adk.tokt.services.ktSessionServiceAsJava
import java.util.Optional

/**
 * Builds a Java [JavaInvocationContext] from a Kotlin [context] for [javaAgent], exposing services
 * as Java views (unwrapped to the original Java service when the Kotlin one is itself a wrapped
 * Java service) and a live view of the Kotlin session. Used by [JavaPluginToKt] so a Java plugin's
 * callbacks hit the Kotlin services and see the current session events rather than a stale
 * snapshot. Services are wired only when present (parity with the tool/agent-level
 * [KtInvocationContextToJavaView]).
 */
@OptIn(FrameworkInternalApi::class)
internal fun ktInvocationContextToJava(
  context: KtInvocationContext,
  javaAgent: JavaBaseAgent,
): JavaInvocationContext {
  val builder =
    JavaInvocationContext.builder()
      .invocationId(context.invocationId)
      .agent(javaAgent)
      .session(ktSessionToJava(context.session))
      .callbackContextData(context.frameworkData.callbackContextData)
  context.sessionService?.let { builder.sessionService(ktSessionServiceAsJava(it)) }
  context.artifactService?.let { builder.artifactService(ktArtifactServiceAsJava(it)) }
  context.memoryService?.let { builder.memoryService(ktMemoryServiceAsJava(it)) }
  context.userContent?.let { builder.userContent(ContentCodec.toJava(it)) }
  return LiveSessionInvocationContext(builder, context)
}

/**
 * Java context backed live by the Kotlin [ktContext]: [session] re-projects the current Kotlin
 * session on each read, and branch / end-invocation read (and write, for end-invocation) through to
 * the Kotlin context - parity with the tool/agent-level [KtInvocationContextToJavaView].
 */
private class LiveSessionInvocationContext(
  builder: JavaInvocationContext.Builder,
  private val ktContext: KtInvocationContext,
) : JavaInvocationContext(builder) {
  override fun session(): JavaSession = ktSessionToJava(ktContext.session)

  override fun branch(): Optional<String> = Optional.ofNullable(ktContext.branch)

  override fun branch(branch: String?) {
    // kt InvocationContext.branch is immutable (val); branch writes are not propagated.
  }

  override fun endInvocation(): Boolean = ktContext.isEndOfInvocation

  override fun setEndInvocation(endInvocation: Boolean) {
    ktContext.isEndOfInvocation = endInvocation
  }
}
