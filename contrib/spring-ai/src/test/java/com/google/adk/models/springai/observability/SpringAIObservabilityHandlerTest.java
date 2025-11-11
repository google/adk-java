/*
 * Copyright 2025 Google LLC
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
package com.google.adk.models.springai.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.models.springai.properties.SpringAIProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpringAIObservabilityHandlerTest {

  private SpringAIObservabilityHandler handler;
  private SpringAIProperties.Observability config;
  private MeterRegistry meterRegistry;

  @BeforeEach
  void setUp() {
    config = new SpringAIProperties.Observability();
    config.setEnabled(true);
    config.setMetricsEnabled(true);
    config.setIncludeContent(true);
    meterRegistry = new SimpleMeterRegistry();
    handler = new SpringAIObservabilityHandler(config, meterRegistry);
  }

  @Test
  void testRequestContextCreation() {
    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");

    assertThat(context.getModelName()).isEqualTo("gpt-4o-mini");
    assertThat(context.getRequestType()).isEqualTo("chat");
    assertThat(context.isObservable()).isTrue();
    assertThat(context.getStartTime()).isNotNull();
  }

  @Test
  void testRequestContextWhenDisabled() {
    config.setEnabled(false);
    handler = new SpringAIObservabilityHandler(config, meterRegistry);

    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");

    assertThat(context.isObservable()).isFalse();
  }

  @Test
  void testSuccessfulRequestRecording() {
    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");

    handler.recordSuccess(context, 100, 50, 50);

    // Verify metrics using Micrometer API
    Counter totalCounter =
        meterRegistry.find("spring.ai.requests.total").tag("model", "gpt-4o-mini").counter();
    assertThat(totalCounter).isNotNull();
    assertThat(totalCounter.count()).isEqualTo(1.0);

    Counter successCounter =
        meterRegistry.find("spring.ai.requests.success").tag("model", "gpt-4o-mini").counter();
    assertThat(successCounter).isNotNull();
    assertThat(successCounter.count()).isEqualTo(1.0);

    Gauge tokenGauge =
        meterRegistry.find("spring.ai.tokens.total").tag("model", "gpt-4o-mini").gauge();
    assertThat(tokenGauge).isNotNull();
    assertThat(tokenGauge.value()).isEqualTo(100.0);
  }

  @Test
  void testErrorRecording() {
    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");

    RuntimeException error = new RuntimeException("Test error");
    handler.recordError(context, error);

    // Verify error metrics using Micrometer API
    Counter totalCounter =
        meterRegistry.find("spring.ai.requests.total").tag("model", "gpt-4o-mini").counter();
    assertThat(totalCounter).isNotNull();
    assertThat(totalCounter.count()).isEqualTo(1.0);

    Counter errorCounter =
        meterRegistry.find("spring.ai.requests.error").tag("model", "gpt-4o-mini").counter();
    assertThat(errorCounter).isNotNull();
    assertThat(errorCounter.count()).isEqualTo(1.0);

    Counter errorTypeCounter =
        meterRegistry
            .find("spring.ai.errors.by.type")
            .tag("error.type", "RuntimeException")
            .counter();
    assertThat(errorTypeCounter).isNotNull();
    assertThat(errorTypeCounter.count()).isEqualTo(1.0);
  }

  @Test
  void testContentLogging() {
    // Content logging is tested through the logging framework integration
    // This test verifies the methods don't throw exceptions
    handler.logRequest("Test request content", "gpt-4o-mini");
    handler.logResponse("Test response content", "gpt-4o-mini");
  }

  @Test
  void testMetricsDisabled() {
    config.setMetricsEnabled(false);
    MeterRegistry disabledMeterRegistry = new SimpleMeterRegistry();
    handler = new SpringAIObservabilityHandler(config, disabledMeterRegistry);

    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");
    handler.recordSuccess(context, 100, 50, 50);

    // Verify no metrics were recorded
    assertThat(disabledMeterRegistry.find("spring.ai.requests.success").counter()).isNull();
    assertThat(disabledMeterRegistry.find("spring.ai.tokens.total").gauge()).isNull();
  }

  @Test
  void testObservabilityDisabled() {
    config.setEnabled(false);
    MeterRegistry disabledMeterRegistry = new SimpleMeterRegistry();
    handler = new SpringAIObservabilityHandler(config, disabledMeterRegistry);

    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");
    handler.recordSuccess(context, 100, 50, 50);

    // Should not record metrics when observability is disabled
    assertThat(disabledMeterRegistry.find("spring.ai.requests.total").counter()).isNull();
    assertThat(disabledMeterRegistry.find("spring.ai.requests.success").counter()).isNull();
  }

  @Test
  void testMultipleRequests() {
    SpringAIObservabilityHandler.RequestContext context1 =
        handler.startRequest("gpt-4o-mini", "chat");
    SpringAIObservabilityHandler.RequestContext context2 =
        handler.startRequest("claude-3-5-sonnet", "streaming");

    handler.recordSuccess(context1, 100, 50, 50);
    handler.recordSuccess(context2, 150, 80, 70);

    // Verify metrics for first model
    Counter totalCounter1 =
        meterRegistry.find("spring.ai.requests.total").tag("model", "gpt-4o-mini").counter();
    assertThat(totalCounter1).isNotNull();
    assertThat(totalCounter1.count()).isEqualTo(1.0);

    Gauge tokenGauge1 =
        meterRegistry.find("spring.ai.tokens.total").tag("model", "gpt-4o-mini").gauge();
    assertThat(tokenGauge1).isNotNull();
    assertThat(tokenGauge1.value()).isEqualTo(100.0);

    // Verify metrics for second model
    Counter totalCounter2 =
        meterRegistry.find("spring.ai.requests.total").tag("model", "claude-3-5-sonnet").counter();
    assertThat(totalCounter2).isNotNull();
    assertThat(totalCounter2.count()).isEqualTo(1.0);

    Gauge tokenGauge2 =
        meterRegistry.find("spring.ai.tokens.total").tag("model", "claude-3-5-sonnet").gauge();
    assertThat(tokenGauge2).isNotNull();
    assertThat(tokenGauge2.value()).isEqualTo(150.0);
  }

  @Test
  void testMeterRegistryAccess() {
    // Verify we can access the MeterRegistry directly
    assertThat(handler.getMeterRegistry()).isNotNull();
    assertThat(handler.getMeterRegistry()).isEqualTo(meterRegistry);
  }
}
