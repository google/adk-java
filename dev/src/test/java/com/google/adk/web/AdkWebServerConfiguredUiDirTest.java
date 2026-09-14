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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A directory named by {@code adk.web.ui.dir} is really served under {@code /dev-ui/}. The other
 * tests cover the bundled classpath copy, and {@code DevUiAssetsTest} only checks the string this
 * normalizes to, which cannot tell whether the resource handler can resolve it.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class AdkWebServerConfiguredUiDirTest {

  @TempDir static Path uiDir;

  @DynamicPropertySource
  static void configuredUiDir(DynamicPropertyRegistry registry) {
    // @SpringBootTest(properties=...) needs a constant; the temp directory is only known now.
    registry.add("adk.web.ui.dir", () -> uiDir.toString());
  }

  @BeforeAll
  static void writeUi() throws IOException {
    Files.writeString(uiDir.resolve("index.html"), "<html>temp ui</html>");
    Files.writeString(uiDir.resolve("asset.txt"), "from the configured dir");
  }

  @Autowired private MockMvc mockMvc;

  @Test
  public void configuredUiDir_shouldBeServedBelowDevUi() throws Exception {
    // The body, not just a 200: it proves the bytes came from the configured directory.
    mockMvc
        .perform(get("/dev-ui/asset.txt"))
        .andExpect(status().isOk())
        .andExpect(content().string("from the configured dir"));
  }

  @Test
  public void configuredUiDir_shouldSupplyTheForwardTarget() throws Exception {
    mockMvc
        .perform(get("/dev-ui/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string("<html>temp ui</html>"));
  }
}
