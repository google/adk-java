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

package com.google.adk.web.config;

import com.google.adk.web.service.ApiServerSpanExporter;
import com.google.adk.web.service.ApiServerSpanExporterConfig;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration class for OpenTelemetry, setting up the tracer provider and span exporter. */
@Configuration
public class OpenTelemetryConfig {
  private static final Logger otelLog = LoggerFactory.getLogger(OpenTelemetryConfig.class);

  @Bean
  public ApiServerSpanExporterConfig apiServerSpanExporterConfig(
      @Value("${adk.debug.trace.max-spans:#{null}}") Optional<Integer> maxSpansToKeep) {
    return ApiServerSpanExporterConfig.builder().maxSpansToKeep(maxSpansToKeep).build();
  }

  @Bean
  public ApiServerSpanExporter apiServerSpanExporter(ApiServerSpanExporterConfig config) {
    return new ApiServerSpanExporter(config);
  }

  @Bean(destroyMethod = "shutdown")
  public SdkTracerProvider sdkTracerProvider(ApiServerSpanExporter apiServerSpanExporter) {
    otelLog.debug("Configuring SdkTracerProvider with ApiServerSpanExporter.");
    Resource resource =
        Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.of(AttributeKey.stringKey("service.name"), "adk-web-server")));

    return SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(apiServerSpanExporter))
        .setResource(resource)
        .build();
  }

  @Bean
  public OpenTelemetry openTelemetrySdk(SdkTracerProvider sdkTracerProvider) {
    otelLog.debug("Configuring OpenTelemetrySdk and registering globally.");

    // Check if OpenTelemetry has already been set globally (common in tests)
    try {
      otelLog.debug("Registering OpenTelemetry globally.");
      OpenTelemetrySdk sdk =
          OpenTelemetrySdk.builder().setTracerProvider(sdkTracerProvider).buildAndRegisterGlobal();
      Runtime.getRuntime().addShutdownHook(new Thread(sdk::close));
      return sdk;
    } catch (IllegalStateException e) {
      otelLog.debug("OpenTelemetry already registered globally, returning non-global instance.");
      return OpenTelemetrySdk.builder().setTracerProvider(sdkTracerProvider).build();
    }
  }
}
