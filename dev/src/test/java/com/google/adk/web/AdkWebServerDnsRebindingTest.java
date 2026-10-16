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

import static org.springframework.http.HttpHeaders.HOST;
import static org.springframework.http.HttpHeaders.ORIGIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Tests for the Host and Origin checks against DNS rebinding. MockMvc requests arrive on 127.0.0.1,
 * as a rebound page's requests do.
 */
final class AdkWebServerDnsRebindingTest {

  private static final String REBOUND_HOST = "evil.example:8000";
  private static final String REBOUND_ORIGIN = "http://evil.example:8000";

  @Nested
  @SpringBootTest(properties = "server.address=127.0.0.1")
  @AutoConfigureMockMvc
  class LoopbackBind {

    @ParameterizedTest
    @ValueSource(strings = {"localhost:8000", "127.0.0.1:8000", "[::1]:8000"})
    public void restApi_fromLoopbackHost_isAllowed(String host, @Autowired MockMvc mockMvc)
        throws Exception {
      mockMvc.perform(get("/list-apps").header(HOST, host)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          REBOUND_HOST,
          "127.0.0.1.evil.example",
          "127.0.0.1:8080.evil.example",
          "localhost:8000, evil.example"
        })
    public void restApi_fromOtherHost_isRejected(String host, @Autowired MockMvc mockMvc)
        throws Exception {
      // A rebound page's same-origin GET carries no Origin, so only its Host shows the rebinding.
      mockMvc.perform(get("/list-apps").header(HOST, host)).andExpect(status().isForbidden());
    }

    @Test
    public void restApi_toOtherServerNameWithoutHostHeader_isRejected(@Autowired MockMvc mockMvc)
        throws Exception {
      // HTTP/2 carries the authority as the server name rather than a Host header.
      mockMvc
          .perform(get("http://" + REBOUND_HOST + "/list-apps"))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @SpringBootTest(
      properties = {"server.address=127.0.0.1", "adk.web.cors.origins=https://ui.example.com"})
  @AutoConfigureMockMvc
  class LoopbackBindWithConfiguredOrigin {

    @Test
    public void restApi_throughProxyForConfiguredOrigin_isAllowed(@Autowired MockMvc mockMvc)
        throws Exception {
      // A listed origin's host is allowed as Host, which a same-machine proxy sends.
      mockMvc
          .perform(
              get("/list-apps")
                  .header(HOST, "ui.example.com")
                  .header(ORIGIN, "https://ui.example.com"))
          .andExpect(status().isOk());
    }
  }

  @Nested
  @SpringBootTest(properties = {"server.address=127.0.0.1", "adk.web.cors.origins=*"})
  @AutoConfigureMockMvc
  class AllOriginsOptIn {

    @Test
    public void restApi_fromReboundHost_isAllowed(@Autowired MockMvc mockMvc) throws Exception {
      mockMvc.perform(get("/list-apps").header(HOST, REBOUND_HOST)).andExpect(status().isOk());
    }
  }

  @Nested
  @SpringBootTest
  @AutoConfigureMockMvc
  class UndeclaredBind {

    @Test
    public void restApi_fromOtherHostWithoutOrigin_isAllowed(@Autowired MockMvc mockMvc)
        throws Exception {
      // Without a loopback bind, the server may be reachable under any name.
      mockMvc.perform(get("/list-apps").header(HOST, REBOUND_HOST)).andExpect(status().isOk());
    }

    @Test
    public void restApi_reboundOriginOverLoopback_isRejected(@Autowired MockMvc mockMvc)
        throws Exception {
      mockMvc
          .perform(get("/list-apps").header(HOST, REBOUND_HOST).header(ORIGIN, REBOUND_ORIGIN))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  @SpringBootTest(properties = "server.forward-headers-strategy=framework")
  @AutoConfigureMockMvc
  class ForwardedHeaderFilterEnabled {

    @Test
    public void restApi_reboundOriginClaimingNetworkAddress_isRejected(@Autowired MockMvc mockMvc)
        throws Exception {
      // Spring turns Forwarded: by= into the local address, and a same-origin page can send it.
      mockMvc
          .perform(
              get("/list-apps")
                  .header(HOST, REBOUND_HOST)
                  .header(ORIGIN, REBOUND_ORIGIN)
                  .header("Forwarded", "by=10.0.0.5"))
          .andExpect(status().isForbidden());
    }
  }
}
