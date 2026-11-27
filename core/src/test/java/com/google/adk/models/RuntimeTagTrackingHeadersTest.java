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

package com.google.adk.models;

import static com.google.adk.models.LocalModelServer.CHAT_COMPLETION_JSON;
import static com.google.adk.models.LocalModelServer.GENERATE_CONTENT_JSON;
import static com.google.adk.models.LocalModelServer.headersSentToLocalServer;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeNotNull;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.sun.net.httpserver.Headers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests the hosting runtime tag on the tracking headers that models send. Runs only with {@code
 * K_SERVICE} set, as on Cloud Run, which the build sets for this test.
 */
@RunWith(JUnit4.class)
public final class RuntimeTagTrackingHeadersTest {

  private static final String CLOUD_RUN_ADK_TAG = TrackingHeaders.ADK_TAG + "+cloud_run";

  @Before
  public void setUp() {
    assumeNotNull(System.getenv("K_SERVICE"));
  }

  @Test
  public void gemini_callerClient_sendsTheRuntimeTag() throws Exception {
    assertAdkTag(
        headersSentToLocalServer(
            url ->
                new Gemini(
                    "gemini-test", Client.builder().apiKey("test-api-key").vertexAI(false).build()),
            GENERATE_CONTENT_JSON),
        CLOUD_RUN_ADK_TAG);
  }

  @Test
  public void gemini_adkBuiltClient_sendsTheRuntimeTag() throws Exception {
    assertAdkTag(
        headersSentToLocalServer(
            url -> Gemini.builder().modelName("gemini-test").apiKey("test-api-key").build(),
            GENERATE_CONTENT_JSON),
        CLOUD_RUN_ADK_TAG);
  }

  @Test
  public void apigeeGenAi_sendsNoRuntimeTag() throws Exception {
    assertAdkTag(
        headersSentToLocalServer(
            url -> apigee("apigee/gemini/v1beta/gemini-test", url), GENERATE_CONTENT_JSON),
        TrackingHeaders.ADK_TAG);
  }

  @Test
  public void apigeeChatCompletions_sendsNoRuntimeTag() throws Exception {
    assertAdkTag(
        headersSentToLocalServer(url -> apigee("apigee/openai/gpt-4o", url), CHAT_COMPLETION_JSON),
        TrackingHeaders.ADK_TAG);
  }

  private static BaseLlm apigee(String modelName, String proxyUrl) {
    return ApigeeLlm.builder().modelName(modelName).proxyUrl(proxyUrl).build();
  }

  /** Asserts that both tracking headers carry exactly one ADK tag, {@code adkTag}. */
  private static void assertAdkTag(Headers headers, String adkTag) {
    for (String name : TrackingHeaders.HEADER_NAMES) {
      ImmutableList<String> adkTags =
          Splitter.on(' ')
              .splitToStream(headers.getFirst(name))
              .filter(tag -> tag.startsWith("google-adk/"))
              .collect(ImmutableList.toImmutableList());
      assertThat(adkTags).containsExactly(adkTag);
    }
  }
}
