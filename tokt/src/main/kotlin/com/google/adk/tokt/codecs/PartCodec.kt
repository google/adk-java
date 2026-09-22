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

package com.google.adk.tokt.codecs

import com.google.adk.kt.logging.LoggerFactory
import com.google.adk.kt.types.Blob as KtBlob
import com.google.adk.kt.types.CodeExecutionResult as KtCodeExecutionResult
import com.google.adk.kt.types.ExecutableCode as KtExecutableCode
import com.google.adk.kt.types.FileData as KtFileData
import com.google.adk.kt.types.FunctionCall as KtFunctionCall
import com.google.adk.kt.types.FunctionResponse as KtFunctionResponse
import com.google.adk.kt.types.Language as KtLanguage
import com.google.adk.kt.types.Outcome as KtOutcome
import com.google.adk.kt.types.Part as KtPart
import com.google.adk.kt.types.PartMediaResolution as KtPartMediaResolution
import com.google.adk.kt.types.PartMediaResolutionLevel as KtPartMediaResolutionLevel
import com.google.adk.kt.types.PartialArg as KtPartialArg
import com.google.adk.kt.types.PartialArgValue as KtPartialArgValue
import com.google.adk.kt.types.ToolCall as KtToolCall
import com.google.adk.kt.types.ToolResponse as KtToolResponse
import com.google.adk.kt.types.ToolType as KtToolType
import com.google.adk.kt.types.VideoMetadata as KtVideoMetadata
import com.google.genai.types.Blob as GenaiBlob
import com.google.genai.types.CodeExecutionResult as GenaiCodeExecutionResult
import com.google.genai.types.ExecutableCode as GenaiExecutableCode
import com.google.genai.types.FileData as GenaiFileData
import com.google.genai.types.FunctionCall as GenaiFunctionCall
import com.google.genai.types.FunctionResponse as GenaiFunctionResponse
import com.google.genai.types.Language as GenaiLanguage
import com.google.genai.types.NullValue as GenaiNullValue
import com.google.genai.types.Outcome as GenaiOutcome
import com.google.genai.types.Part as GenaiPart
import com.google.genai.types.PartMediaResolution as GenaiPartMediaResolution
import com.google.genai.types.PartMediaResolutionLevel as GenaiPartMediaResolutionLevel
import com.google.genai.types.PartialArg as GenaiPartialArg
import com.google.genai.types.ToolCall as GenaiToolCall
import com.google.genai.types.ToolResponse as GenaiToolResponse
import com.google.genai.types.VideoMetadata as GenaiVideoMetadata
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.optionals.getOrNull
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * Converts a [Part][KtPart] between the genai type ADK Java exposes and the ADK Kotlin type.
 * Carries text, inline binary data ([KtBlob]), file references ([KtFileData]), function
 * call/response parts, server-side tool call/response parts ([KtToolCall] / [KtToolResponse]),
 * code-execution parts ([KtExecutableCode] / [KtCodeExecutionResult]), the model "thought"
 * marker/signature, video metadata, media resolution ([KtPartMediaResolution]), and part metadata.
 * Shared by [ContentCodec] and the artifact service.
 *
 * Returns null for an empty part and for one carrying only fields the Kotlin [KtPart] has no
 * counterpart for (such as `audioTranscription`, `mediaProcessing` or `speechMetadata`), which
 * [ContentCodec] then drops from the content. The loss is forced, so it is logged rather than
 * silent; see [com.google.adk.tokt.JavaAdkToKt].
 */
internal object PartCodec {

  private val logger = LoggerFactory.getLogger(PartCodec::class)

  /** An empty part, to tell a genuinely empty part from one carrying only an unmapped field. */
  private val emptyGenaiPart = GenaiPart.builder().build()

  /** One dropped-part warning per interop session is enough to flag the loss without spamming. */
  private val unmappedPartWarned = AtomicBoolean(false)

  private fun warnUnmappedPart(part: GenaiPart) {
    // Detect an unmapped-but-non-empty part by value rather than by naming each accessor, so this
    // stays correct as the genai Part gains fields and does not depend on any one field existing.
    if (part == emptyGenaiPart) return // A genuinely empty part carries no information to lose.
    if (unmappedPartWarned.compareAndSet(false, true)) {
      logger.warn {
        "Dropping a genai Part that carries only fields the ADK Kotlin Part has no equivalent for" +
          " (such as audioTranscription, mediaProcessing or speechMetadata): it cannot cross the" +
          " Java -> Kotlin interop and will be missing from the event stream."
      }
    }
  }

