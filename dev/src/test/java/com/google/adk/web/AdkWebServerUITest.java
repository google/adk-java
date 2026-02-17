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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

  @Test
  public void rootShouldRedirectToDevUi() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/dev-ui"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/dev-ui", "/dev-ui/"})
  public void devUiEndpointsShouldReturnOk(String path) throws Exception {
    mockMvc.perform(get(path)).andExpect(status().isOk());
  }

  @Test
  public void nonExistentUiPageShouldReturnNotFound() throws Exception {
    mockMvc.perform(get("/non-existent-page")).andExpect(status().isNotFound());
  }
}
