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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.OpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.SdkLoggerProviderBuilder;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Installs the global OpenTelemetry providers that ADK exports its telemetry through.
 *
 * <p>An application that embeds ADK hands over its own span processors, metric readers and log
 * record processors as {@link OtelHooks}, and they become the global providers that {@link Tracing}
 * and {@link Metrics} write to.
 *
 * <p>Call this before anything else in the process reads the global OpenTelemetry instance. Reading
 * it while it is unset registers a no-op instance in it, and the global can only be claimed once,
 * so a read that happens first leaves nothing for this class to install. {@link Tracing} and {@link
 * Metrics} both read it as they are class-loaded, which is early enough that touching an agent, a
 * runner or a flow before calling this is enough to lose the setup.
 */
public final class TelemetrySetup {

  private static final Logger logger = LoggerFactory.getLogger(TelemetrySetup.class);

  private static final Function<String, String> SYSTEM_ENVIRONMENT = System::getenv;

  private static final String OTLP_VARIABLE_PREFIX = "OTEL_EXPORTER_OTLP_";
  private static final String SERVICE_NAME_VARIABLE = "OTEL_SERVICE_NAME";
  private static final String RESOURCE_ATTRIBUTES_VARIABLE = "OTEL_RESOURCE_ATTRIBUTES";
  private static final String SERVICE_NAME_ATTRIBUTE = "service.name";

  /**
   * Installs global OpenTelemetry providers carrying {@code otelHooksToSetUp}, resourced with
   * whatever the environment declares.
   *
   * @see #maybeSetOtelProviders(List, Resource)
   */
  public static void maybeSetOtelProviders(List<OtelHooks> otelHooksToSetUp) {
    maybeSetOtelProviders(
        otelHooksToSetUp, environmentResource(SYSTEM_ENVIRONMENT), SYSTEM_ENVIRONMENT);
  }

  /**
   * Installs global OpenTelemetry providers carrying {@code otelHooksToSetUp}.
   *
   * <p>Every hooks value in the list contributes: a provider is built per signal from all the
   * processors and readers given for it, carrying {@code otelResource}, and the instance installed
   * propagates context as W3C trace context and baggage.
   *
   * <p>Java registers all three signals through one global instance, so claiming it for one signal
   * claims it for all of them. A signal nobody asked for gets an empty provider that discards
   * everything, and the application cannot install its own for that signal afterwards.
   *
   * <p>Generic OTLP exporters are added on top when the standard environment variables ask for
   * them: {@code OTEL_EXPORTER_OTLP_ENDPOINT}, {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}, {@code
   * OTEL_EXPORTER_OTLP_METRICS_ENDPOINT} and {@code OTEL_EXPORTER_OTLP_LOGS_ENDPOINT}. Those
   * exporters also take the headers, timeout, compression and trusted certificates the matching
   * {@code OTEL_EXPORTER_OTLP_*} variables declare, per signal or generically. See <a
   * href="https://opentelemetry.io/docs/languages/sdk-configuration/otlp-exporter/">the OTLP
   * exporter configuration</a> for how they are used.
   *
   * <p>This is the "maybe" part: an application that registered its own OpenTelemetry before ADK
   * started keeps it, and calling this anyway is not an error. With nothing at all to install, the
   * global is left unclaimed so the application can still install its own later.
   *
   * @param otelHooksToSetUp per-signal processors and readers to add to the providers
   * @param otelResource the resource the providers report themselves with
   */
  public static void maybeSetOtelProviders(
      List<OtelHooks> otelHooksToSetUp, Resource otelResource) {
    maybeSetOtelProviders(otelHooksToSetUp, otelResource, SYSTEM_ENVIRONMENT);
  }

