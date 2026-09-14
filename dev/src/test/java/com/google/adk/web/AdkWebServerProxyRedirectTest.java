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
 * Behind a proxy that strips a path prefix, both dev UI entry points redirect to a target that
 * still carries the prefix, so the browser follows a path the gateway can still route. Requires
 * {@code server.forward-headers-strategy=framework}, which is what puts the prefix in the context
 * path for the redirect to pick up. This is the path taken when {@code adk.web.backend-url} is
 * unset; setting it supplies the prefix directly and the forwarded one is then ignored.
 */
@SpringBootTest(properties = "server.forward-headers-strategy=framework")
@AutoConfigureMockMvc
public class AdkWebServerProxyRedirectTest {

  @Autowired private MockMvc mockMvc;

  @ParameterizedTest
  @ValueSource(strings = {"/", "/dev-ui"})
  public void devUiEntryPoints_behindPrefixStrippingProxy_shouldKeepThePrefix(String path)
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

  @Test
  public void rootEntryPoint_withoutForwardedHeaders_shouldNotGainAPrefix() throws Exception {
    // The prefix comes from the header alone, so an unproxied deployment is unaffected.
    mockMvc
        .perform(get("/"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/dev-ui/"));
  }
}
