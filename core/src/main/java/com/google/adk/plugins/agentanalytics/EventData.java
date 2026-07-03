package com.google.adk.plugins.agentanalytics;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/** Typed container for structured fields passed to _log_event. */
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

    abstract EventData build();
  }
}