  /**
   * The body of {@link #maybeSetOtelProviders(List, Resource)}, reading configuration through
   * {@code environment} rather than straight from the process environment so a test can drive it.
   */
  @VisibleForTesting
  static void maybeSetOtelProviders(
      List<OtelHooks> otelHooksToSetUp,
      Resource otelResource,
      Function<String, String> environment) {
    OtelHooks otlpHooks = otlpHooksFromEnvironment(environment);
    ImmutableList<OtelHooks> hooksToSetUp =
        ImmutableList.<OtelHooks>builder().addAll(otelHooksToSetUp).add(otlpHooks).build();

    List<SpanProcessor> spanProcessors = new ArrayList<>();
    List<MetricReader> metricReaders = new ArrayList<>();
    List<LogRecordProcessor> logRecordProcessors = new ArrayList<>();
    for (OtelHooks otelHooks : hooksToSetUp) {
      spanProcessors.addAll(otelHooks.spanProcessors());
      metricReaders.addAll(otelHooks.metricReaders());
      logRecordProcessors.addAll(otelHooks.logRecordProcessors());
    }

    // Java registers all three signals through a single global. Claiming it for providers with
    // nothing behind them would lock the application out of installing its own.
    if (spanProcessors.isEmpty() && metricReaders.isEmpty() && logRecordProcessors.isEmpty()) {
      return;
    }

    // Without this the builder defaults to no-op propagators, losing W3C traceparent for good.
    OpenTelemetrySdkBuilder sdkBuilder =
        OpenTelemetrySdk.builder()
            .setPropagators(
                ContextPropagators.create(
                    TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(),
                        W3CBaggagePropagator.getInstance())));
    if (!spanProcessors.isEmpty()) {
      SdkTracerProviderBuilder tracerProvider =
          SdkTracerProvider.builder().setResource(otelResource);
      spanProcessors.forEach(tracerProvider::addSpanProcessor);
      sdkBuilder.setTracerProvider(tracerProvider.build());
    }
    if (!metricReaders.isEmpty()) {
      SdkMeterProviderBuilder meterProvider = SdkMeterProvider.builder().setResource(otelResource);
      metricReaders.forEach(meterProvider::registerMetricReader);
      sdkBuilder.setMeterProvider(meterProvider.build());
    }
    if (!logRecordProcessors.isEmpty()) {
      SdkLoggerProviderBuilder loggerProvider =
          SdkLoggerProvider.builder().setResource(otelResource);
      logRecordProcessors.forEach(loggerProvider::addLogRecordProcessor);
      sdkBuilder.setLoggerProvider(loggerProvider.build());
    }

