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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Checks the parts of the bundled Dev UI that a refresh from adk-web could drop. */
@SpringBootTest
@AutoConfigureMockMvc
class DevUiBundleTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void runtimeConfig_pinsTelemetryOff() throws Exception {
    // Unpinned, the UI asks the user to consent to usage analytics.
    mockMvc
        .perform(get("/dev-ui/assets/config/runtime-config.json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.telemetry").value(false));
  }

  @Test
  void prismThemes_areServed() throws Exception {
    // The UI loads these for code highlighting, but adk-web builds since 1.0 no longer ship them.
    mockMvc.perform(get("/dev-ui/prism-light.css")).andExpect(status().isOk());
    mockMvc.perform(get("/dev-ui/prism-dark.css")).andExpect(status().isOk());
  }

  @Test
  void nestedAsset_isServed() throws Exception {
    // A nested path proves the whole asset tree is packaged, not just the entry point.
    mockMvc.perform(get("/dev-ui/assets/audio-processor.js")).andExpect(status().isOk());
  }
}
