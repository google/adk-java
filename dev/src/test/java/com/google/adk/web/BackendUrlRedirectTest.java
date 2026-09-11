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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With {@code adk.web.backend-url} set, the entry redirect carries the gateway's path prefix on its
 * own, so a deployment behind a path-stripping proxy needs nothing forwarded from the proxy. The
 * property governs the path only: host and scheme still come from forwarded headers wherever the
 * operator has enabled {@code server.forward-headers-strategy}.
 */
public class BackendUrlRedirectTest {

  @Nested
  @SpringBootTest(
      properties = {
        "adk.web.backend-url=https://gw.example.com/my-app",
        // Without this Spring discards the forwarded header, and the test below could not fail if
        // the redirect stopped ignoring it.
        "server.forward-headers-strategy=framework"
      })
  @AutoConfigureMockMvc
  class ConfiguredWithTheFilter {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/dev-ui"})
    public void devUiEntryPoints_shouldCarryTheConfiguredPrefix(
        String path, @Autowired MockMvc mockMvc) throws Exception {
      // No forwarded headers: the configured value is the only thing that knows the prefix.
      mockMvc
          .perform(get(path))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/my-app/dev-ui/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/dev-ui"})
    public void devUiEntryPoints_shouldNotStackAForwardedPrefix(
        String path, @Autowired MockMvc mockMvc) throws Exception {
      // The filter makes this a context path; the configured prefix replaces it.
      mockMvc
          .perform(get(path).header("X-Forwarded-Prefix", "/evil"))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("http://localhost/my-app/dev-ui/"));
    }

    @Test
    public void devUiEntryPoint_forwardedHostAndProto_stillSetTheRedirectsOrigin(
        @Autowired MockMvc mockMvc) throws Exception {
      // The configured value supplies the path; host and scheme still come from the headers.
      mockMvc
          .perform(
              get("/")
                  .header("X-Forwarded-Host", "gw.example.com")
                  .header("X-Forwarded-Proto", "https"))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("https://gw.example.com/my-app/dev-ui/"));
    }
  }

  @Nested
  @SpringBootTest(properties = "adk.web.backend-url=https://gw.example.com/my-app")
  @AutoConfigureMockMvc
  class ConfiguredWithoutTheFilter {

    @Test
    public void devUiEntryPoint_forwardedHeaders_reachNothingUnderTheShippedStrategy(
        @Autowired MockMvc mockMvc) throws Exception {
      // No strategy set, which is what ships: the property supplies the path, these reach nothing.
      mockMvc
          .perform(
              get("/")
                  .header("X-Forwarded-Prefix", "/evil")
                  .header("X-Forwarded-Host", "evil.example.com")
                  .header("Forwarded", "host=evil.example.com;proto=https"))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/my-app/dev-ui/"));
    }
  }

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  class UnconfiguredWithoutTheFilter {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/dev-ui"})
    public void devUiEntryPoints_shouldRedirectWithoutAPrefix(
        String path, @Autowired MockMvc mockMvc) throws Exception {
      mockMvc
          .perform(get(path))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/dev-ui/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/dev-ui"})
    public void devUiEntryPoints_withForwardedHeaders_shouldStillRedirectWithoutAPrefix(
        String path, @Autowired MockMvc mockMvc) throws Exception {
      // The shipped default: no forwarded-header strategy is set, so nothing in the request can
      // reach the redirect.
      mockMvc
          .perform(
              get(path)
                  .header("X-Forwarded-Prefix", "/evil")
                  .header("X-Forwarded-Host", "evil.example.com")
                  .header("Forwarded", "host=evil.example.com;proto=https"))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/dev-ui/"));
    }
  }
}
