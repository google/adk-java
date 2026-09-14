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

package com.google.adk.web.controller;

import static com.google.common.truth.Truth.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * The served runtime config merges into the bundled document rather than replacing it, so keys the
 * dev UI bundle gains later are not silently dropped. {@code adk.web.backend-url} overrides one
 * key; an unset value leaves the bundled document exactly as it was served before.
 */
public class RuntimeConfigControllerMergeTest {

  private static final String CONFIGURED = "https://gw.example.com/my-app";

  @Test
  public void runtimeConfig_shouldPreserveOtherBundledKeys() {
    String bundled = "{\"backendUrl\":\"\",\"telemetry\":null,\"logo\":{\"text\":\"x\"}}";
    RuntimeConfigController controller = controllerFor(bundled, CONFIGURED);

    Map<String, Object> config = controller.runtimeConfig().getBody();

    assertThat(config).containsEntry("backendUrl", CONFIGURED);
    assertThat(config).containsKey("telemetry");
    assertThat(config).containsEntry("logo", Map.of("text", "x"));
  }

  @Test
  public void runtimeConfig_shouldOverrideTheBundledBackendUrl() {
    RuntimeConfigController controller =
        controllerFor("{\"backendUrl\":\"http://stale\"}", CONFIGURED);

    assertThat(controller.runtimeConfig().getBody()).containsEntry("backendUrl", CONFIGURED);
  }

  @Test
  public void runtimeConfig_propertyUnset_shouldKeepTheBundledBackendUrl() {
    // The static handler served this file verbatim, so a hand-set value survived; it still does.
    RuntimeConfigController controller =
        controllerFor("{\"backendUrl\":\"http://elsewhere:9000\"}");

    assertThat(controller.runtimeConfig().getBody())
        .containsExactly("backendUrl", "http://elsewhere:9000");
  }

  @Test
  public void runtimeConfig_propertyUnsetAndNoBundledKey_shouldStillReportBackendUrl() {
    RuntimeConfigController controller = controllerFor("{\"telemetry\":null}");

    assertThat(controller.runtimeConfig().getBody()).containsEntry("backendUrl", "");
  }

  @Test
  public void runtimeConfig_bundledFileMissing_shouldStillServe() {
    RuntimeConfigController controller = controllerFor(null, CONFIGURED);

    assertThat(controller.runtimeConfig().getBody()).containsExactly("backendUrl", CONFIGURED);
  }

  @Test
  public void runtimeConfig_bundledFileMalformed_shouldStillServe() {
    RuntimeConfigController controller = controllerFor("not json at all", CONFIGURED);

    assertThat(controller.runtimeConfig().getBody()).containsExactly("backendUrl", CONFIGURED);
  }

  @Test
  public void runtimeConfig_bundledFileNotAnObject_shouldStillServe() {
    // A config that is not a JSON object is ignored rather than failing the request.
    RuntimeConfigController controller = controllerFor("[1, 2, 3]", CONFIGURED);

    assertThat(controller.runtimeConfig().getBody()).containsExactly("backendUrl", CONFIGURED);
  }

  @Test
  public void runtimeConfig_trailingSlash_shouldBeStripped() {
    // The UI appends paths starting with a slash, so a trailing one would yield //run_live.
    RuntimeConfigController controller = controllerFor("{}", CONFIGURED + "/");

    assertThat(controller.runtimeConfig().getBody()).containsEntry("backendUrl", CONFIGURED);
  }

  @Test
  public void runtimeConfig_barePath_shouldStillBeServed() {
    // Not absolute, so it is warned about, but an explicit value is never silently discarded.
    RuntimeConfigController controller = controllerFor("{}", "/my-app");

    assertThat(controller.runtimeConfig().getBody()).containsEntry("backendUrl", "/my-app");
  }

  @Test
  public void runtimeConfig_upperCaseScheme_shouldStillBeServed() {
    // The UI strips the scheme case-sensitively, so this is a value the warning must catch.
    RuntimeConfigController controller = controllerFor("{}", "HTTPS://gw.example.com/my-app");

    assertThat(controller.runtimeConfig().getBody())
        .containsEntry("backendUrl", "HTTPS://gw.example.com/my-app");
  }

  @Test
  public void runtimeConfig_schemeOnly_shouldNotBeMangledByTheSlashTrim() {
    // Trimming slashes before validating would turn this into "http:".
    RuntimeConfigController controller = controllerFor("{}", "http://");

    assertThat(controller.runtimeConfig().getBody()).containsEntry("backendUrl", "http://");
  }

  @Test
  public void runtimeConfig_slashOnly_shouldNotSilentlyFallBackToTheBundledValue() {
    // Trimming first would empty this and quietly revert to the bundled value.
    RuntimeConfigController controller = controllerFor("{\"backendUrl\":\"http://bundled\"}", "/");

    assertThat(controller.runtimeConfig().getBody()).containsEntry("backendUrl", "/");
  }

  @Test
  public void construction_nonAbsoluteBackendUrl_shouldWarnNamingTheValue() {
    assertThat(warningsFor("/my-app")).hasSize(1);
    assertThat(warningsFor("/my-app").get(0)).contains("/my-app");
    // The UI strips the scheme case-sensitively, so an upper-case one must warn too.
    assertThat(warningsFor("HTTPS://gw.example.com/my-app")).hasSize(1);
  }

  @Test
  public void construction_absoluteBackendUrl_shouldNotWarn() {
    assertThat(warningsFor(CONFIGURED)).isEmpty();
    assertThat(warningsFor(CONFIGURED + "/")).isEmpty();
    assertThat(warningsFor("")).isEmpty();
  }

  /** The WARN messages logged while constructing a controller with {@code backendUrl}. */
  private static List<String> warningsFor(String backendUrl) {
    Logger logger = (Logger) LoggerFactory.getLogger(RuntimeConfigController.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      RuntimeConfigController unused = controllerFor("{}", backendUrl);
    } finally {
      logger.detachAppender(appender);
    }
    return appender.list.stream()
        .filter(event -> event.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  /** A controller with {@code adk.web.backend-url} unset. */
  private static RuntimeConfigController controllerFor(String body) {
    return controllerFor(body, "");
  }

  /** A controller whose bundled config is {@code body}, or absent when {@code body} is null. */
  private static RuntimeConfigController controllerFor(String body, String backendUrl) {
    ResourceLoader loader =
        new ResourceLoader() {
          @Override
          public Resource getResource(String location) {
            return body == null
                ? new DescriptiveResource("absent")
                : new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8));
          }

          @Override
          public ClassLoader getClassLoader() {
            return RuntimeConfigControllerMergeTest.class.getClassLoader();
          }
        };
    return new RuntimeConfigController(loader, new ObjectMapper(), null, backendUrl);
  }
}
