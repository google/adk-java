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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.web.config.BackendUrl;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
    return new RuntimeConfigController(
        loader, new ObjectMapper(), null, BackendUrl.from(backendUrl));
  }
}
