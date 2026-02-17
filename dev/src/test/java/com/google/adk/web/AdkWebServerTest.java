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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for the {@link AdkWebServer}.
 *
 * <p>These tests use MockMvc to simulate HTTP requests and then verify the expected responses from
 * the ADK API server.
 *
 * @author <a href="http://www.vorburger.ch">Michael Vorburger.ch</a>, with Google Gemini Code
 *     Assist in Agent mode
 */
@SpringBootTest
@AutoConfigureMockMvc
public class AdkWebServerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  public void listApps_shouldReturnOkAndEmptyList() throws Exception {
    mockMvc.perform(get("/list-apps")).andExpect(status().isOk()).andExpect(content().json("[]"));
  }

  @Test
  public void createSession_shouldReturnCreated() throws Exception {
    var result =
        mockMvc
            .perform(
                post("/apps/test-app/users/test-user/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.appName", Matchers.is("test-app")))
            .andExpect(jsonPath("$.userId", Matchers.is("test-user")))
            .andReturn();

    var responseBody = result.getResponse().getContentAsString();
    var sessionId = com.jayway.jsonpath.JsonPath.read(responseBody, "$.id");
    mockMvc.perform(delete("/apps/test-app/users/test-user/sessions/" + sessionId));
  }

  @Test
  public void createSessionWithId_shouldReturnCreated() throws Exception {
    try {
      mockMvc
          .perform(
              post("/apps/test-app/users/test-user/sessions/test-session")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.appName", Matchers.is("test-app")))
          .andExpect(jsonPath("$.userId", Matchers.is("test-user")))
          .andExpect(jsonPath("$.id", Matchers.is("test-session")));
    } finally {
      mockMvc.perform(delete("/apps/test-app/users/test-user/sessions/test-session"));
    }
  }

  @Test
  public void deleteSession_shouldReturnNoContent() throws Exception {
    mockMvc.perform(
        post("/apps/test-app/users/test-user/sessions/test-session-to-delete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));

    mockMvc
        .perform(delete("/apps/test-app/users/test-user/sessions/test-session-to-delete"))
        .andExpect(status().isNoContent());
  }

  @Test
  public void getSession_shouldReturnOk() throws Exception {
    mockMvc.perform(
        post("/apps/test-app/users/test-user/sessions/test-session")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));

    try {
      mockMvc
          .perform(get("/apps/test-app/users/test-user/sessions/test-session"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.appName", Matchers.is("test-app")))
          .andExpect(jsonPath("$.userId", Matchers.is("test-user")))
          .andExpect(jsonPath("$.id", Matchers.is("test-session")));
    } finally {
      mockMvc.perform(delete("/apps/test-app/users/test-user/sessions/test-session"));
    }
  }

  @Test
  public void listSessions_shouldReturnOk() throws Exception {
    mockMvc.perform(
        post("/apps/test-app/users/test-user/sessions/test-session-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));
    mockMvc.perform(
        post("/apps/test-app/users/test-user/sessions/test-session-2")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"));

    mockMvc
        .perform(get("/apps/test-app/users/test-user/sessions"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$[?(@.id == 'test-session-1' || @.id == 'test-session-2')].id",
                Matchers.containsInAnyOrder("test-session-1", "test-session-2")));

    mockMvc.perform(delete("/apps/test-app/users/test-user/sessions/test-session-1"));
    mockMvc.perform(delete("/apps/test-app/users/test-user/sessions/test-session-2"));
  }
}
