/*
 * Copyright 2025 Google LLC
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
 * Integration tests for the {@link AdkWebServer} UI.
 *
 * @author <a href="http://www.vorburger.ch">Michael Vorburger.ch</a>, with Google Gemini Code
 *     Assist in Agent mode
 */
@SpringBootTest
@AutoConfigureMockMvc
public class AdkWebServerUITest {

  @Autowired private MockMvc mockMvc;

  @ParameterizedTest
  @ValueSource(strings = {"/", "/dev-ui"})
  public void devUiEntryPoints_shouldRedirectToTrailingSlashForm(String path) throws Exception {
    // index.html declares <base href="./">, which only resolves correctly from "/dev-ui/".
    mockMvc
        .perform(get(path))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/dev-ui/"));
  }

  @Test
  public void devUi_shouldBeServedAtTrailingSlashForm() throws Exception {
    mockMvc.perform(get("/dev-ui/")).andExpect(status().isOk());
  }

  @Test
  public void devUiAssets_shouldBeServedBelowDevUi() throws Exception {
    mockMvc.perform(get("/dev-ui/adk_favicon.svg")).andExpect(status().isOk());
  }

  @ParameterizedTest
  @ValueSource(strings = {"/", "/dev-ui"})
  public void devUiEntryPoints_shouldKeepQueryString(String path) throws Exception {
    // The UI picks its agent from ?app=, and the sample READMEs send users to "/dev-ui".
    mockMvc
        .perform(get(path + "?app=my-agent"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/dev-ui/?app=my-agent"));
  }

  @Test
  public void devUiAssets_shouldNotBeServedAtRoot() throws Exception {
    // Narrowing the handler from "/**" to "/dev-ui/**" makes this deliberately unreachable.
    mockMvc.perform(get("/adk_favicon.svg")).andExpect(status().isNotFound());
  }

  @Test
  public void nonExistentUiPage_shouldReturnNotFound() throws Exception {
    mockMvc.perform(get("/non-existent-page")).andExpect(status().isNotFound());
  }
}
