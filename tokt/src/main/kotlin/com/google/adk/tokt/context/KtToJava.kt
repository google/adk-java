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
import com.google.adk.agents.ReadonlyContext as JavaReadonlyContext
import com.google.adk.events.Event as JavaEvent
import com.google.adk.kt.agents.BaseAgent as KtBaseAgent
import com.google.adk.kt.agents.CallbackContext as KtCallbackContext
import com.google.adk.kt.agents.InvocationContext as KtInvocationContext
import com.google.adk.kt.agents.ReadonlyContext as KtReadonlyContext
import com.google.adk.kt.annotations.FrameworkInternalApi
import com.google.adk.kt.tools.ToolContext as KtToolContext
import com.google.adk.plugins.Plugin as JavaPlugin
import com.google.adk.tokt.adapters.ktAgentAsJava
import com.google.adk.tokt.adapters.ktPluginManagerAsJava
import com.google.adk.tokt.codecs.ContentCodec
import com.google.adk.tokt.codecs.EventCodec
import com.google.adk.tokt.codecs.KtEventActionsToJavaView
import com.google.adk.tokt.codecs.RunConfigCodec
import com.google.adk.tokt.codecs.ToolConfirmationCodec
import com.google.adk.tokt.codecs.ktSessionToJavaLive
import com.google.adk.tokt.codecs.sessionId
import com.google.adk.tokt.services.ktArtifactServiceAsJava
import com.google.adk.tokt.services.ktMemoryServiceAsJava
import com.google.adk.tokt.services.ktSessionServiceAsJava
import com.google.adk.tools.ToolContext as JavaToolContext
import com.google.genai.types.Content as GenaiContent
import java.util.Collections
import java.util.Optional
import java.util.concurrent.ConcurrentMap

/**
 * Kt -> Java context views used when the ADK Kotlin calls back into ADK Java code (a Java tool) or
 * returns results. These subclass the Java ADK context types and delegate straight to the Kotlin
 * context - no neutral interfaces, no identity registry. (Plain value conversions live in
 * `codecs`.)
 */

/**
 * A real Java [JavaReadonlyContext] backed by a Kotlin [KtReadonlyContext], used when the engine
 * pulls tools from a Java toolset
 * ([JavaToolsetToKt][com.google.adk.tokt.adapters.JavaToolsetToKt]). Every accessor reads through
 * to the Kotlin context; [invocationContext] is not available (the Kotlin exposes only the
 * read-only view).
 */
internal class KtReadonlyContextToJavaView(private val ctx: KtReadonlyContext) :
  JavaReadonlyContext(null) {

  override fun userContent(): Optional<GenaiContent> =
    Optional.ofNullable(ctx.userContent?.let { ContentCodec.toJava(it) })

  override fun invocationId(): String = ctx.invocationId

  override fun branch(): Optional<String> = Optional.ofNullable(ctx.branch)

  override fun agentName(): String = ctx.agentName

  override fun userId(): String = ctx.userId

  override fun sessionId(): String = sessionId(ctx.session.key)

  override fun events(): List<JavaEvent> =
    Collections.unmodifiableList(ctx.session.events.map { EventCodec.toJava(it) })

  override fun state(): Map<String, Any> = Collections.unmodifiableMap(ctx.state)

  override fun invocationContext(): JavaInvocationContext =
    throw UnsupportedOperationException(
      "ReadonlyContext.invocationContext() is unavailable for a Java toolset on the Kotlin engine"
    )
}

/**
 * The Java view of [agent], memoized in the invocation's scratch data. [ktAgentAsJava] wraps and
 * re-validates the whole sub-agent tree, and Java agents compare by identity, so a plugin that
 * pairs its before/after callbacks by agent must be handed the same instance each time.
 */
private fun javaAgentView(
  agent: KtBaseAgent,
  callbackContextData: MutableMap<String, Any>,
): JavaBaseAgent =
  // computeIfAbsent, not getOrPut: a turn's function calls run concurrently, and getOrPut has no
  // CAS, so racing callers would each mint a wrapper - the identity split this memo prevents.
  memoize(callbackContextData, "tokt.javaAgent.${agent.name}") { ktAgentAsJava(agent) }
    as JavaBaseAgent

