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

import com.google.adk.kt.types.FunctionCallingConfig as KtFunctionCallingConfig
import com.google.adk.kt.types.GenerateContentConfig as KtConfig
import com.google.adk.kt.types.GenerationConfigRoutingConfig as KtRoutingConfig
import com.google.adk.kt.types.GenerationConfigRoutingConfigAutoRoutingMode as KtAutoRoutingMode
import com.google.adk.kt.types.GenerationConfigRoutingConfigManualRoutingMode as KtManualRoutingMode
import com.google.adk.kt.types.GoogleMaps as KtGoogleMaps
import com.google.adk.kt.types.GoogleSearch as KtGoogleSearch
import com.google.adk.kt.types.HarmBlockThreshold as KtHarmBlockThreshold
import com.google.adk.kt.types.HarmCategory as KtHarmCategory
import com.google.adk.kt.types.MediaResolution as KtMediaResolution
import com.google.adk.kt.types.ModelRoutingPreference as KtModelRoutingPreference
import com.google.adk.kt.types.Retrieval as KtRetrieval
import com.google.adk.kt.types.SafetySetting as KtSafetySetting
import com.google.adk.kt.types.ServiceTier as KtServiceTier
import com.google.adk.kt.types.ThinkingConfig as KtThinkingConfig
import com.google.adk.kt.types.ThinkingLevel as KtThinkingLevel
import com.google.adk.kt.types.Tool as KtTool
import com.google.adk.kt.types.ToolConfig as KtToolConfig
import com.google.adk.kt.types.UrlContext as KtUrlContext
import com.google.adk.kt.types.VertexAISearch as KtVertexAISearch
import com.google.adk.kt.types.VertexAISearchDataStoreSpec as KtVertexAISearchDataStoreSpec
import com.google.adk.kt.types.VertexRagStore as KtVertexRagStore
import com.google.adk.kt.types.VertexRagStoreRagResource as KtVertexRagStoreRagResource
import com.google.genai.types.FunctionCallingConfig as GenaiFunctionCallingConfig
import com.google.genai.types.GenerateContentConfig as GenaiConfig
import com.google.genai.types.GenerationConfigRoutingConfig as GenaiRoutingConfig
import com.google.genai.types.GenerationConfigRoutingConfigAutoRoutingMode as GenaiAutoRoutingMode
import com.google.genai.types.GenerationConfigRoutingConfigManualRoutingMode as GenaiManualRoutingMode
import com.google.genai.types.GoogleMaps as GenaiGoogleMaps
import com.google.genai.types.GoogleSearch as GenaiGoogleSearch
import com.google.genai.types.HarmBlockThreshold as GenaiHarmBlockThreshold
import com.google.genai.types.HarmCategory as GenaiHarmCategory
import com.google.genai.types.MediaResolution as GenaiMediaResolution
import com.google.genai.types.Retrieval as GenaiRetrieval
import com.google.genai.types.SafetySetting as GenaiSafetySetting
import com.google.genai.types.ServiceTier as GenaiServiceTier
import com.google.genai.types.ThinkingConfig as GenaiThinkingConfig
import com.google.genai.types.ThinkingLevel as GenaiThinkingLevel
import com.google.genai.types.Tool as GenaiTool
import com.google.genai.types.ToolConfig as GenaiToolConfig
import com.google.genai.types.UrlContext as GenaiUrlContext
import com.google.genai.types.VertexAISearch as GenaiVertexAISearch
import com.google.genai.types.VertexAISearchDataStoreSpec as GenaiVertexAISearchDataStoreSpec
import com.google.genai.types.VertexRagStore as GenaiVertexRagStore
import com.google.genai.types.VertexRagStoreRagResource as GenaiVertexRagStoreRagResource
import kotlin.jvm.optionals.getOrNull

