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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpringAIObservabilityHandlerTest {

  private SpringAIObservabilityHandler handler;
  private SpringAIProperties.Observability config;

  @BeforeEach
  void setUp() {
    config = new SpringAIProperties.Observability();
    config.setEnabled(true);
    config.setMetricsEnabled(true);
    config.setIncludeContent(true);
    handler = new SpringAIObservabilityHandler(config);
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
    handler = new SpringAIObservabilityHandler(config);

    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");

    assertThat(context.isObservable()).isFalse();
  }

  @Test
  void testSuccessfulRequestRecording() {
    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");

    handler.recordSuccess(context, 100, 50, 50);

    Map<String, Number> metrics = handler.getMetrics();
    assertThat(metrics).isNotEmpty();
    assertThat(metrics.get("spring_ai_requests_total_gpt_4o_mini_chat")).isEqualTo(1L);
    assertThat(metrics.get("spring_ai_requests_success_gpt_4o_mini_chat")).isEqualTo(1L);
    assertThat(metrics.get("spring_ai_tokens_total_gpt_4o_mini")).isEqualTo(100.0);
  }

  @Test
  void testErrorRecording() {
    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");

    RuntimeException error = new RuntimeException("Test error");
    handler.recordError(context, error);

    Map<String, Number> metrics = handler.getMetrics();
    assertThat(metrics).isNotEmpty();
    assertThat(metrics.get("spring_ai_requests_total_gpt_4o_mini_chat")).isEqualTo(1L);
    assertThat(metrics.get("spring_ai_requests_error_gpt_4o_mini_chat")).isEqualTo(1L);
    assertThat(metrics.get("spring_ai_errors_by_type_RuntimeException")).isEqualTo(1L);
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
    handler = new SpringAIObservabilityHandler(config);

    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");
    handler.recordSuccess(context, 100, 50, 50);

    Map<String, Number> metrics = handler.getMetrics();
    assertThat(metrics).isEmpty();
  }

  @Test
  void testObservabilityDisabled() {
    config.setEnabled(false);
    handler = new SpringAIObservabilityHandler(config);

    SpringAIObservabilityHandler.RequestContext context =
        handler.startRequest("gpt-4o-mini", "chat");
    handler.recordSuccess(context, 100, 50, 50);

    // Should not record metrics when disabled
    Map<String, Number> metrics = handler.getMetrics();
    assertThat(metrics).isEmpty();
  }

  @Test
  void testMultipleRequests() {
    SpringAIObservabilityHandler.RequestContext context1 =
        handler.startRequest("gpt-4o-mini", "chat");
    SpringAIObservabilityHandler.RequestContext context2 =
        handler.startRequest("claude-3-5-sonnet", "streaming");

    handler.recordSuccess(context1, 100, 50, 50);
    handler.recordSuccess(context2, 150, 80, 70);

    Map<String, Number> metrics = handler.getMetrics();
    assertThat(metrics.get("spring_ai_requests_total_gpt_4o_mini_chat")).isEqualTo(1L);
    assertThat(metrics.get("spring_ai_requests_total_claude_3_5_sonnet_streaming")).isEqualTo(1L);
    assertThat(metrics.get("spring_ai_tokens_total_gpt_4o_mini")).isEqualTo(100.0);
    assertThat(metrics.get("spring_ai_tokens_total_claude_3_5_sonnet")).isEqualTo(150.0);
  }
}
