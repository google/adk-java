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
 * Spring's own forwarded-header support keeps working: with {@code
 * server.forward-headers-strategy=framework} the operator opts in, {@code ForwardedHeaderFilter}
 * turns the forwarded prefix into the request's context path, and the redirect picks it up. This
 * server adds nothing here and reads no header itself; the test exists so enabling that Spring
 * feature keeps behaving as it did.
 *
 * <p>The filter honours the standard {@code Forwarded} header as well as {@code X-Forwarded-*}, so
 * both are covered.
 */
@SpringBootTest(properties = "server.forward-headers-strategy=framework")
@AutoConfigureMockMvc
public class AdkWebServerProxyRedirectTest {

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
