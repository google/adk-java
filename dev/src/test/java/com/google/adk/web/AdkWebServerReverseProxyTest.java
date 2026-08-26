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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies that the root ("/") -> "dev-ui" redirect (see {@link AdkWebServer#addViewControllers})
 * is reverse-proxy-prefix-aware once Spring's ForwardedHeaderFilter is enabled via {@code
 * server.forward-headers-strategy=framework}.
 *
 * <p>This is a regression test for the redirect target being context-relative ("dev-ui") rather
 * than root-relative ("/dev-ui"): only a context-relative sendRedirect() target is passed through
 * {@link org.springframework.util.StringUtils#applyRelativePath}, which is what makes {@code
 * ForwardedHeaderFilter} splice an {@code X-Forwarded-Prefix} header's value back into the redirect
 * Location. A root-relative target bypasses that codepath entirely and would leave this test's
 * "with X-Forwarded-Prefix" case failing.
 *
 * <p>Uses a dedicated test class (rather than adding to {@link AdkWebServerUITest}) because {@code
 * server.forward-headers-strategy} is a server-wide property, kept separate here so enabling it
 * doesn't affect the default context {@link AdkWebServerUITest} runs against.
 */
@SpringBootTest(properties = "server.forward-headers-strategy=framework")
@AutoConfigureMockMvc
public class AdkWebServerReverseProxyTest {

  @Autowired private MockMvc mockMvc;

  /**
   * With no Forwarded/X-Forwarded-* headers on the request, Spring's ForwardedHeaderFilter doesn't
   * wrap the response at all (verified by running this test), so behavior matches the property
   * being off entirely: the redirect is the raw, unresolved "dev-ui" string.
   */
  @Test
  public void rootRedirect_withoutForwardedHeaders_isUnchanged() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("dev-ui"));
  }

  @Test
  public void rootRedirect_withForwardedPrefixHeader_restoresPathPrefix() throws Exception {
    mockMvc
        .perform(get("/").header("X-Forwarded-Prefix", "/my-app-prefix"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrlPattern("http://*/my-app-prefix/dev-ui"));
  }
}
