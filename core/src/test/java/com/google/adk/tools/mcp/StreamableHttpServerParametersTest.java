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

package com.google.adk.tools.mcp;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link StreamableHttpServerParameters}. */
@RunWith(JUnit4.class)
public final class StreamableHttpServerParametersTest {

  // -------------------------------------------------------------------------
  // Constructor tests
  // -------------------------------------------------------------------------

  @Test
  public void constructor_withUrlOnly_createsParameters() {
    StreamableHttpServerParameters params =
        new StreamableHttpServerParameters(
            "http://localhost:8080", null, null, null, null, null);

    assertThat(params.url()).isEqualTo("http://localhost:8080");
    assertThat(params.endpoint()).isNull();
    assertThat(params.headers()).isEmpty();
    assertThat(params.timeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(params.readTimeout()).isEqualTo(Duration.ofMinutes(5));
    assertThat(params.terminateOnClose()).isTrue();
  }

  @Test
  public void constructor_withUrlAndEndpoint_createsParameters() {
    StreamableHttpServerParameters params =
        new StreamableHttpServerParameters(
            "http://localhost:8080", "/mcp/stream", null, null, null, null);

    assertThat(params.url()).isEqualTo("http://localhost:8080");
    // endpoint() returns exactly what was passed — the transport builder will use it
    assertThat(params.endpoint()).isEqualTo("/mcp/stream");
  }

  @Test
  public void constructor_withEmptyUrl_throwsIllegalArgumentException() {
    // Assert.hasText() in the constructor rejects blank/empty strings
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StreamableHttpServerParameters("", null, null, null, null, null));
  }

  @Test
  public void constructor_withNullUrl_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new StreamableHttpServerParameters(null, null, null, null, null, null));
  }

  @Test
  public void constructor_withNullTimeouts_usesDefaults() {
    StreamableHttpServerParameters params =
        new StreamableHttpServerParameters(
            "http://localhost:8080", null, null, /*timeout=*/ null, /*readTimeout=*/ null, null);

    assertThat(params.timeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(params.readTimeout()).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  public void constructor_withNullTerminateOnClose_defaultsToTrue() {
    StreamableHttpServerParameters params =
        new StreamableHttpServerParameters(
            "http://localhost:8080", null, null, null, null, /*terminateOnClose=*/ null);

    assertThat(params.terminateOnClose()).isTrue();
  }

  // -------------------------------------------------------------------------
  // Builder tests
  // -------------------------------------------------------------------------

  @Test
  public void builder_withUrlOnly_buildsParameters() {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080")
            .build();

    assertThat(params.url()).isEqualTo("http://localhost:8080");
    // No endpoint set → null, so DefaultMcpTransportBuilder will use library default "/mcp"
    assertThat(params.endpoint()).isNull();
  }

  @Test
  public void builder_withUrlAndEndpoint_buildsParameters() {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080")
            .endpoint("/mcp/stream")
            .build();

    assertThat(params.url()).isEqualTo("http://localhost:8080");
    assertThat(params.endpoint()).isEqualTo("/mcp/stream");
  }

  @Test
  public void builder_withDefaults_usesDefaultTimeouts() {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080")
            .build();

    assertThat(params.timeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(params.readTimeout()).isEqualTo(Duration.ofMinutes(5));
    assertThat(params.terminateOnClose()).isTrue();
  }

  @Test
  public void builder_withCustomTimeouts_buildsParameters() {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080")
            .timeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofMinutes(2))
            .build();

    assertThat(params.timeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(params.readTimeout()).isEqualTo(Duration.ofMinutes(2));
  }

  @Test
  public void builder_withHeaders_buildsParameters() {
    Map<String, String> headers = Map.of("Authorization", "Bearer token123");

    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080")
            .headers(headers)
            .build();

    assertThat(params.headers()).containsEntry("Authorization", "Bearer token123");
  }

  @Test
  public void builder_withTerminateOnCloseFalse_buildsParameters() {
    StreamableHttpServerParameters params =
        StreamableHttpServerParameters.builder()
            .url("http://localhost:8080")
            .terminateOnClose(false)
            .build();

    assertThat(params.terminateOnClose()).isFalse();
  }
}
