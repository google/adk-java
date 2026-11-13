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

import com.google.adk.kt.types.AutomaticActivityDetection as KtAutomaticActivityDetection
import com.google.adk.kt.types.ContextWindowCompressionConfig as KtContextWindowCompressionConfig
import com.google.adk.kt.types.LiveConnectConfig as KtLiveConnectConfig
import com.google.adk.kt.types.ProactivityConfig as KtProactivityConfig
import com.google.adk.kt.types.RealtimeInputConfig as KtRealtimeInputConfig
import com.google.adk.kt.types.SessionResumptionConfig as KtSessionResumptionConfig
import com.google.adk.kt.types.TranslationConfig as KtTranslationConfig
import com.google.genai.types.ActivityHandling as GenaiActivityHandling
import com.google.genai.types.AutomaticActivityDetection as GenaiAutomaticActivityDetection
import com.google.genai.types.ContextWindowCompressionConfig as GenaiContextWindowCompressionConfig
import com.google.genai.types.EndSensitivity as GenaiEndSensitivity
import com.google.genai.types.LiveConnectConfig as GenaiLiveConnectConfig
import com.google.genai.types.MediaResolution as GenaiMediaResolution
import com.google.genai.types.Modality as GenaiModality
import com.google.genai.types.ProactivityConfig as GenaiProactivityConfig
import com.google.genai.types.RealtimeInputConfig as GenaiRealtimeInputConfig
import com.google.genai.types.SessionResumptionConfig as GenaiSessionResumptionConfig
import com.google.genai.types.SlidingWindow as GenaiSlidingWindow
import com.google.genai.types.StartSensitivity as GenaiStartSensitivity
import com.google.genai.types.TranslationConfig as GenaiTranslationConfig
import com.google.genai.types.TurnCoverage as GenaiTurnCoverage

/**
 * Converts the Kotlin [KtLiveConnectConfig] to the genai [GenaiLiveConnectConfig] an ADK Java model
 * opens a live connection with ([LlmRequestCodec.toJavaLive]). Every Kotlin field is carried except
 * `avatarConfig`, which is newer than the ADK Kotlin release tokt pins. There is no reverse
 * direction, because nothing reads a Java live config back.
 */
internal object LiveConnectConfigCodec {

  /** Returns the genai [GenaiLiveConnectConfig] view of the Kotlin [config]. */
  fun toJava(config: KtLiveConnectConfig): GenaiLiveConnectConfig {
    val builder = GenaiLiveConnectConfig.builder()
    with(config) {
      responseModalities?.let { modalities ->
        builder.responseModalities(modalities.map { GenaiModality(it.name) })
      }
      temperature?.let { builder.temperature(it) }
      topP?.let { builder.topP(it) }
      topK?.let { builder.topK(it.toFloat()) } // genai types topK as Float.
      maxOutputTokens?.let { builder.maxOutputTokens(it) }
      mediaResolution?.let { builder.mediaResolution(GenaiMediaResolution(it.name)) }
      seed?.let { builder.seed(it) }
      speechConfig?.let { builder.speechConfig(it.toGenai()) }
      thinkingConfig?.let {
        builder.thinkingConfig(GenerateContentConfigCodec.thinkingConfigToJava(it))
      }
      enableAffectiveDialog?.let { builder.enableAffectiveDialog(it) }
      systemInstruction?.let { builder.systemInstruction(ContentCodec.toJava(it)) }
      tools?.let { builder.tools(it.map(GenerateContentConfigCodec::toolToJava)) }
      sessionResumption?.let { builder.sessionResumption(it.toGenai()) }
      inputAudioTranscription?.let { builder.inputAudioTranscription(it.toGenai()) }
      outputAudioTranscription?.let { builder.outputAudioTranscription(it.toGenai()) }
      realtimeInputConfig?.let { builder.realtimeInputConfig(it.toGenai()) }
      contextWindowCompression?.let { builder.contextWindowCompression(it.toGenai()) }
      proactivity?.let { builder.proactivity(it.toGenai()) }
      safetySettings?.let {
        builder.safetySettings(it.map(GenerateContentConfigCodec::safetySettingToJava))
      }
      explicitVadSignal?.let { builder.explicitVadSignal(it) }
      translationConfig?.let { builder.translationConfig(it.toGenai()) }
    }
    return builder.build()
  }
}

private fun KtSessionResumptionConfig.toGenai(): GenaiSessionResumptionConfig {
  val builder = GenaiSessionResumptionConfig.builder()
  handle?.let { builder.handle(it) }
  transparent?.let { builder.transparent(it) }
  return builder.build()
}

private fun KtRealtimeInputConfig.toGenai(): GenaiRealtimeInputConfig {
  val builder = GenaiRealtimeInputConfig.builder()
  automaticActivityDetection?.let { builder.automaticActivityDetection(it.toGenai()) }
  activityHandling?.let { builder.activityHandling(GenaiActivityHandling(it.name)) }
  turnCoverage?.let { builder.turnCoverage(GenaiTurnCoverage(it.name)) }
  return builder.build()
}

private fun KtAutomaticActivityDetection.toGenai(): GenaiAutomaticActivityDetection {
  val builder = GenaiAutomaticActivityDetection.builder()
  disabled?.let { builder.disabled(it) }
  startOfSpeechSensitivity?.let { builder.startOfSpeechSensitivity(GenaiStartSensitivity(it.name)) }
  endOfSpeechSensitivity?.let { builder.endOfSpeechSensitivity(GenaiEndSensitivity(it.name)) }
  prefixPaddingMs?.let { builder.prefixPaddingMs(it) }
  silenceDurationMs?.let { builder.silenceDurationMs(it) }
  return builder.build()
}

private fun KtContextWindowCompressionConfig.toGenai(): GenaiContextWindowCompressionConfig {
  val builder = GenaiContextWindowCompressionConfig.builder()
  triggerTokens?.let { builder.triggerTokens(it) }
  slidingWindow?.let { window ->
    val windowBuilder = GenaiSlidingWindow.builder()
    window.targetTokens?.let { windowBuilder.targetTokens(it) }
    builder.slidingWindow(windowBuilder.build())
  }
  return builder.build()
}

private fun KtTranslationConfig.toGenai(): GenaiTranslationConfig {
  val builder = GenaiTranslationConfig.builder()
  targetLanguageCode?.let { builder.targetLanguageCode(it) }
  echoTargetLanguage?.let { builder.echoTargetLanguage(it) }
  return builder.build()
}

private fun KtProactivityConfig.toGenai(): GenaiProactivityConfig {
  val builder = GenaiProactivityConfig.builder()
  proactiveAudio?.let { builder.proactiveAudio(it) }
  return builder.build()
}