/**
 * Converts the Kotlin's `kt.types.GenerateContentConfig` and the genai `GenerateContentConfig` that
 * an ADK Java `BaseLlm` consumes ([LlmRequestCodec]), in both directions.
 *
 * Carries the system instruction, cached content name, tools (function declarations, Google Search,
 * Google Maps, and both the Vertex AI Search and Vertex RAG store retrieval kinds), the common
 * generation parameters, thinking config, model routing config, tool config, and safety settings.
 * genai fields the Kotlin types cannot represent (`FunctionCallingConfig.mode`,
 * `SafetySetting.method`) are not mapped.
 */
internal object GenerateContentConfigCodec {

  /** Returns the genai [GenaiConfig] view of the Kotlin [config]. */
  fun toJava(config: KtConfig): GenaiConfig {
    val builder = GenaiConfig.builder()
    config.systemInstruction?.let { builder.systemInstruction(ContentCodec.toJava(it)) }
    config.cachedContent?.let { builder.cachedContent(it) }
    config.tools?.let { tools -> builder.tools(tools.map { toolToJava(it) }) }
    config.temperature?.let { builder.temperature(it) }
    config.topP?.let { builder.topP(it) }
    config.topK?.let { builder.topK(it.toFloat()) } // genai types topK as Float.
    config.candidateCount?.let { builder.candidateCount(it) }
    config.maxOutputTokens?.let { builder.maxOutputTokens(it) }
    config.stopSequences?.let { builder.stopSequences(it) }
    config.responseMimeType?.let { builder.responseMimeType(it) }
    config.responseSchema?.let { builder.responseSchema(SchemaCodec.toJava(it)) }
    config.thinkingConfig?.let { builder.thinkingConfig(thinkingConfigToJava(it)) }
    config.labels?.let { builder.labels(it) }
    config.presencePenalty?.let { builder.presencePenalty(it) }
    config.frequencyPenalty?.let { builder.frequencyPenalty(it) }
    config.responseLogprobs?.let { builder.responseLogprobs(it) }
    config.mediaResolution?.let { builder.mediaResolution(GenaiMediaResolution(it.name)) }
    config.serviceTier?.let { builder.serviceTier(GenaiServiceTier(it.name)) }
    config.routingConfig?.let { builder.routingConfig(routingConfigToJava(it)) }
    config.toolConfig?.let { builder.toolConfig(toolConfigToJava(it)) }
    config.safetySettings?.let { settings ->
      builder.safetySettings(settings.map { safetySettingToJava(it) })
    }
    return builder.build()
  }

  /** Returns the Kotlin [KtConfig] view of the genai [config]. */
  fun fromJava(config: GenaiConfig): KtConfig =
    KtConfig(
      tools = config.tools().getOrNull()?.map { toolFromJava(it) },
      systemInstruction = config.systemInstruction().getOrNull()?.let { ContentCodec.fromJava(it) },
      cachedContent = config.cachedContent().getOrNull(),
      temperature = config.temperature().getOrNull(),
      topP = config.topP().getOrNull(),
      topK = config.topK().getOrNull()?.toInt(),
      candidateCount = config.candidateCount().getOrNull(),
      maxOutputTokens = config.maxOutputTokens().getOrNull(),
      stopSequences = config.stopSequences().getOrNull(),
      responseMimeType = config.responseMimeType().getOrNull(),
      responseSchema = config.responseSchema().getOrNull()?.let { SchemaCodec.fromJava(it) },
      thinkingConfig = config.thinkingConfig().getOrNull()?.let { thinkingConfigFromJava(it) },
      labels = config.labels().getOrNull(),
      presencePenalty = config.presencePenalty().getOrNull(),
      frequencyPenalty = config.frequencyPenalty().getOrNull(),
      responseLogprobs = config.responseLogprobs().getOrNull(),
      mediaResolution =
        enumByNameOrNull<KtMediaResolution> {
          config.mediaResolution().getOrNull()?.knownEnum()?.name
        },
      serviceTier =
        enumByNameOrNull<KtServiceTier> { config.serviceTier().getOrNull()?.knownEnum()?.name },
      routingConfig = config.routingConfig().getOrNull()?.let { routingConfigFromJava(it) },
      toolConfig = config.toolConfig().getOrNull()?.let { toolConfigFromJava(it) },
      safetySettings = config.safetySettings().getOrNull()?.map { safetySettingFromJava(it) },
    )

