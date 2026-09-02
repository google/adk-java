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

package com.google.adk.telemetry;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TelemetrySetupTest {

  private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
  private static final ImmutableMap<String, String> EMPTY_ENVIRONMENT = ImmutableMap.of();
  private static final String COLLECTOR = "http://localhost:4318";

  @Before
  public void unclaimTheGlobal() {
    GlobalOpenTelemetry.resetForTest();
  }

  @After
  public void releaseTheGlobal() {
    GlobalOpenTelemetry.resetForTest();
  }

  // Installing providers.

  @Test
  public void maybeSetOtelProviders_callerSpanProcessorReceivesSpansWithTheResource() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    Resource resource = Resource.getDefault().merge(resourceNamed("under-test"));

    TelemetrySetup.maybeSetOtelProviders(
        ImmutableList.of(hooksExportingTo(exporter)), resource, EMPTY_ENVIRONMENT::get);
    GlobalOpenTelemetry.getTracer("test").spanBuilder("a-span").startSpan().end();

    assertThat(exporter.getFinishedSpanItems()).hasSize(1);
    SpanData span = exporter.getFinishedSpanItems().get(0);
    assertThat(span.getName()).isEqualTo("a-span");
    assertThat(span.getResource().getAttribute(SERVICE_NAME)).isEqualTo("under-test");
  }

  @Test
  public void maybeSetOtelProviders_everyHooksValueInTheListContributes() {
    InMemorySpanExporter first = InMemorySpanExporter.create();
    InMemorySpanExporter second = InMemorySpanExporter.create();

    TelemetrySetup.maybeSetOtelProviders(
        ImmutableList.of(hooksExportingTo(first), hooksExportingTo(second)),
        Resource.getDefault(),
        EMPTY_ENVIRONMENT::get);
    GlobalOpenTelemetry.getTracer("test").spanBuilder("a-span").startSpan().end();

    assertThat(first.getFinishedSpanItems()).hasSize(1);
    assertThat(second.getFinishedSpanItems()).hasSize(1);
  }

  @Test
  public void maybeSetOtelProviders_callerMetricReaderAndLogProcessorAlsoReceiveTheirSignal() {
    InMemoryMetricReader metricReader = InMemoryMetricReader.create();
    InMemoryLogRecordExporter logExporter = InMemoryLogRecordExporter.create();
    OtelHooks hooks =
        OtelHooks.builder()
            .metricReaders(ImmutableList.of(metricReader))
            .logRecordProcessors(ImmutableList.of(SimpleLogRecordProcessor.create(logExporter)))
            .build();

    TelemetrySetup.maybeSetOtelProviders(
        ImmutableList.of(hooks), Resource.getDefault(), EMPTY_ENVIRONMENT::get);
    GlobalOpenTelemetry.getMeter("test").counterBuilder("a-counter").build().add(1);
    GlobalOpenTelemetry.get()
        .getLogsBridge()
        .get("test")
        .logRecordBuilder()
        .setBody("a-log")
        .emit();

    assertThat(metricReader.collectAllMetrics()).hasSize(1);
    assertThat(logExporter.getFinishedLogRecordItems()).hasSize(1);
  }

  @Test
  public void maybeSetOtelProviders_installedGlobalPropagatesTraceContextAndBaggage() {
    TelemetrySetup.maybeSetOtelProviders(
        ImmutableList.of(hooksExportingTo(InMemorySpanExporter.create())),
        Resource.getDefault(),
        EMPTY_ENVIRONMENT::get);

    // The SDK builder's own default is a no-op propagator, which would drop every incoming and
    // outgoing traceparent for the life of the process.
    assertThat(GlobalOpenTelemetry.getPropagators().getTextMapPropagator().fields())
        .containsAtLeast("traceparent", "baggage");
  }

  @Test
  public void maybeSetOtelProviders_oneSignalStillClaimsTheWholeGlobal() {
    TelemetrySetup.maybeSetOtelProviders(
        ImmutableList.of(hooksExportingTo(InMemorySpanExporter.create())),
        Resource.getDefault(),
        EMPTY_ENVIRONMENT::get);

    // Java has one global for all three signals, so a traces-only setup takes metrics and logs
    // with it and the application can no longer install its own.
    OpenTelemetrySdk applicationSdk = OpenTelemetrySdk.builder().build();
    assertThrows(IllegalStateException.class, () -> GlobalOpenTelemetry.set(applicationSdk));
  }

  @Test
  public void maybeSetOtelProviders_nothingToInstall_leavesTheGlobalUnclaimed() {
    TelemetrySetup.maybeSetOtelProviders(
        ImmutableList.of(), Resource.getDefault(), EMPTY_ENVIRONMENT::get);

    InMemorySpanExporter applicationExporter = InMemorySpanExporter.create();
    GlobalOpenTelemetry.set(sdkExportingTo(applicationExporter));
    GlobalOpenTelemetry.getTracer("test").spanBuilder("a-span").startSpan().end();

    assertThat(applicationExporter.getFinishedSpanItems()).hasSize(1);
  }

  @Test
  public void maybeSetOtelProviders_globalAlreadyRegistered_leavesItInPlace() {
    InMemorySpanExporter applicationExporter = InMemorySpanExporter.create();
    GlobalOpenTelemetry.set(sdkExportingTo(applicationExporter));
    InMemorySpanExporter adkExporter = InMemorySpanExporter.create();

    TelemetrySetup.maybeSetOtelProviders(
        ImmutableList.of(hooksExportingTo(adkExporter)),
        Resource.getDefault(),
        EMPTY_ENVIRONMENT::get);
    GlobalOpenTelemetry.getTracer("test").spanBuilder("a-span").startSpan().end();

    assertThat(applicationExporter.getFinishedSpanItems()).hasSize(1);
    assertThat(adkExporter.getFinishedSpanItems()).isEmpty();
  }

  // Which signals the OTLP environment variables ask to export.

  @Test
  public void otlpHooksFromEnvironment_tracesEndpoint_exportsTracesOnly() {
    assertConfiguredSignals(
        ImmutableMap.of("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", COLLECTOR + "/v1/traces"),
        /* traces= */ true,
        /* metrics= */ false,
        /* logs= */ false);
  }

  @Test
  public void otlpHooksFromEnvironment_metricsEndpoint_exportsMetricsOnly() {
    assertConfiguredSignals(
        ImmutableMap.of("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", COLLECTOR + "/v1/metrics"),
        /* traces= */ false,
        /* metrics= */ true,
        /* logs= */ false);
  }

  @Test
  public void otlpHooksFromEnvironment_logsEndpoint_exportsLogsOnly() {
    assertConfiguredSignals(
        ImmutableMap.of("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT", COLLECTOR + "/v1/logs"),
        /* traces= */ false,
        /* metrics= */ false,
        /* logs= */ true);
  }

  @Test
  public void otlpHooksFromEnvironment_genericEndpoint_exportsEverySignal() {
    assertConfiguredSignals(
        ImmutableMap.of("OTEL_EXPORTER_OTLP_ENDPOINT", COLLECTOR),
        /* traces= */ true,
        /* metrics= */ true,
        /* logs= */ true);
  }

  @Test
  public void otlpHooksFromEnvironment_noEndpoint_exportsNothing() {
    assertConfiguredSignals(
        ImmutableMap.of("OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer token"),
        /* traces= */ false,
        /* metrics= */ false,
        /* logs= */ false);
  }

  // Endpoints.

  @Test
  public void otlpEndpoint_signalEndpointIsUsedWhole() {
    assertThat(
            TelemetrySetup.otlpEndpoint(
                "http://collector:4318/custom/traces", "http://generic:4318", "/v1/traces"))
        .isEqualTo("http://collector:4318/custom/traces");
  }

  @Test
  public void otlpEndpoint_genericEndpointGainsTheSignalPath() {
    assertThat(TelemetrySetup.otlpEndpoint(null, "http://collector:4318", "/v1/metrics"))
        .isEqualTo("http://collector:4318/v1/metrics");
  }

  @Test
  public void otlpEndpoint_genericEndpointKeepsItsOwnPath() {
    assertThat(TelemetrySetup.otlpEndpoint("", "http://collector:4318/otlp/", "/v1/logs"))
        .isEqualTo("http://collector:4318/otlp/v1/logs");
  }

  @Test
  public void otlpEndpoint_neitherIsSet_isNull() {
    assertThat(TelemetrySetup.otlpEndpoint(null, null, "/v1/traces")).isNull();
    assertThat(TelemetrySetup.otlpEndpoint("", "", "/v1/traces")).isNull();
  }

  // Key-value environment variables.

  @Test
  public void parseKeyValueList_keepsAPlusSignAndLaterSeparatorsInTheValue() {
    // A credential is the usual content of this variable, and base64 is full of plus signs.
    assertThat(TelemetrySetup.parseKeyValueList("authorization=Bearer a+b/c==", "headers"))
        .containsExactly("authorization", "Bearer a+b/c==");
  }

  @Test
  public void parseKeyValueList_percentDecodesTheValue() {
    assertThat(
            TelemetrySetup.parseKeyValueList(
                "greeting=hello%20world%21,accented=caf%C3%A9", "attributes"))
        .containsExactly("greeting", "hello world!", "accented", "caf\u00e9");
  }

  @Test
  public void parseKeyValueList_incompleteEscapeStaysLiteral() {
    assertThat(TelemetrySetup.parseKeyValueList("progress=100%done,trailing=50%", "attributes"))
        .containsExactly("progress", "100%done", "trailing", "50%");
  }

  @Test
  public void parseKeyValueList_dropsAnEntryThatIsNotAPair() {
    assertThat(TelemetrySetup.parseKeyValueList("good=yes,justatoken,=novalue", "attributes"))
        .containsExactly("good", "yes");
  }

  @Test
  public void parseKeyValueList_trimsAroundEveryPart() {
    assertThat(
            TelemetrySetup.parseKeyValueList(" region = us-central1 , tier = prod ", "attributes"))
        .containsExactly("region", "us-central1", "tier", "prod");
  }

  @Test
  public void parseKeyValueList_unset_isEmpty() {
    assertThat(TelemetrySetup.parseKeyValueList(null, "attributes")).isEmpty();
    assertThat(TelemetrySetup.parseKeyValueList("", "attributes")).isEmpty();
    assertThat(TelemetrySetup.parseKeyValueList(" , ,, ", "attributes")).isEmpty();
  }

  // Timeouts.

  @Test
  public void parseTimeout_readsWholeMilliseconds() {
    assertThat(TelemetrySetup.parseTimeout(" 2500 ")).isEqualTo(Duration.ofMillis(2500));
  }

  @Test
  public void parseTimeout_withAUnitSuffix_isIgnored() {
    assertThat(TelemetrySetup.parseTimeout("10s")).isNull();
  }

  @Test
  public void parseTimeout_negative_isIgnored() {
    assertThat(TelemetrySetup.parseTimeout("-1")).isNull();
  }

  @Test
  public void parseTimeout_unset_isNull() {
    assertThat(TelemetrySetup.parseTimeout(null)).isNull();
    assertThat(TelemetrySetup.parseTimeout("")).isNull();
  }

  // Trusted certificates.

  @Test
  public void readTrustedCertificates_readsTheNamedFile() throws IOException {
    byte[] pem = "-----BEGIN CERTIFICATE-----".getBytes(UTF_8);
    Path certificate = Files.createTempFile("trusted", ".pem");
    Files.write(certificate, pem);

    assertThat(TelemetrySetup.readTrustedCertificates(certificate.toString())).isEqualTo(pem);
  }

  @Test
  public void readTrustedCertificates_unreadableFile_isIgnored() {
    assertThat(TelemetrySetup.readTrustedCertificates("/no/such/trusted.pem")).isNull();
  }

  @Test
  public void readTrustedCertificates_unset_isNull() {
    assertThat(TelemetrySetup.readTrustedCertificates(null)).isNull();
    assertThat(TelemetrySetup.readTrustedCertificates("")).isNull();
  }

  // Resources.

  @Test
  public void environmentResource_serviceNameVariableWinsOverTheAttributeList() {
    ImmutableMap<String, String> environment =
        ImmutableMap.of(
            "OTEL_RESOURCE_ATTRIBUTES",
            "service.name=from-the-list,deployment.environment=staging",
            "OTEL_SERVICE_NAME",
            "from-the-variable");

    Resource resource = TelemetrySetup.environmentResource(environment::get);

    assertThat(resource.getAttribute(SERVICE_NAME)).isEqualTo("from-the-variable");
    assertThat(resource.getAttribute(AttributeKey.stringKey("deployment.environment")))
        .isEqualTo("staging");
  }

  @Test
  public void environmentResource_attributesLieOverTheDefaultResource() {
    ImmutableMap<String, String> environment =
        ImmutableMap.of("OTEL_RESOURCE_ATTRIBUTES", "deployment.environment=staging");

    Resource resource = TelemetrySetup.environmentResource(environment::get);

    assertThat(resource.getAttribute(AttributeKey.stringKey("deployment.environment")))
        .isEqualTo("staging");
    assertThat(resource.getAttributes().asMap())
        .containsAtLeastEntriesIn(Resource.getDefault().getAttributes().asMap());
  }

  @Test
  public void environmentResource_noVariables_isTheDefaultResource() {
    assertThat(TelemetrySetup.environmentResource(EMPTY_ENVIRONMENT::get))
        .isEqualTo(Resource.getDefault());
  }

  private static void assertConfiguredSignals(
      ImmutableMap<String, String> environment, boolean traces, boolean metrics, boolean logs) {
    OtelHooks hooks = TelemetrySetup.otlpHooksFromEnvironment(environment::get);
    try {
      assertThat(hooks.spanProcessors()).hasSize(traces ? 1 : 0);
      assertThat(hooks.metricReaders()).hasSize(metrics ? 1 : 0);
      assertThat(hooks.logRecordProcessors()).hasSize(logs ? 1 : 0);
    } finally {
      hooks.spanProcessors().forEach(SpanProcessor::shutdown);
      hooks.metricReaders().forEach(MetricReader::shutdown);
      hooks.logRecordProcessors().forEach(LogRecordProcessor::shutdown);
    }
  }

  private static OtelHooks hooksExportingTo(InMemorySpanExporter exporter) {
    return OtelHooks.builder()
        .spanProcessors(ImmutableList.of(SimpleSpanProcessor.create(exporter)))
        .build();
  }

  private static OpenTelemetrySdk sdkExportingTo(InMemorySpanExporter exporter) {
    return OpenTelemetrySdk.builder()
        .setTracerProvider(
            SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build())
        .build();
  }

  private static Resource resourceNamed(String serviceName) {
    return Resource.builder().put(SERVICE_NAME, serviceName).build();
  }
}