  /**
   * As [fromJava], but rejects a part that carries nothing mapped. The artifact services must not
   * silently persist or return an empty artifact.
   */
  fun fromJavaOrThrow(part: GenaiPart): KtPart =
    requireNotNull(fromJava(part)) { "Artifact part is empty or unmapped" }

  /** As [toJava], rejecting a part that carries nothing; see [fromJavaOrThrow]. */
  fun toJavaOrThrow(part: KtPart): GenaiPart =
    requireNotNull(toJava(part)) { "Artifact part is empty or unmapped" }

  /** Returns the Kotlin [KtPart] view of the genai [part], or null if it carries nothing mapped. */
  fun fromJava(part: GenaiPart): KtPart? {
    val functionCall = part.functionCall().getOrNull()
    val functionResponse = part.functionResponse().getOrNull()
    val toolCall = part.toolCall().getOrNull()
    val toolResponse = part.toolResponse().getOrNull()
    val inlineData = part.inlineData().getOrNull()
    val fileData = part.fileData().getOrNull()
    val text = part.text().getOrNull()
    val thought = part.thought().getOrNull()
    val thoughtSignature = part.thoughtSignature().getOrNull()
    val partMetadata = part.partMetadata().getOrNull()
    val videoMetadata = part.videoMetadata().getOrNull()?.let { videoMetadataFromJava(it) }
    val mediaResolution = part.mediaResolution().getOrNull()?.let { mediaResolutionFromJava(it) }
    val executableCode = part.executableCode().getOrNull()
    val codeExecutionResult = part.codeExecutionResult().getOrNull()
    val base =
      when {
        functionCall != null -> KtPart(functionCall = functionCallFromJava(functionCall))
        functionResponse != null ->
          KtPart(functionResponse = functionResponseFromJava(functionResponse))
        toolCall != null -> KtPart(toolCall = toolCallFromJava(toolCall))
        toolResponse != null -> KtPart(toolResponse = toolResponseFromJava(toolResponse))
        executableCode != null -> KtPart(executableCode = executableCodeFromJava(executableCode))
        codeExecutionResult != null ->
          KtPart(codeExecutionResult = codeExecutionResultFromJava(codeExecutionResult))
        inlineData != null -> KtPart(inlineData = blobFromJava(inlineData))
        fileData != null -> KtPart(fileData = fileDataFromJava(fileData))
        text != null -> KtPart(text = text)
        // A part carrying only a thought/thoughtSignature or metadata (no primary payload) is still
        // meaningful - dropping it breaks Gemini thinking continuity - so keep it.
        thought != null ||
          thoughtSignature != null ||
          partMetadata != null ||
          videoMetadata != null ||
          mediaResolution != null -> KtPart()
        else -> {
          warnUnmappedPart(part)
          return null
        }
      }
    return base.copy(
      thought = thought,
      thoughtSignature = thoughtSignature,
      partMetadata = partMetadata,
      videoMetadata = videoMetadata,
      mediaResolution = mediaResolution,
    )
  }

  /** Returns the genai [GenaiPart] view of the Kotlin [part], or null if it carries nothing. */
  fun toJava(part: KtPart): GenaiPart? {
    val functionCall = part.functionCall
    val functionResponse = part.functionResponse
    val toolCall = part.toolCall
    val toolResponse = part.toolResponse
    val inlineData = part.inlineData
    val fileData = part.fileData
    val text = part.text
    val executableCode = part.executableCode
    val codeExecutionResult = part.codeExecutionResult
    val builder =
      when {
        functionCall != null -> GenaiPart.builder().functionCall(functionCallToJava(functionCall))
        functionResponse != null ->
          GenaiPart.builder().functionResponse(functionResponseToJava(functionResponse))
        toolCall != null -> GenaiPart.builder().toolCall(toolCallToJava(toolCall))
        toolResponse != null -> GenaiPart.builder().toolResponse(toolResponseToJava(toolResponse))
        executableCode != null ->
          GenaiPart.builder().executableCode(executableCodeToJava(executableCode))
        codeExecutionResult != null ->
          GenaiPart.builder().codeExecutionResult(codeExecutionResultToJava(codeExecutionResult))
        inlineData != null -> GenaiPart.builder().inlineData(blobToJava(inlineData))
        fileData != null -> GenaiPart.builder().fileData(fileDataToJava(fileData))
        text != null -> GenaiPart.builder().text(text)
        // Keep a thought/thoughtSignature- or metadata-only part (no primary payload); dropping it
        // breaks Gemini thinking continuity.
        part.thought != null ||
          part.thoughtSignature != null ||
          part.partMetadata != null ||
          part.videoMetadata != null ||
          part.mediaResolution != null -> GenaiPart.builder()
        else -> return null
      }
    part.thought?.let { builder.thought(it) }
    part.thoughtSignature?.let { builder.thoughtSignature(it) }
    part.partMetadata?.let { builder.partMetadata(it) }
    part.videoMetadata?.let { builder.videoMetadata(videoMetadataToJava(it)) }
    part.mediaResolution?.let { builder.mediaResolution(mediaResolutionToJava(it)) }
    return builder.build()
  }

