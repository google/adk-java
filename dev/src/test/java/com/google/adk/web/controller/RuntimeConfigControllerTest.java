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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The dev UI learns where its backend lives from {@code adk.web.backend-url}, so its API calls
 * reach a server published under a path prefix. Nothing is read from the request, so a client
 * cannot influence the value it is served.
 */
public class RuntimeConfigControllerTest {

  private static final String CONFIG = "/dev-ui/assets/config/runtime-config.json";

  @Nested
  @SpringBootTest(
      properties = {
        "adk.web.backend-url=https://gw.example.com/my-app",
        // Without this Spring ignores the forwarded headers anyway, so the tests below could
        // not fail if the value went back to being derived from the request.
        "server.forward-headers-strategy=framework"
      })
  @AutoConfigureMockMvc
  class Configured {

    @Test
    public void runtimeConfig_shouldReportTheConfiguredBackendUrl(@Autowired MockMvc mockMvc)
        throws Exception {
      mockMvc
          .perform(get(CONFIG))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.backendUrl").value("https://gw.example.com/my-app"))
          .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    public void runtimeConfig_forwardedHeaders_shouldNotChangeTheValue(@Autowired MockMvc mockMvc)
        throws Exception {
      // Configuration alone decides this; a client naming its own host cannot move it.
      mockMvc
          .perform(
              get(CONFIG)
                  .header("X-Forwarded-Prefix", "/evil")
                  .header("X-Forwarded-Host", "evil.example.com")
                  .header("X-Forwarded-Proto", "https"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.backendUrl").value("https://gw.example.com/my-app"));
    }
  }

  @Nested
  @SpringBootTest(properties = "server.forward-headers-strategy=framework")
  @AutoConfigureMockMvc
  class Unconfigured {

    @Test
    public void runtimeConfig_shouldServeWhatTheBundledFileSaid(@Autowired MockMvc mockMvc)
        throws Exception {
      // Unset is the default, so an unproxied deployment sees exactly what it saw before.
      mockMvc
          .perform(get(CONFIG))
          .andExpect(status().isOk())
          // Byte-exact: the bundled file is pretty-printed, so this passes only if the
          // controller (which serializes compactly) answered rather than the static handler.
          .andExpect(content().string("{\"backendUrl\":\"\"}"));
    }

    @Test
    public void runtimeConfig_forwardedHeaders_shouldStillReportEmpty(@Autowired MockMvc mockMvc)
        throws Exception {
      mockMvc
          .perform(
              get(CONFIG)
                  .header("X-Forwarded-Prefix", "/evil")
                  .header("X-Forwarded-Host", "evil.example.com"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.backendUrl").value(""));
    }
  }
}
