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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With {@code adk.web.backend-url} set, the entry redirect carries the gateway's path prefix on its
 * own, so a deployment behind a path-stripping proxy needs nothing forwarded from the proxy.
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
  class Configured {

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
      // The filter turns this into a context path, which a context-relative redirect would prepend
      // to the configured prefix. The configured value is the public base, so it wins alone.
      mockMvc
          .perform(get(path).header("X-Forwarded-Prefix", "/evil"))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("http://localhost/my-app/dev-ui/"));
    }
  }

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  class Unconfigured {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/dev-ui"})
    public void devUiEntryPoints_shouldRedirectWithoutAPrefix(
        String path, @Autowired MockMvc mockMvc) throws Exception {
      mockMvc
          .perform(get(path))
          .andExpect(status().is3xxRedirection())
          .andExpect(redirectedUrl("/dev-ui/"));
    }
  }
}