  private fun videoMetadataFromJava(metadata: GenaiVideoMetadata): KtVideoMetadata =
    KtVideoMetadata(
      startOffset = metadata.startOffset().getOrNull()?.toKotlinDuration(),
      endOffset = metadata.endOffset().getOrNull()?.toKotlinDuration(),
      fps = metadata.fps().getOrNull(),
    )

  private fun videoMetadataToJava(metadata: KtVideoMetadata): GenaiVideoMetadata {
    val builder = GenaiVideoMetadata.builder()
    metadata.startOffset?.let { builder.startOffset(it.toJavaDuration()) }
    metadata.endOffset?.let { builder.endOffset(it.toJavaDuration()) }
    metadata.fps?.let { builder.fps(it) }
    return builder.build()
  }

  // A level this build does not know about has no Kotlin enum constant and is dropped.
  private fun mediaResolutionFromJava(resolution: GenaiPartMediaResolution): KtPartMediaResolution =
    KtPartMediaResolution(
      level =
        enumByNameOrNull<KtPartMediaResolutionLevel>(
          resolution.level().getOrNull()?.knownEnum()?.name
        ),
      numTokens = resolution.numTokens().getOrNull(),
    )

  private fun mediaResolutionToJava(resolution: KtPartMediaResolution): GenaiPartMediaResolution {
    val builder = GenaiPartMediaResolution.builder()
    resolution.level?.let { builder.level(GenaiPartMediaResolutionLevel(it.name)) }
    resolution.numTokens?.let { builder.numTokens(it) }
    return builder.build()
  }

  private fun blobFromJava(blob: GenaiBlob): KtBlob =
    KtBlob(
      mimeType = blob.mimeType().getOrNull(),
      displayName = blob.displayName().getOrNull(),
      data = blob.data().getOrNull(),
    )

  private fun blobToJava(blob: KtBlob): GenaiBlob {
    val builder = GenaiBlob.builder()
    blob.data?.let { builder.data(it) }
    blob.mimeType?.let { builder.mimeType(it) }
    blob.displayName?.let { builder.displayName(it) }
    return builder.build()
  }

  private fun fileDataFromJava(fileData: GenaiFileData): KtFileData =
    KtFileData(
      mimeType = fileData.mimeType().getOrNull(),
      displayName = fileData.displayName().getOrNull(),
      fileUri = fileData.fileUri().getOrNull(),
    )

  private fun fileDataToJava(fileData: KtFileData): GenaiFileData {
    val builder = GenaiFileData.builder()
    fileData.fileUri?.let { builder.fileUri(it) }
    fileData.mimeType?.let { builder.mimeType(it) }
    fileData.displayName?.let { builder.displayName(it) }
    return builder.build()
  }

  private fun functionCallFromJava(call: GenaiFunctionCall): KtFunctionCall =
    KtFunctionCall(
      name = call.name().getOrNull() ?: "",
      args = call.args().getOrNull().orEmpty(),
      id = call.id().getOrNull(),
      partialArgs = call.partialArgs().getOrNull()?.map { partialArgFromJava(it) },
      willContinue = call.willContinue().getOrNull(),
    )

  private fun functionCallToJava(call: KtFunctionCall): GenaiFunctionCall {
    val builder = GenaiFunctionCall.builder().name(call.name).args(call.args)
    call.id?.let { builder.id(it) }
    call.partialArgs?.let { builder.partialArgs(it.map { arg -> partialArgToJava(arg) }) }
    call.willContinue?.let { builder.willContinue(it) }
    return builder.build()
  }

