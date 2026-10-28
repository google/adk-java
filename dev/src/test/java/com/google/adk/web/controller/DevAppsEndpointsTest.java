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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DevAppsEndpointsTest {

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private static final ImmutableList<UnimplementedCall> UNIMPLEMENTED_CALLS =
      ImmutableList.of(
          new UnimplementedCall(HttpMethod.GET, "/builder", "builder"),
          new UnimplementedCall(HttpMethod.POST, "/builder/save", "builder"),
          new UnimplementedCall(HttpMethod.POST, "/builder/cancel", "builder"),
          new UnimplementedCall(HttpMethod.POST, "/eval-sets", "not installed"),
          new UnimplementedCall(HttpMethod.GET, "/eval-sets/set", "not installed"),
          new UnimplementedCall(HttpMethod.DELETE, "/eval-sets/set", "not installed"),
          new UnimplementedCall(HttpMethod.POST, "/eval-sets/set/run", "not installed"),
          new UnimplementedCall(HttpMethod.GET, "/eval_sets", "not installed"),
          new UnimplementedCall(HttpMethod.POST, "/eval_sets/set/add_session", "not installed"),
          new UnimplementedCall(HttpMethod.GET, "/eval_sets/set/evals", "not installed"),
          new UnimplementedCall(HttpMethod.GET, "/eval_sets/set/evals/case", "not installed"),
          new UnimplementedCall(HttpMethod.PUT, "/eval_sets/set/evals/case", "not installed"),
          new UnimplementedCall(HttpMethod.DELETE, "/eval_sets/set/evals/case", "not installed"),
          new UnimplementedCall(HttpMethod.GET, "/eval_results", "not installed"),
          new UnimplementedCall(HttpMethod.GET, "/eval_results/result", "not installed"),
          new UnimplementedCall(HttpMethod.GET, "/metrics-info", "not installed"),
          new UnimplementedCall(HttpMethod.GET, "/deploy/defaults", "Deploying"),
          new UnimplementedCall(HttpMethod.POST, "/deploy/cloud_run", "Deploying"),
          new UnimplementedCall(HttpMethod.GET, "/tests", "tests"),
          new UnimplementedCall(HttpMethod.POST, "/tests/rebuild", "tests"),
          new UnimplementedCall(HttpMethod.POST, "/tests/run", "tests"),
          new UnimplementedCall(HttpMethod.GET, "/tests/case", "tests"),
          new UnimplementedCall(HttpMethod.PUT, "/tests/case", "tests"),
          new UnimplementedCall(HttpMethod.DELETE, "/tests/case", "tests"));

  @Autowired private MockMvc mockMvc;

  @Test
  void unimplementedCalls_answerNotImplementedWithADetail() throws Exception {
    for (UnimplementedCall call : UNIMPLEMENTED_CALLS) {
      MockHttpServletResponse response =
          mockMvc
              .perform(request(call.method(), "/dev/apps/app" + call.path()))
              .andReturn()
              .getResponse();

      String endpoint = call.method() + " " + call.path();
      assertWithMessage("status of %s", endpoint).that(response.getStatus()).isEqualTo(501);
      assertWithMessage("detail of %s", endpoint)
          .that(objectMapper.readTree(response.getContentAsString()).path("detail").asText())
          .contains(call.detailWord());
    }
  }

  @Test
  void traceForSession_devAppsPath_answersLikeTheOldPath() throws Exception {
    String devAppsPath = body("/dev/apps/app/debug/trace/session/session");

    assertThat(devAppsPath).isEqualTo(body("/debug/trace/session/session"));
  }

  @Test
  void traceForEvent_devAppsPathWithUnknownEvent_answersLikeTheOldPath() throws Exception {
    String devAppsPath = notFoundBody("/dev/apps/app/debug/trace/missing");

    assertThat(devAppsPath).contains("Trace not found");
    assertThat(devAppsPath).isEqualTo(notFoundBody("/debug/trace/missing"));
  }

  @Test
  void telemetryConfig_isOff() throws Exception {
    mockMvc
        .perform(get("/config/telemetry"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"telemetry\": false}"));
  }

  private String body(String path) throws Exception {
    return mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String notFoundBody(String path) throws Exception {
    return mockMvc
        .perform(get(path))
        .andExpect(status().isNotFound())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private record UnimplementedCall(HttpMethod method, String path, String detailWord) {}
}