  private fun toolToJava(tool: KtTool): GenaiTool {
    val builder = GenaiTool.builder()
    tool.functionDeclarations?.let { decls ->
      builder.functionDeclarations(decls.map { FunctionDeclarationCodec.toJava(it) })
    }
    tool.googleSearch?.let { builder.googleSearch(googleSearchToJava(it)) }
    tool.googleMaps?.let { builder.googleMaps(googleMapsToJava(it)) }
    tool.retrieval?.let { builder.retrieval(retrievalToJava(it)) }
    tool.urlContext?.let { builder.urlContext(GenaiUrlContext.builder().build()) }
    return builder.build()
  }

  private fun toolFromJava(tool: GenaiTool): KtTool =
    KtTool(
      functionDeclarations =
        tool.functionDeclarations().getOrNull()?.map { FunctionDeclarationCodec.fromJava(it) },
      googleSearch = tool.googleSearch().getOrNull()?.let { googleSearchFromJava(it) },
      googleMaps = tool.googleMaps().getOrNull()?.let { googleMapsFromJava(it) },
      retrieval = tool.retrieval().getOrNull()?.let { retrievalFromJava(it) },
      urlContext = tool.urlContext().getOrNull()?.let { KtUrlContext() },
    )

  private fun googleSearchToJava(search: KtGoogleSearch): GenaiGoogleSearch {
    val builder = GenaiGoogleSearch.builder()
    search.excludeDomains.takeIf { it.isNotEmpty() }?.let { builder.excludeDomains(it) }
    return builder.build()
  }

  private fun googleSearchFromJava(search: GenaiGoogleSearch): KtGoogleSearch =
    KtGoogleSearch(excludeDomains = search.excludeDomains().getOrNull().orEmpty())

  private fun googleMapsToJava(maps: KtGoogleMaps): GenaiGoogleMaps {
    val builder = GenaiGoogleMaps.builder()
    maps.enableWidget?.let { builder.enableWidget(it) }
    return builder.build()
  }

  private fun googleMapsFromJava(maps: GenaiGoogleMaps): KtGoogleMaps =
    KtGoogleMaps(enableWidget = maps.enableWidget().getOrNull())

  // Carries the Vertex AI Search and Vertex RAG store retrieval kinds; genai's disableAttribution
  // and externalApi have no Kotlin field and are dropped.
  private fun retrievalToJava(retrieval: KtRetrieval): GenaiRetrieval {
    val builder = GenaiRetrieval.builder()
    retrieval.vertexAiSearch?.let { builder.vertexAiSearch(vertexAiSearchToJava(it)) }
    retrieval.vertexRagStore?.let { builder.vertexRagStore(vertexRagStoreToJava(it)) }
    return builder.build()
  }

  private fun retrievalFromJava(retrieval: GenaiRetrieval): KtRetrieval =
    KtRetrieval(
      vertexAiSearch = retrieval.vertexAiSearch().getOrNull()?.let { vertexAiSearchFromJava(it) },
      vertexRagStore = retrieval.vertexRagStore().getOrNull()?.let { vertexRagStoreFromJava(it) },
    )

  private fun vertexRagStoreToJava(store: KtVertexRagStore): GenaiVertexRagStore {
    val builder = GenaiVertexRagStore.builder()
    store.ragCorpora?.let { builder.ragCorpora(it) }
    store.ragResources?.let { resources ->
      builder.ragResources(resources.map { ragResourceToJava(it) })
    }
    store.similarityTopK?.let { builder.similarityTopK(it) }
    store.vectorDistanceThreshold?.let { builder.vectorDistanceThreshold(it) }
    return builder.build()
  }