    try {
      GlobalOpenTelemetry.set(sdkBuilder.build());
    } catch (IllegalStateException alreadyRegistered) {
      logger.warn(
          "OpenTelemetry is already registered globally, so ADK installed nothing and the"
              + " telemetry configured here is being discarded. Call maybeSetOtelProviders before"
              + " anything else in the process reads the global OpenTelemetry instance.");
      // The caller's processors and readers stay theirs to shut down; the OTLP exporters were
      // built here from the environment and nothing else can reach them.
      shutdown(otlpHooks);
    }
  }

  /** Exporters for whichever signals the standard OTLP environment variables ask to export. */
  @VisibleForTesting
  static OtelHooks otlpHooksFromEnvironment(Function<String, String> environment) {
    OtelHooks.Builder otelHooks = OtelHooks.builder();

    OtlpSettings traces = otlpSettings("TRACES", "/v1/traces", environment);
    if (traces != null) {
      OtlpHttpSpanExporterBuilder exporter =
          OtlpHttpSpanExporter.builder().setEndpoint(traces.endpoint);
      traces.applyTo(
          exporter::addHeader,
          exporter::setTimeout,
          exporter::setCompression,
          exporter::setTrustedCertificates);
      otelHooks.spanProcessors(
          ImmutableList.of(BatchSpanProcessor.builder(exporter.build()).build()));
    }

    OtlpSettings metrics = otlpSettings("METRICS", "/v1/metrics", environment);
    if (metrics != null) {
      OtlpHttpMetricExporterBuilder exporter =
          OtlpHttpMetricExporter.builder().setEndpoint(metrics.endpoint);
      metrics.applyTo(
          exporter::addHeader,
          exporter::setTimeout,
          exporter::setCompression,
          exporter::setTrustedCertificates);
      otelHooks.metricReaders(ImmutableList.of(PeriodicMetricReader.create(exporter.build())));
    }

    OtlpSettings logs = otlpSettings("LOGS", "/v1/logs", environment);
    if (logs != null) {
      OtlpHttpLogRecordExporterBuilder exporter =
          OtlpHttpLogRecordExporter.builder().setEndpoint(logs.endpoint);
      logs.applyTo(
          exporter::addHeader,
          exporter::setTimeout,
          exporter::setCompression,
          exporter::setTrustedCertificates);
      otelHooks.logRecordProcessors(
          ImmutableList.of(BatchLogRecordProcessor.builder(exporter.build()).build()));
    }

    return otelHooks.build();
  }

  /**
   * What the standard OTLP environment variables configure for one signal. Java's exporter builders
   * read no environment of their own, so everything the variables say has to be applied by hand.
   */
  private static final class OtlpSettings {
    private final String endpoint;
    private final ImmutableMap<String, String> headers;
    private final @Nullable Duration timeout;
    private final @Nullable String compression;
    private final byte @Nullable [] trustedCertificates;

    OtlpSettings(
        String endpoint,
        ImmutableMap<String, String> headers,
        @Nullable Duration timeout,
        @Nullable String compression,
        byte @Nullable [] trustedCertificates) {
      this.endpoint = endpoint;
      this.headers = headers;
      this.timeout = timeout;
      this.compression = compression;
      this.trustedCertificates = trustedCertificates;
    }

    /**
     * Applies everything but the endpoint to one signal's exporter builder. The three builders have
     * the same setters and no common supertype, so the caller passes them in.
     */
    void applyTo(
        BiConsumer<String, String> addHeader,
        Consumer<Duration> setTimeout,
        Consumer<String> setCompression,
        Consumer<byte[]> setTrustedCertificates) {
      headers.forEach(addHeader);
      if (timeout != null) {
        setTimeout.accept(timeout);
      }
      if (compression != null) {
        setCompression.accept(compression);
      }
      if (trustedCertificates != null) {
        setTrustedCertificates.accept(trustedCertificates);
      }
    }
  }

  /**
   * The OTLP settings for one signal, or null if no endpoint variable asks for that signal to be
   * exported.
   *
   * @param signal the infix the signal's own variables carry, such as {@code TRACES}
   * @param signalPath the path appended to a generic endpoint, such as {@code /v1/traces}
   */
  private static @Nullable OtlpSettings otlpSettings(
      String signal, String signalPath, Function<String, String> environment) {
    String endpoint =
        otlpEndpoint(
            environment.apply(OTLP_VARIABLE_PREFIX + signal + "_ENDPOINT"),
            environment.apply(OTLP_VARIABLE_PREFIX + "ENDPOINT"),
            signalPath);
    if (endpoint == null) {
      return null;
    }
    return new OtlpSettings(
        endpoint,
        parseKeyValueList(
            otlpSetting(signal, "HEADERS", environment), "the OTLP exporter headers for " + signal),
        parseTimeout(otlpSetting(signal, "TIMEOUT", environment)),
        otlpSetting(signal, "COMPRESSION", environment),
        readTrustedCertificates(otlpSetting(signal, "CERTIFICATE", environment)));
  }

  /** One OTLP setting, taken from the signal's own variable and falling back to the generic one. */
  private static @Nullable String otlpSetting(
      String signal, String setting, Function<String, String> environment) {
    String forThisSignal = environment.apply(OTLP_VARIABLE_PREFIX + signal + "_" + setting);
    return Strings.isNullOrEmpty(forThisSignal)
        ? Strings.emptyToNull(environment.apply(OTLP_VARIABLE_PREFIX + setting))
        : forThisSignal;
  }

  /**
   * An export timeout in whole milliseconds, which is the unit the OpenTelemetry specification
   * gives {@code OTEL_EXPORTER_OTLP_TIMEOUT}. Anything else, a unit suffix or a negative number
   * included, is ignored and leaves the exporter on its own default.
   */
  @VisibleForTesting
  static @Nullable Duration parseTimeout(@Nullable String timeout) {
    if (Strings.isNullOrEmpty(timeout)) {
      return null;
    }
    long milliseconds;
    try {
      milliseconds = Long.parseLong(timeout.trim());
    } catch (NumberFormatException e) {
      logger.warn(
          "Ignoring OTLP exporter timeout {}, which is not a whole number of milliseconds.",
          timeout);
      return null;
    }
    if (milliseconds < 0) {
      logger.warn("Ignoring OTLP exporter timeout {}, which is negative.", timeout);
      return null;
    }
    return Duration.ofMillis(milliseconds);
  }

  /** The PEM certificate chain in the file the certificate variable names. */
  @VisibleForTesting
  static byte @Nullable [] readTrustedCertificates(@Nullable String path) {
    if (Strings.isNullOrEmpty(path)) {
      return null;
    }
    try {
      return Files.readAllBytes(Paths.get(path));
    } catch (IOException | RuntimeException e) {
      logger.warn("Ignoring OTLP exporter certificate file {}, which could not be read.", path, e);
      return null;
    }
  }

  /**
   * OpenTelemetry's default resource with whatever {@code OTEL_SERVICE_NAME} and {@code
   * OTEL_RESOURCE_ATTRIBUTES} declare laid over it. A service name in its own variable wins over
   * one in the attribute list.
   */
  @VisibleForTesting
  static Resource environmentResource(Function<String, String> environment) {
    AttributesBuilder attributes = Attributes.builder();
    parseKeyValueList(environment.apply(RESOURCE_ATTRIBUTES_VARIABLE), RESOURCE_ATTRIBUTES_VARIABLE)
        .forEach(attributes::put);
    String serviceName = environment.apply(SERVICE_NAME_VARIABLE);
    if (!Strings.isNullOrEmpty(serviceName)) {
      attributes.put(SERVICE_NAME_ATTRIBUTE, serviceName);
    }
    Attributes declared = attributes.build();
    return declared.isEmpty()
        ? Resource.getDefault()
        : Resource.getDefault().merge(Resource.create(declared));
  }

  /**
   * A {@code key1=value1,key2=value2} environment variable, with each value percent-decoded. An
   * entry that is not a key-value pair is dropped.
   *
   * <p>{@code description} names the variable for the log line an entry is dropped on. The entry
   * itself is never logged. One of the variables parsed here is the OTLP exporter headers, where a
   * malformed entry is most likely a piece of a credential.
   */
  @VisibleForTesting
  static ImmutableMap<String, String> parseKeyValueList(@Nullable String list, String description) {
    if (Strings.isNullOrEmpty(list)) {
      return ImmutableMap.of();
    }
    Map<String, String> parsed = new LinkedHashMap<>();
    int position = 0;
    for (String entry : Splitter.on(',').trimResults().omitEmptyStrings().split(list)) {
      position++;
      int separator = entry.indexOf('=');
      if (separator <= 0) {
        logger.warn("Ignoring entry {} of {}, which is not key=value.", position, description);
        continue;
      }
      parsed.put(
          entry.substring(0, separator).trim(),
          percentDecode(entry.substring(separator + 1).trim()));
    }
    return ImmutableMap.copyOf(parsed);
  }

  /**
   * Percent-decoding as the W3C Baggage syntax uses it, which is how the OpenTelemetry
   * specification encodes these values.
   *
   * <p>This is not {@code URLDecoder}, which decodes for HTML form submission instead: it turns a
   * {@code +} into a space, which corrupts any credential or base64 value carrying one, and it
   * throws on a percent sign that does not introduce two hex digits, which turns a stray {@code %}
   * in a resource attribute into a startup failure. Here a {@code +} stays a plus sign and an
   * incomplete escape stays the literal text it was written as.
   */
  private static String percentDecode(String value) {
    if (value.indexOf('%') < 0) {
      return value;
    }
    // Three bytes per character is the most any character in a Java string contributes to UTF-8:
    // a surrogate pair is two characters and four bytes.
    byte[] decoded = new byte[value.length() * 3];
    int length = 0;
    int i = 0;
    while (i < value.length()) {
      if (value.charAt(i) == '%' && i + 2 < value.length()) {
        int high = Character.digit(value.charAt(i + 1), 16);
        int low = Character.digit(value.charAt(i + 2), 16);
        if (high >= 0 && low >= 0) {
          decoded[length++] = (byte) ((high << 4) + low);
          i += 3;
          continue;
        }
      }
      // Everything up to the next percent sign is literal, and is encoded whole so that a
      // character outside the ASCII range survives.
      int literalEnd = value.indexOf('%', i + 1);
      if (literalEnd < 0) {
        literalEnd = value.length();
      }
      for (byte encoded : value.substring(i, literalEnd).getBytes(UTF_8)) {
        decoded[length++] = encoded;
      }
      i = literalEnd;
    }
    return new String(decoded, 0, length, UTF_8);
  }

  /**
   * The endpoint one signal should be exported to, or null if neither variable configures one. A
   * signal-specific endpoint is a complete URL; the generic one is a base that the signal's own
   * path is appended to.
   */
  @VisibleForTesting
  static @Nullable String otlpEndpoint(
      @Nullable String signalEndpoint, @Nullable String genericEndpoint, String signalPath) {
    if (!Strings.isNullOrEmpty(signalEndpoint)) {
      return signalEndpoint;
    }
    if (Strings.isNullOrEmpty(genericEndpoint)) {
      return null;
    }
    return CharMatcher.is('/').trimTrailingFrom(genericEndpoint) + signalPath;
  }

  private static void shutdown(OtelHooks otelHooks) {
    otelHooks.spanProcessors().forEach(SpanProcessor::shutdown);
    otelHooks.metricReaders().forEach(MetricReader::shutdown);
    otelHooks.logRecordProcessors().forEach(LogRecordProcessor::shutdown);
  }

  private TelemetrySetup() {}
}
