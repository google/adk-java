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

package com.google.adk.plugins.agentanalytics;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Typed container for structured fields passed to the plugin's event-logging method. */
@AutoValue
abstract class EventData {
  abstract Optional<String> spanIdOverride();

  abstract Optional<String> parentSpanIdOverride();

  abstract Optional<Duration> latency();

  abstract Optional<Duration> timeToFirstToken();

  abstract Optional<String> model();

  abstract Optional<String> modelVersion();

  abstract Optional<Object> usageMetadata();

  abstract String status();

  abstract Optional<String> errorMessage();

  abstract ImmutableMap<String, Object> extraAttributes();

  abstract Optional<String> traceIdOverride();

  // Fallback name for the `agent` column when the InvocationContext has no current agent (e.g.
  // workflow-driven callbacks). Mirrors the Python plugin's fallback to Event.author.
  abstract Optional<String> fallbackAgentName();

  static Builder builder() {
    return new AutoValue_EventData.Builder().setStatus("OK").setExtraAttributes(ImmutableMap.of());
  }

  @AutoValue.Builder
  abstract static class Builder {
    abstract Builder setSpanIdOverride(String value);

    abstract Builder setParentSpanIdOverride(String value);

    abstract Builder setLatency(Duration value);

    abstract Builder setTimeToFirstToken(Duration value);

    abstract Builder setModel(String value);

    abstract Builder setModelVersion(String value);

    abstract Builder setUsageMetadata(Object value);

    abstract Builder setStatus(String value);

    abstract Builder setErrorMessage(String value);

    abstract Builder setExtraAttributes(Map<String, Object> value);

    abstract Builder setTraceIdOverride(String value);

    abstract Builder setFallbackAgentName(String value);

    abstract EventData build();
  }
}
