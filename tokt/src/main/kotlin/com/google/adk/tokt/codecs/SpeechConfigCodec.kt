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

import com.google.adk.kt.types.MultiSpeakerVoiceConfig as KtMultiSpeakerVoiceConfig
import com.google.adk.kt.types.PrebuiltVoiceConfig as KtPrebuiltVoiceConfig
import com.google.adk.kt.types.ReplicatedVoiceConfig as KtReplicatedVoiceConfig
import com.google.adk.kt.types.SpeakerVoiceConfig as KtSpeakerVoiceConfig
import com.google.adk.kt.types.SpeechConfig as KtSpeechConfig
import com.google.adk.kt.types.VoiceConfig as KtVoiceConfig
import com.google.adk.kt.types.VoiceConsentSignature as KtVoiceConsentSignature
import com.google.genai.types.MultiSpeakerVoiceConfig as GenaiMultiSpeakerVoiceConfig
import com.google.genai.types.PrebuiltVoiceConfig as GenaiPrebuiltVoiceConfig
import com.google.genai.types.ReplicatedVoiceConfig as GenaiReplicatedVoiceConfig
import com.google.genai.types.SpeakerVoiceConfig as GenaiSpeakerVoiceConfig
import com.google.genai.types.SpeechConfig as GenaiSpeechConfig
import com.google.genai.types.VoiceConfig as GenaiVoiceConfig
import com.google.genai.types.VoiceConsentSignature as GenaiVoiceConsentSignature
import kotlin.jvm.optionals.getOrNull

/**
 * Returns the genai [GenaiSpeechConfig] view of this ADK Kotlin voice setting; every field crosses.
 */
internal fun KtSpeechConfig.toGenai(): GenaiSpeechConfig {
  val builder = GenaiSpeechConfig.builder()
  voiceConfig?.let { builder.voiceConfig(it.toGenai()) }
  languageCode?.let { builder.languageCode(it) }
  multiSpeakerVoiceConfig?.let { builder.multiSpeakerVoiceConfig(it.toGenai()) }
  return builder.build()
}

/**
 * Returns the ADK Kotlin [KtSpeechConfig] view of this genai voice setting; every field crosses.
 */
internal fun GenaiSpeechConfig.toKt(): KtSpeechConfig =
  KtSpeechConfig(
    voiceConfig = voiceConfig().getOrNull()?.toKt(),
    languageCode = languageCode().getOrNull(),
    multiSpeakerVoiceConfig =
      multiSpeakerVoiceConfig().getOrNull()?.let { multi ->
        KtMultiSpeakerVoiceConfig(
          speakerVoiceConfigs = multi.speakerVoiceConfigs().getOrNull()?.map { it.toKt() }
        )
      },
  )

private fun KtVoiceConfig.toGenai(): GenaiVoiceConfig {
  val builder = GenaiVoiceConfig.builder()
  prebuiltVoiceConfig?.let { builder.prebuiltVoiceConfig(it.toGenai()) }
  replicatedVoiceConfig?.let { builder.replicatedVoiceConfig(it.toGenai()) }
  voice?.let { builder.voice(it) }
  return builder.build()
}

private fun KtReplicatedVoiceConfig.toGenai(): GenaiReplicatedVoiceConfig {
  val builder = GenaiReplicatedVoiceConfig.builder()
  mimeType?.let { builder.mimeType(it) }
  voiceSampleAudio?.let { builder.voiceSampleAudio(it) }
  consentAudio?.let { builder.consentAudio(it) }
  voiceConsentSignature?.let { builder.voiceConsentSignature(it.toGenai()) }
  return builder.build()
}

private fun KtMultiSpeakerVoiceConfig.toGenai(): GenaiMultiSpeakerVoiceConfig {
  val builder = GenaiMultiSpeakerVoiceConfig.builder()
  speakerVoiceConfigs?.let { configs -> builder.speakerVoiceConfigs(configs.map { it.toGenai() }) }
  return builder.build()
}

private fun KtPrebuiltVoiceConfig.toGenai(): GenaiPrebuiltVoiceConfig {
  val builder = GenaiPrebuiltVoiceConfig.builder()
  voiceName?.let { builder.voiceName(it) }
  return builder.build()
}

private fun KtVoiceConsentSignature.toGenai(): GenaiVoiceConsentSignature {
  val builder = GenaiVoiceConsentSignature.builder()
  signature?.let { builder.signature(it) }
  return builder.build()
}

private fun KtSpeakerVoiceConfig.toGenai(): GenaiSpeakerVoiceConfig {
  val builder = GenaiSpeakerVoiceConfig.builder()
  speaker?.let { builder.speaker(it) }
  voiceConfig?.let { builder.voiceConfig(it.toGenai()) }
  return builder.build()
}

private fun GenaiVoiceConfig.toKt(): KtVoiceConfig =
  KtVoiceConfig(
    prebuiltVoiceConfig =
      prebuiltVoiceConfig().getOrNull()?.let {
        KtPrebuiltVoiceConfig(voiceName = it.voiceName().getOrNull())
      },
    replicatedVoiceConfig = replicatedVoiceConfig().getOrNull()?.toKt(),
    voice = voice().getOrNull(),
  )

private fun GenaiReplicatedVoiceConfig.toKt(): KtReplicatedVoiceConfig =
  KtReplicatedVoiceConfig(
    mimeType = mimeType().getOrNull(),
    voiceSampleAudio = voiceSampleAudio().getOrNull(),
    consentAudio = consentAudio().getOrNull(),
    voiceConsentSignature =
      voiceConsentSignature().getOrNull()?.let {
        KtVoiceConsentSignature(signature = it.signature().getOrNull())
      },
  )

private fun GenaiSpeakerVoiceConfig.toKt(): KtSpeakerVoiceConfig =
  KtSpeakerVoiceConfig(
    speaker = speaker().getOrNull(),
    voiceConfig = voiceConfig().getOrNull()?.toKt(),
  )
