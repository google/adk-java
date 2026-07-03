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

package com.google.adk.web.config;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class AdkWebCorsConfigTest {

  private final AdkWebCorsConfig config = new AdkWebCorsConfig();

  @Test
  void defaultCorsConfigurationDoesNotAllowArbitraryOrigins() {
    CorsConfiguration corsConfiguration =
        getCorsConfiguration(new AdkWebCorsProperties(null, null, null, null, false, 0));

    assertThat(corsConfiguration.checkOrigin("https://attacker.example")).isNull();
  }

  @Test
  void explicitCorsOriginsAreStillAllowed() {
    CorsConfiguration corsConfiguration =
        getCorsConfiguration(
            new AdkWebCorsProperties(null, List.of("http://localhost:3000"), null, null, false, 0));

    assertThat(corsConfiguration.checkOrigin("http://localhost:3000"))
        .isEqualTo("http://localhost:3000");
    assertThat(corsConfiguration.checkOrigin("https://attacker.example")).isNull();
  }

  private CorsConfiguration getCorsConfiguration(AdkWebCorsProperties properties) {
    CorsConfigurationSource source = config.corsConfigurationSource(properties);
    CorsConfiguration corsConfiguration =
        source.getCorsConfiguration(new MockHttpServletRequest("POST", "/run"));
    assertThat(corsConfiguration).isNotNull();
    return corsConfiguration;
  }
}