  private fun vertexRagStoreFromJava(store: GenaiVertexRagStore): KtVertexRagStore =
    KtVertexRagStore(
      ragCorpora = store.ragCorpora().getOrNull(),
      ragResources = store.ragResources().getOrNull()?.map { ragResourceFromJava(it) },
      similarityTopK = store.similarityTopK().getOrNull(),
      vectorDistanceThreshold = store.vectorDistanceThreshold().getOrNull(),
    )

  private fun ragResourceToJava(
    resource: KtVertexRagStoreRagResource
  ): GenaiVertexRagStoreRagResource {
    val builder = GenaiVertexRagStoreRagResource.builder()
    resource.ragCorpus?.let { builder.ragCorpus(it) }
    resource.ragFileIds?.let { builder.ragFileIds(it) }
    return builder.build()
  }

  private fun ragResourceFromJava(
    resource: GenaiVertexRagStoreRagResource
  ): KtVertexRagStoreRagResource =
    KtVertexRagStoreRagResource(
      ragCorpus = resource.ragCorpus().getOrNull(),
      ragFileIds = resource.ragFileIds().getOrNull(),
    )

  private fun routingConfigToJava(config: KtRoutingConfig): GenaiRoutingConfig {
    val builder = GenaiRoutingConfig.builder()
    config.autoMode?.let { builder.autoMode(autoRoutingModeToJava(it)) }
    config.manualMode?.let { builder.manualMode(manualRoutingModeToJava(it)) }
    return builder.build()
  }

  private fun routingConfigFromJava(config: GenaiRoutingConfig): KtRoutingConfig =
    KtRoutingConfig(
      autoMode = config.autoMode().getOrNull()?.let { autoRoutingModeFromJava(it) },
      manualMode = config.manualMode().getOrNull()?.let { manualRoutingModeFromJava(it) },
    )

  private fun autoRoutingModeToJava(mode: KtAutoRoutingMode): GenaiAutoRoutingMode {
    val builder = GenaiAutoRoutingMode.builder()
    mode.modelRoutingPreference?.let { builder.modelRoutingPreference(it.name) }
    return builder.build()
  }

  private fun autoRoutingModeFromJava(mode: GenaiAutoRoutingMode): KtAutoRoutingMode =
    KtAutoRoutingMode(
      modelRoutingPreference =
        enumByNameOrNull<KtModelRoutingPreference> {
          mode.modelRoutingPreference().getOrNull()?.knownEnum()?.name
        }
    )

  private fun manualRoutingModeToJava(mode: KtManualRoutingMode): GenaiManualRoutingMode {
    val builder = GenaiManualRoutingMode.builder()
    mode.modelName?.let { builder.modelName(it) }
    return builder.build()
  }

  private fun manualRoutingModeFromJava(mode: GenaiManualRoutingMode): KtManualRoutingMode =
    KtManualRoutingMode(modelName = mode.modelName().getOrNull())

  private fun vertexAiSearchToJava(search: KtVertexAISearch): GenaiVertexAISearch {
    val builder = GenaiVertexAISearch.builder()
    search.datastore?.let { builder.datastore(it) }
    search.engine?.let { builder.engine(it) }
    search.filter?.let { builder.filter(it) }
    search.maxResults?.let { builder.maxResults(it) }
    search.dataStoreSpecs?.let { specs ->
      builder.dataStoreSpecs(specs.map { dataStoreSpecToJava(it) })
    }
    return builder.build()
  }

  private fun vertexAiSearchFromJava(search: GenaiVertexAISearch): KtVertexAISearch =
    KtVertexAISearch(
      dataStoreSpecs = search.dataStoreSpecs().getOrNull()?.map { dataStoreSpecFromJava(it) },
      datastore = search.datastore().getOrNull(),
      engine = search.engine().getOrNull(),
      filter = search.filter().getOrNull(),
      maxResults = search.maxResults().getOrNull(),
    )

