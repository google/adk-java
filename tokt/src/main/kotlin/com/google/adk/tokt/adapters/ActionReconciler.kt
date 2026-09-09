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

import com.google.adk.events.EventActions as JavaEventActions
import com.google.adk.kt.events.EventActions as KtEventActions
import com.google.adk.tokt.codecs.EventCompactionCodec
import com.google.adk.tokt.codecs.ToolConfirmationCodec
import com.google.adk.tokt.codecs.agentStateFromJava
import com.google.adk.tokt.codecs.reconcileRemovedSentinels

/**
 * Copies what a bridged tool or plugin callback wrote on its [JavaEventActions] onto the live
 * [KtEventActions] the engine reads. Fields the live view already writes through are re-applied
 * idempotently; skip-summarization, tool confirmations, agentState and compaction are carried here.
 * A signal the engine cannot represent (requestedAuthConfigs, deletedArtifactIds) throws.
 */
internal fun reconcileActionsToKt(java: JavaEventActions, kt: KtEventActions) {
  require(java.requestedAuthConfigs().isNullOrEmpty()) {
    "EventActions.requestedAuthConfigs is not supported by the ADK Kotlin engine"
  }
  require(java.deletedArtifactIds().isNullOrEmpty()) {
    "EventActions.deletedArtifactIds is not supported by the ADK Kotlin engine"
  }
  java.escalate().ifPresent { kt.escalate = it }
  java.transferToAgent().ifPresent { kt.transferToAgent = it }
  java.skipSummarization().ifPresent { kt.skipSummarization = it }
  java.agentState().ifPresent { kt.agentState = agentStateFromJava(it) }
  java.compaction().ifPresent { kt.compaction = EventCompactionCodec.fromJava(it) }
  kt.endOfAgent = kt.endOfAgent || java.endOfAgent()
  // Merge deltas only when the tool replaced the actions (else they are the same live Kotlin map).
  if (java.stateDelta() !== kt.stateDelta) kt.stateDelta.putAll(java.stateDelta())
  if (java.artifactDelta() !== kt.artifactDelta) kt.artifactDelta.putAll(java.artifactDelta())
  // A Java state removal writes the Java sentinel into the delta; map it to the Kotlin one.
  reconcileRemovedSentinels(kt.stateDelta)
  for ((id, confirmation) in java.requestedToolConfirmations()) {
    kt.requestedToolConfirmations[id] = ToolConfirmationCodec.fromJava(confirmation)
  }
}
