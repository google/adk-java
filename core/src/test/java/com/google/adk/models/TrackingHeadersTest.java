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

import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TrackingHeadersTest {

  private static final ImmutableList<String> RUNTIME_VARIABLES =
      ImmutableList.of(
          "GOOGLE_CLOUD_AGENT_ENGINE_ID", "K_SERVICE", "CLOUD_RUN_JOB", "KUBERNETES_SERVICE_HOST");

  @Test
  public void mergeInto_addsOnlyTheTagsACallerValueLacks() {
    ImmutableMap<String, String> merged =
        TrackingHeaders.mergeInto(
            ImmutableMap.of(
                "User-Agent", "my-app/1.0 gl-java/11",
                "X-Goog-Api-Client", "my-lib/2.0 google-adk/0.0.1",
                "X-Custom", "v"),
            /* includeRuntimeTag= */ false);

    assertThat(merged)
        .containsExactly(
            "X-Custom", "v",
            "x-goog-api-client", "my-lib/2.0 google-adk/0.0.1 gl-java/" + JAVA_VERSION.value(),
            "user-agent", "my-app/1.0 gl-java/11 " + TrackingHeaders.ADK_TAG);
  }

  @Test
  public void mergeInto_noCallerValues_addsTrackingHeaders() {
    String value = TrackingHeaders.ADK_TAG + " gl-java/" + JAVA_VERSION.value();

    assertThat(TrackingHeaders.mergeInto(ImmutableMap.of(), /* includeRuntimeTag= */ false))
        .containsExactly("x-goog-api-client", value, "user-agent", value);
  }

  @Test
  public void value_withoutRuntimeTag_isThePlainAdkTagAndJavaTag() {
    assertThat(TrackingHeaders.value(/* includeRuntimeTag= */ false))
        .isEqualTo(TrackingHeaders.ADK_TAG + " gl-java/" + JAVA_VERSION.value());
  }

  @Test
  public void genAiSdkHeaders_withoutRuntimeTag_carryOnlyThePlainAdkTag() {
    assertThat(TrackingHeaders.genAiSdkHeaders(/* includeRuntimeTag= */ false))
        .containsExactly(
            "x-goog-api-client", TrackingHeaders.ADK_TAG, "user-agent", TrackingHeaders.ADK_TAG);
  }

  @Test
  public void runtimeTag_agentEngine() {
    assertThat(runtimeTagWith("GOOGLE_CLOUD_AGENT_ENGINE_ID")).isEqualTo("remote_reasoning_engine");
  }

  @Test
  public void runtimeTag_cloudRunService() {
    assertThat(runtimeTagWith("K_SERVICE")).isEqualTo("cloud_run");
  }

  @Test
  public void runtimeTag_cloudRunJob() {
    assertThat(runtimeTagWith("CLOUD_RUN_JOB")).isEqualTo("cloud_run");
  }

  @Test
  public void runtimeTag_cloudRunServiceAndJob() {
    assertThat(runtimeTagWith("K_SERVICE", "CLOUD_RUN_JOB")).isEqualTo("cloud_run");
  }

  @Test
  public void runtimeTag_kubernetes() {
    assertThat(runtimeTagWith("KUBERNETES_SERVICE_HOST")).isEqualTo("k8s");
  }

  @Test
  public void runtimeTag_cloudRunServiceOnKubernetes_isCloudRun() {
    assertThat(runtimeTagWith("K_SERVICE", "KUBERNETES_SERVICE_HOST")).isEqualTo("cloud_run");
  }

  @Test
  public void runtimeTag_cloudRunJobOnKubernetes_isCloudRun() {
    assertThat(runtimeTagWith("CLOUD_RUN_JOB", "KUBERNETES_SERVICE_HOST")).isEqualTo("cloud_run");
  }

  @Test
  public void runtimeTag_agentEngineWinsOverEveryOtherRuntime() {
    assertThat(runtimeTagWith(RUNTIME_VARIABLES.toArray(new String[0])))
        .isEqualTo("remote_reasoning_engine");
  }

  @Test
  public void runtimeTag_noVariables_isNull() {
    assertThat(TrackingHeaders.runtimeTag(ImmutableMap.<String, String>of()::get)).isNull();
  }

  @Test
  public void runtimeTag_emptyValues_areIgnored() {
    for (String name : RUNTIME_VARIABLES) {
      assertThat(TrackingHeaders.runtimeTag(ImmutableMap.of(name, "")::get)).isNull();
    }
  }

  @Test
  public void runtimeTag_emptyAgentEngineId_fallsThroughToCloudRun() {
    assertThat(
            TrackingHeaders.runtimeTag(
                ImmutableMap.of("GOOGLE_CLOUD_AGENT_ENGINE_ID", "", "K_SERVICE", "s")::get))
        .isEqualTo("cloud_run");
  }

  @Test
  public void runtimeTag_emptyCloudRunService_fallsThroughToKubernetes() {
    assertThat(
            TrackingHeaders.runtimeTag(
                ImmutableMap.of("K_SERVICE", "", "KUBERNETES_SERVICE_HOST", "h")::get))
        .isEqualTo("k8s");
  }

  @Test
  public void isGoogleApisUrl_googleApiHosts() {
    ImmutableList<String> urls =
        ImmutableList.of(
            "https://aiplatform.googleapis.com/v1",
            "https://us-east5-aiplatform.googleapis.com",
            "https://aiplatform.mtls.googleapis.com/v1",
            "https://BigQuery.GoogleAPIs.com/v1",
            "HTTPS://bigquery.googleapis.com/v1",
            // A backslash outside the authority is fine.
            "https://bigquery.googleapis.com/v1?q=a\\b",
            "https://bigquery.googleapis.com/v1/a@b?q=c@d",
            "https://bigquery.googleapis.com?q=a@b",
            "https://bigquery.googleapis.com#a\\b");

    for (String url : urls) {
      assertWithMessage(url).that(TrackingHeaders.isGoogleApisUrl(url)).isTrue();
    }
  }

  @Test
  public void isGoogleApisUrl_otherDestinations() {
    ImmutableList<String> urls =
        ImmutableList.of(
            "http://aiplatform.googleapis.com/v1",
            "https://notgoogleapis.com/v1",
            "https://googleapis.com.evil.com/v1",
            "https://api.anthropic.com",
            // The apex is not a service endpoint.
            "https://googleapis.com/v1",
            "https://googleapis.com@evil.com/v1",
            "https://user@bigquery.googleapis.com/v1",
            "https://:secret@bigquery.googleapis.com/v1",
            "https://@bigquery.googleapis.com/v1",
            "https://evil.com\\.googleapis.com/v1",
            "https://evil.com\\@bigquery.googleapis.com/v1",
            "https://bigquery.googleapis.com\\@evil.com/v1",
            "https://bigquery.googleapis.com\\evil.com/v1",
            "https:\\\\bigquery.googleapis.com/v1",
            "https:///aiplatform.googleapis.com/v1",
            "https:///evil.com@aiplatform.googleapis.com/v1",
            "https:///aiplatform.googleapis.com\\evil.com",
            "not a url",
            "");

    for (String url : urls) {
      assertWithMessage(url).that(TrackingHeaders.isGoogleApisUrl(url)).isFalse();
    }
  }

  @Test
  public void isGoogleApisUrl_null_isFalse() {
    assertThat(TrackingHeaders.isGoogleApisUrl(null)).isFalse();
  }

  /**
   * Returns the runtime tag detected when only {@code names} are set, each to a non-empty value.
   */
  private static String runtimeTagWith(String... names) {
    ImmutableMap.Builder<String, String> env = ImmutableMap.builder();
    for (String name : names) {
      env.put(name, "set");
    }
    return TrackingHeaders.runtimeTag(env.buildOrThrow()::get);
  }
}