  private fun dataStoreSpecToJava(
    spec: KtVertexAISearchDataStoreSpec
  ): GenaiVertexAISearchDataStoreSpec {
    val builder = GenaiVertexAISearchDataStoreSpec.builder()
    spec.dataStore?.let { builder.dataStore(it) }
    spec.filter?.let { builder.filter(it) }
    return builder.build()
  }

  private fun dataStoreSpecFromJava(
    spec: GenaiVertexAISearchDataStoreSpec
  ): KtVertexAISearchDataStoreSpec =
    KtVertexAISearchDataStoreSpec(
      dataStore = spec.dataStore().getOrNull(),
      filter = spec.filter().getOrNull(),
    )

  private fun thinkingConfigToJava(config: KtThinkingConfig): GenaiThinkingConfig {
    val builder = GenaiThinkingConfig.builder()
    config.includeThoughts?.let { builder.includeThoughts(it) }
    config.thinkingBudget?.let { builder.thinkingBudget(it) }
    config.thinkingLevel?.let { builder.thinkingLevel(GenaiThinkingLevel(it.name)) }
    return builder.build()
  }

  private fun thinkingConfigFromJava(config: GenaiThinkingConfig): KtThinkingConfig =
    KtThinkingConfig(
      includeThoughts = config.includeThoughts().getOrNull(),
      thinkingBudget = config.thinkingBudget().getOrNull(),
      thinkingLevel =
        enumByNameOrNull<KtThinkingLevel> { config.thinkingLevel().getOrNull()?.knownEnum()?.name },
    )

  // The Kotlin FunctionCallingConfig models allowedFunctionNames and streamFunctionCallArguments;
  // genai's `mode` has no Kotlin field and is dropped.
  private fun toolConfigToJava(config: KtToolConfig): GenaiToolConfig {
    val builder = GenaiToolConfig.builder()
    config.functionCallingConfig?.let {
      builder.functionCallingConfig(functionCallingConfigToJava(it))
    }
    return builder.build()
  }

  private fun toolConfigFromJava(config: GenaiToolConfig): KtToolConfig =
    KtToolConfig(
      functionCallingConfig =
        config.functionCallingConfig().getOrNull()?.let { functionCallingConfigFromJava(it) }
    )

  private fun functionCallingConfigToJava(
    config: KtFunctionCallingConfig
  ): GenaiFunctionCallingConfig {
    val builder = GenaiFunctionCallingConfig.builder()
    config.allowedFunctionNames?.let { builder.allowedFunctionNames(it) }
    config.streamFunctionCallArguments?.let { builder.streamFunctionCallArguments(it) }
    return builder.build()
  }

  private fun functionCallingConfigFromJava(
    config: GenaiFunctionCallingConfig
  ): KtFunctionCallingConfig =
    KtFunctionCallingConfig(
      allowedFunctionNames = config.allowedFunctionNames().getOrNull(),
      streamFunctionCallArguments = config.streamFunctionCallArguments().getOrNull(),
    )

  // The Kotlin SafetySetting models category + threshold; genai's `method` has no Kotlin field and
  // is dropped (a Kotlin gap, not a bridge one).
  private fun safetySettingToJava(setting: KtSafetySetting): GenaiSafetySetting {
    val builder = GenaiSafetySetting.builder()
    setting.category?.let { builder.category(GenaiHarmCategory(it.name)) }
    setting.threshold?.let { builder.threshold(GenaiHarmBlockThreshold(it.name)) }
    return builder.build()
  }

  private fun safetySettingFromJava(setting: GenaiSafetySetting): KtSafetySetting =
    KtSafetySetting(
      category =
        enumByNameOrNull<KtHarmCategory> { setting.category().getOrNull()?.knownEnum()?.name },
      threshold =
        enumByNameOrNull<KtHarmBlockThreshold> {
          setting.threshold().getOrNull()?.knownEnum()?.name
        },
    )
}