/** Computes [key] at most once in [data], atomically when the map supports it. */
private fun memoize(data: MutableMap<String, Any>, key: String, compute: () -> Any): Any =
  when (data) {
    is ConcurrentMap<String, Any> -> data.computeIfAbsent(key) { compute() }
    else -> synchronized(data) { data.getOrPut(key, compute) }
  }

/** The memoized Java view of the agent a callback context belongs to. */
@OptIn(FrameworkInternalApi::class)
internal fun javaAgentView(context: KtCallbackContext): JavaBaseAgent =
  javaAgentView(context.agent, context.callbackContextData)

/** The memoized Java view of the agent an invocation is running. */
@OptIn(FrameworkInternalApi::class)
internal fun javaAgentView(ic: KtInvocationContext): JavaBaseAgent =
  javaAgentView(ic.agent, ic.frameworkData.callbackContextData)

/**
 * Builds the base builder for [KtInvocationContextToJavaView], wiring the Kotlin services as Java
 * views (unwrapped to the original Java service where possible to avoid extra hops).
 */
@OptIn(FrameworkInternalApi::class)
private fun ktInvocationContextJavaBuilder(ic: KtInvocationContext): JavaInvocationContext.Builder {
  val builder =
    JavaInvocationContext.builder()
      .invocationId(ic.invocationId)
      // Live view: events + state read through, so it never needs re-projecting.
      .session(ktSessionToJavaLive(ic.session))
      // Inspection-only agent view, so a Java tool reading the agent does not hit a null.
      .agent(javaAgentView(ic.agent, ic.frameworkData.callbackContextData))
  // Carry the run's RunConfig so a component does not fall back to a default streaming mode.
  ic.runConfig?.let { builder.runConfig(RunConfigCodec.toJava(it)) }
  ic.sessionService?.let { builder.sessionService(ktSessionServiceAsJava(it)) }
  ic.artifactService?.let { builder.artifactService(ktArtifactServiceAsJava(it)) }
  ic.memoryService?.let { builder.memoryService(ktMemoryServiceAsJava(it)) }
  // ReadonlyContext.userContent() delegates here, so tools and plugins read the turn's content.
  ic.userContent?.let { builder.userContent(ContentCodec.toJava(it)) }
  return builder
}

/**
 * A Java [JavaInvocationContext] backed live by a Kotlin [KtInvocationContext]: it subclasses the
 * Java type so casts keep working, and its accessors read and write through to the Kotlin context.
 * Tools and plugin run-level callbacks share it, so a Java component sees the same context wherever
 * it runs.
 */
internal class KtInvocationContextToJavaView(private val ic: KtInvocationContext) :
  JavaInvocationContext(ktInvocationContextJavaBuilder(ic)) {

  override fun invocationId(): String = ic.invocationId

  override fun branch(): Optional<String> = Optional.ofNullable(ic.branch)

  // Throws rather than silently no-op: the Kotlin branch is immutable, only the engine deepens it.
  override fun branch(branch: String?) =
    throw UnsupportedOperationException(
      "A Kotlin invocation's branch is immutable, so a bridged Java component cannot re-scope it"
    )

  override fun endInvocation(): Boolean = ic.isEndOfInvocation

  override fun setEndInvocation(endInvocation: Boolean) {
    ic.isEndOfInvocation = endInvocation
  }

  @OptIn(FrameworkInternalApi::class)
  override fun callbackContextData(): MutableMap<String, Any> = ic.frameworkData.callbackContextData

  // Exposes the invocation's plugins to a tool like an AgentTool with includePlugins; the returned
  // manager rejects registration.
  override fun pluginManager(): JavaPlugin = ktPluginManagerAsJava(ic.pluginManager)
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
  // Carry a resume confirmation so a requireConfirmation tool proceeds instead of re-requesting.
  context.toolConfirmation?.let { builder.toolConfirmation(ToolConfirmationCodec.toJava(it)) }
  return builder.build()
}
