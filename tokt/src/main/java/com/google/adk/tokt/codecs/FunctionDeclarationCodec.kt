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

import com.google.adk.kt.types.FunctionDeclaration as KtFunctionDeclaration
import com.google.genai.types.FunctionDeclaration as GenaiFunctionDeclaration
import kotlin.jvm.optionals.getOrNull

/**
 * Converts a tool's [FunctionDeclaration][KtFunctionDeclaration] between the genai type the ADK
 * Java facade exposes (`BaseTool.declaration()`) and the Kotlin's `kt.types.FunctionDeclaration`.
 *
 * Lets the Kotlin see a Java tool's schema so a model can be prompted to call it, enabling
 * LLM-driven tool calling through [JavaToolToKt]. The parameter `Schema` is carried by
 * [SchemaCodec].
 */
internal object FunctionDeclarationCodec {

  /** Returns the Kotlin [KtFunctionDeclaration] view of the genai [declaration]. */
  fun fromJava(declaration: GenaiFunctionDeclaration): KtFunctionDeclaration =
    KtFunctionDeclaration(
      name = declaration.name().getOrNull() ?: "",
      description = declaration.description().getOrNull() ?: "",
      parameters = declaration.parameters().getOrNull()?.let { SchemaCodec.fromJava(it) },
    )

  /** Returns the genai [GenaiFunctionDeclaration] view of the Kotlin [declaration]. */
  fun toJava(declaration: KtFunctionDeclaration): GenaiFunctionDeclaration {
    val builder =
      GenaiFunctionDeclaration.builder().name(declaration.name).description(declaration.description)
    declaration.parameters?.let { builder.parameters(SchemaCodec.toJava(it)) }
    return builder.build()
  }
}
