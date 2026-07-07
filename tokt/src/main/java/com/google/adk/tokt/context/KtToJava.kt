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

import com.google.adk.agents.InvocationContext as JavaInvocationContext
import com.google.adk.agents.ReadonlyContext as JavaReadonlyContext
import com.google.adk.events.Event as JavaEvent
import com.google.adk.kt.agents.InvocationContext as KtInvocationContext
import com.google.adk.kt.agents.ReadonlyContext as KtReadonlyContext
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.tools.ToolContext as KtToolContext
import com.google.adk.sessions.Session as JavaSession
import com.google.adk.tokt.adapters.ktAgentAsJava
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.KtEventActionsToJavaView
import com.google.adk.tokt.codecs.ToolConfirmationCodec
import com.google.adk.tokt.codecs.ktSessionToJava
import com.google.adk.tokt.services.ktArtifactServiceAsJava
import com.google.adk.tokt.services.ktMemoryServiceAsJava
import com.google.adk.tokt.services.ktSessionServiceAsJava
import com.google.adk.tools.ToolContext as JavaToolContext
import com.google.genai.types.Content as GenaiContent
import java.util.Collections
import java.util.Optional

/**
 * Kt -> Java context views used when the ADK Kotlin calls back into ADK Java code (a Java tool) or
 * returns results. These subclass the Java ADK context types and delegate straight to the Kotlin
 * context - no neutral interfaces, no identity registry. (Plain value conversions live in
 * `codecs`.)
 */

/**
 * A real Java [JavaReadonlyContext] backed by a Kotlin [KtReadonlyContext], used when the Kotlin
 * pulls tools from a Java toolset ([JavaToolsetToKt]). Every accessor reads through to the Kotlin
 * context; [invocationContext] is not available (the Kotlin exposes only the read-only view).
 */
internal class KtReadonlyContextToJavaView(private val ctx: KtReadonlyContext) :
  JavaReadonlyContext(null) {

  override fun userContent(): Optional<GenaiContent> =
    Optional.ofNullable(ctx.userContent?.let { ContentCodec.toJava(it) })

  override fun invocationId(): String = ctx.invocationId

  override fun branch(): Optional<String> = Optional.ofNullable(ctx.branch)

  override fun agentName(): String = ctx.agentName

  override fun userId(): String = ctx.userId

  override fun sessionId(): String = ctx.session.key.id ?: ""

  override fun events(): List<JavaEvent> =
    Collections.unmodifiableList(ctx.session.events.map { EventCodec.toJava(it) })

  override fun state(): Map<String, Any> = Collections.unmodifiableMap(ctx.state)

  override fun invocationContext(): JavaInvocationContext =
    throw UnsupportedOperationException(
      "ReadonlyContext.invocationContext() is unavailable for a Java toolset on the Kotlin"
    )
}

/**
 * Builds the base builder for [KtInvocationContextToJavaView], wiring the Kotlin services as Java
 * views (unwrapped to the original Java service where possible to avoid extra hops).
 */
private fun ktInvocationContextJavaBuilder(ic: KtInvocationContext): JavaInvocationContext.Builder {
  val builder =
    JavaInvocationContext.builder()
      .invocationId(ic.invocationId)
      // Wire the agent (as an inspection-only view) so a Java tool reading ToolContext.agentName()
      // / invocationContext.agent() does not hit a null.
      .agent(ktAgentAsJava(ic.agent))
  ic.sessionService?.let { builder.sessionService(ktSessionServiceAsJava(it)) }
  ic.artifactService?.let { builder.artifactService(ktArtifactServiceAsJava(it)) }
  ic.memoryService?.let { builder.memoryService(ktMemoryServiceAsJava(it)) }
  return builder
}

/**
 * A real Java [JavaInvocationContext] backed live by a Kotlin [KtInvocationContext]. It subclasses
 * the Java type (so `instanceof`/casts keep working) and overrides the mutable accessors to read
 * and write through to the Kotlin context. [session] is re-projected on each read so it always
 * reflects the current Kotlin session (never a stale snapshot); the session / artifact / memory
 * services are wired through too.
 */
internal class KtInvocationContextToJavaView(private val ic: KtInvocationContext) :
  JavaInvocationContext(ktInvocationContextJavaBuilder(ic)) {

  override fun invocationId(): String = ic.invocationId

  override fun branch(): Optional<String> = Optional.ofNullable(ic.branch)

  override fun branch(branch: String?) {
    // kt InvocationContext.branch is immutable (val); branch writes are not propagated.
  }

  override fun endInvocation(): Boolean = ic.isEndOfInvocation

  override fun setEndInvocation(endInvocation: Boolean) {
    ic.isEndOfInvocation = endInvocation
  }

  override fun session(): JavaSession = ktSessionToJava(ic.session)

  override fun appName(): String = ic.session.key.appName

  override fun userId(): String = ic.session.key.userId

  @OptIn(FrameworkInternalApi::class)
  override fun callbackContextData(): MutableMap<String, Any> = ic.frameworkData.callbackContextData
}

/**
 * Converts a Kotlin [KtToolContext] to a Java [JavaToolContext]. The tool's side effects stay live
 * because the invocation context and actions delegate to the Kotlin context by reference.
 */
internal fun ktToolContextToJava(context: KtToolContext): JavaToolContext {
  val builder =
    JavaToolContext.builder(KtInvocationContextToJavaView(context.invocationContext))
      .actions(KtEventActionsToJavaView(context.actions))
      .functionCallId(context.functionCallId)
      .eventId(context.eventId)
  // On resume, the Kotlin re-runs the tool with the user's confirmation set; carry it so a Java
  // tool (e.g. a requireConfirmation FunctionTool) sees it and proceeds instead of re-requesting.
  context.toolConfirmation?.let { builder.toolConfirmation(ToolConfirmationCodec.toJava(it)) }
  return builder.build()
}
