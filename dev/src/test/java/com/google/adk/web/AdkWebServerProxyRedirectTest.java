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

package com.google.adk.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that when {@code adk.web.backend-url} is unset and {@code
 * server.forward-headers-strategy=framework} is enabled, Spring's {@code ForwardedHeaderFilter}
 * applies {@code Forwarded} and {@code X-Forwarded-*} headers to the entry redirects. When {@code
 * adk.web.backend-url} is set, its path prefix takes precedence over {@code X-Forwarded-Prefix}.
 */
@SpringBootTest(properties = "server.forward-headers-strategy=framework")
@AutoConfigureMockMvc
final class AdkWebServerProxyRedirectTest {

  @Autowired private MockMvc mockMvc;

  @ParameterizedTest
  @ValueSource(strings = {"/", "/dev-ui"})
  public void devUiEntryPoints_withSpringForwardedHeaderSupport_shouldKeepThePrefix(String path)
      throws Exception {
    mockMvc
        .perform(
            get(path)
                .header("X-Forwarded-Prefix", "/my-app")
                .header("X-Forwarded-Host", "gw.example.com")
                .header("X-Forwarded-Proto", "https"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("https://gw.example.com/my-app/dev-ui/"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/", "/dev-ui"})
  public void devUiEntryPoints_withTheStandardForwardedHeader_shouldUseItsHost(String path)
      throws Exception {
    // RFC 7239 defines no prefix parameter, so this one moves the host and scheme but not the path.
    mockMvc
        .perform(get(path).header("Forwarded", "host=gw.example.com;proto=https"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("https://gw.example.com/dev-ui/"));
  }

  @Test
  public void devUiEntryPoint_withNoForwardedHeaders_shouldNotGainAPrefix() throws Exception {
    // Control arm: the filter is installed here, so this pins that it leaves this case alone.
    mockMvc
        .perform(get("/"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/dev-ui/"));
  }
}