  private fun partialArgFromJava(arg: GenaiPartialArg): KtPartialArg =
    KtPartialArg(
      value =
        arg.boolValue().getOrNull()?.let { KtPartialArgValue.BoolValue(it) }
          ?: arg.numberValue().getOrNull()?.let { KtPartialArgValue.NumberValue(it) }
          ?: arg.stringValue().getOrNull()?.let { KtPartialArgValue.StringValue(it) }
          ?: arg.nullValue().getOrNull()?.let { KtPartialArgValue.NullValue },
      jsonPath = arg.jsonPath().getOrNull(),
      willContinue = arg.willContinue().getOrNull(),
    )

  private fun partialArgToJava(arg: KtPartialArg): GenaiPartialArg {
    val builder = GenaiPartialArg.builder()
    when (val value = arg.value) {
      is KtPartialArgValue.BoolValue -> builder.boolValue(value.value)
      is KtPartialArgValue.NumberValue -> builder.numberValue(value.value)
      is KtPartialArgValue.StringValue -> builder.stringValue(value.value)
      is KtPartialArgValue.NullValue -> builder.nullValue(GenaiNullValue.Known.NULL_VALUE)
      null -> {}
    }
    arg.jsonPath?.let { builder.jsonPath(it) }
    arg.willContinue?.let { builder.willContinue(it) }
    return builder.build()
  }

  // genai FunctionResponse.willContinue/scheduling/parts have no Kotlin counterpart and are
  // dropped.
  private fun functionResponseFromJava(response: GenaiFunctionResponse): KtFunctionResponse =
    KtFunctionResponse(
      name = response.name().getOrNull() ?: "",
      response = response.response().getOrNull().orEmpty(),
      id = response.id().getOrNull(),
    )

  private fun functionResponseToJava(response: KtFunctionResponse): GenaiFunctionResponse {
    val builder = GenaiFunctionResponse.builder().name(response.name).response(response.response)
    response.id?.let { builder.id(it) }
    return builder.build()
  }

  private fun executableCodeFromJava(code: GenaiExecutableCode): KtExecutableCode =
    KtExecutableCode(
      code = code.code().getOrNull(),
      language = enumByNameOrNull<KtLanguage>(code.language().getOrNull()?.knownEnum()?.name),
      id = code.id().getOrNull(),
    )

  private fun executableCodeToJava(code: KtExecutableCode): GenaiExecutableCode {
    val builder = GenaiExecutableCode.builder()
    code.code?.let { builder.code(it) }
    code.language?.let { builder.language(GenaiLanguage(it.name)) }
    code.id?.let { builder.id(it) }
    return builder.build()
  }

  private fun codeExecutionResultFromJava(result: GenaiCodeExecutionResult): KtCodeExecutionResult =
    KtCodeExecutionResult(
      outcome = enumByNameOrNull<KtOutcome>(result.outcome().getOrNull()?.knownEnum()?.name),
      output = result.output().getOrNull(),
      id = result.id().getOrNull(),
    )

  private fun codeExecutionResultToJava(result: KtCodeExecutionResult): GenaiCodeExecutionResult {
    val builder = GenaiCodeExecutionResult.builder()
    result.outcome?.let { builder.outcome(GenaiOutcome(it.name)) }
    result.output?.let { builder.output(it) }
    result.id?.let { builder.id(it) }
    return builder.build()
  }

  // toolType is a Kotlin value class over the wire string, so it round-trips an unknown type too.
  private fun toolCallFromJava(call: GenaiToolCall): KtToolCall =
    KtToolCall(
      id = call.id().getOrNull(),
      toolType = call.toolType().getOrNull()?.let { KtToolType(it.toString()) },
      args = call.args().getOrNull(),
    )

  private fun toolCallToJava(call: KtToolCall): GenaiToolCall {
    val builder = GenaiToolCall.builder()
    call.id?.let { builder.id(it) }
    call.toolType?.let { builder.toolType(it.value) }
    call.args?.let { builder.args(it) }
    return builder.build()
  }

  private fun toolResponseFromJava(response: GenaiToolResponse): KtToolResponse =
    KtToolResponse(
      id = response.id().getOrNull(),
      toolType = response.toolType().getOrNull()?.let { KtToolType(it.toString()) },
      response = response.response().getOrNull(),
    )

  private fun toolResponseToJava(response: KtToolResponse): GenaiToolResponse {
    val builder = GenaiToolResponse.builder()
    response.id?.let { builder.id(it) }
    response.toolType?.let { builder.toolType(it.value) }
    response.response?.let { builder.response(it) }
    return builder.build()
  }
}
