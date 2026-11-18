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

import com.google.adk.kt.agents.LlmAgent as KtLlmAgent
import com.google.adk.kt.runners.InMemoryRunner as KtInMemoryRunner
import com.google.adk.kt.sessions.SessionKey as KtSessionKey
import com.google.adk.kt.tools.BaseTool as KtBaseTool
import com.google.adk.kt.tools.ToolContext as KtToolContext
import com.google.adk.kt.types.Content as KtContent
import com.google.adk.kt.types.FunctionDeclaration as KtFunctionDeclaration
import com.google.adk.kt.types.Part as KtPart
import com.google.adk.kt.types.Role as KtRole
import com.google.adk.kt.types.Schema as KtSchema
import com.google.adk.kt.types.Type as KtType
import com.google.adk.models.BaseLlm as JavaBaseLlm
import com.google.adk.models.BaseLlmConnection as JavaBaseLlmConnection
import com.google.adk.models.LlmCapabilities as JavaLlmCapabilities
import com.google.adk.models.LlmRequest as JavaLlmRequest
import com.google.adk.models.LlmResponse as JavaLlmResponse
import com.google.common.truth.Truth.assertThat
import com.google.genai.types.Content as GenaiContent
import com.google.genai.types.Part as GenaiPart
import io.reactivex.rxjava3.core.Flowable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.optionals.getOrNull
import kotlin.test.Test
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/** Covers how the Java -> Kotlin model adapter reports the Java model's capabilities. */
class JavaModelToKtTest {

  @Test
  fun capabilities_javaModelReportingNoOutputSchemaAndTools_getsSetModelResponse() = runBlocking {
    // Arrange: a name that alone would get the response schema.
    val javaModel = RecordingJavaModel("java-model", reported = false)
    val agent =
      KtLlmAgent(
        name = "agent",
        model = JavaAdkToKt.asKtModel(javaModel),
        tools = listOf(LookupTool()),
        outputSchema =
          KtSchema(
            type = KtType.OBJECT,
            properties = mapOf("answer" to KtSchema(type = KtType.STRING)),
          ),
      )
    val runner = KtInMemoryRunner(agent = agent, appName = "app")
    val unused = runner.sessionService.createSession(KtSessionKey("app", "u", "s"))

    // Act
    val unusedEvents =
      runner
        .runAsync(
          userId = "u",
          sessionId = "s",
          newMessage = KtContent(role = KtRole.USER, parts = listOf(KtPart(text = "hi"))),
        )
        .toList()

    // Assert
    val config = javaModel.requests.first().config().getOrNull()
    val functionNames =
      config?.tools()?.getOrNull().orEmpty().flatMap { tool ->
        tool.functionDeclarations().getOrNull().orEmpty().mapNotNull { it.name().getOrNull() }
      }
    assertThat(functionNames).contains("set_model_response")
    assertThat(config?.responseSchema()?.getOrNull()).isNull()
  }

  @Test
  fun capabilities_javaModelReportingOutputSchemaAndTools_overridesTheNameRule() {
    // Arrange: a name that alone would get set_model_response.
    val model = JavaAdkToKt.asKtModel(RecordingJavaModel("gemini-2.5-flash", reported = true))

    // Act & Assert
    assertThat(model.capabilities.outputSchemaAndTools).isTrue()
  }

  @Test
  fun capabilities_javaModelReturningNull_fallsBackToTheNameRule() {
    // Arrange: a Java model whose capabilities() returns null, as a Mockito mock does.
    val gemini2 = JavaAdkToKt.asKtModel(RecordingJavaModel("gemini-2.5-flash", reported = null))
    val other = JavaAdkToKt.asKtModel(RecordingJavaModel("java-model", reported = null))

    // Act & Assert
    assertThat(gemini2.capabilities.outputSchemaAndTools).isFalse()
    assertThat(other.capabilities.outputSchemaAndTools).isTrue()
  }

  /**
   * A Java model that reports [reported] as its capability, answers with text and records requests.
   */
  private class RecordingJavaModel(name: String, private val reported: Boolean?) :
    JavaBaseLlm(name) {
    val requests = CopyOnWriteArrayList<JavaLlmRequest>()

    override fun capabilities(): JavaLlmCapabilities? = reported?.let {
      JavaLlmCapabilities.builder().outputSchemaAndTools(it).build()
    }

    override fun generateContent(
      llmRequest: JavaLlmRequest,
      stream: Boolean,
    ): Flowable<JavaLlmResponse> {
      requests.add(llmRequest)
      val answer =
        GenaiContent.builder()
          .role("model")
          .parts(GenaiPart.fromText("""{"answer":"42"}"""))
          .build()
      return Flowable.just(JavaLlmResponse.builder().content(answer).build())
    }

    override fun connect(llmRequest: JavaLlmRequest): JavaBaseLlmConnection =
      throw UnsupportedOperationException()
  }

  private class LookupTool : KtBaseTool(name = "lookup", description = "Looks up a value.") {
    override fun declaration() = KtFunctionDeclaration(name = name, description = description)

    override suspend fun run(context: KtToolContext, args: Map<String, Any?>): Any = "x"
  }
}
