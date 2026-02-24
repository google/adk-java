/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.sessions;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.HttpOptions;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;

/** Interface for an API client which issues HTTP requests to the GenAI APIs. */
abstract class ApiClient {
  OkHttpClient httpClient;
  // For Google AI APIs
  final Optional<String> apiKey;
  // For Vertex AI APIs
  final Optional<String> project;
  final Optional<String> location;
  final Optional<GoogleCredentials> credentials;
  HttpOptions httpOptions;
  final boolean vertexAI;

  /** Constructs an ApiClient for Google AI APIs. */
  ApiClient(Optional<String> apiKey, Optional<HttpOptions> customHttpOptions) {
    checkNotNull(apiKey, "API Key cannot be null");
    checkNotNull(customHttpOptions, "customHttpOptions cannot be null");

    try {
      this.apiKey = Optional.of(apiKey.orElseGet(() -> System.getenv("GOOGLE_API_KEY")));
    } catch (NullPointerException e) {
      throw new IllegalArgumentException(
          "API key must either be provided or set in the environment variable" + " GOOGLE_API_KEY.",
          e);
    }

    this.project = Optional.empty();
    this.location = Optional.empty();
    this.credentials = Optional.empty();
    this.vertexAI = false;

    this.httpOptions = defaultHttpOptions(/* vertexAI= */ false, this.location);

    if (customHttpOptions.isPresent()) {
      applyHttpOptions(customHttpOptions.get());
    }

    this.httpClient = createHttpClient(httpOptions.timeout());
  }

  ApiClient(
      Optional<String> project,
      Optional<String> location,
      Optional<GoogleCredentials> credentials,
      Optional<HttpOptions> customHttpOptions) {
    checkNotNull(project, "project cannot be null");
    checkNotNull(location, "location cannot be null");
    checkNotNull(credentials, "credentials cannot be null");
    checkNotNull(customHttpOptions, "customHttpOptions cannot be null");

    try {
      this.project = Optional.of(project.orElseGet(() -> System.getenv("GOOGLE_CLOUD_PROJECT")));
    } catch (NullPointerException e) {
      throw new IllegalArgumentException(
          "Project must either be provided or set in the environment variable"
              + " GOOGLE_CLOUD_PROJECT.",
          e);
    }
    if (this.project.get().isEmpty()) {
      throw new IllegalArgumentException("Project must not be empty.");
    }

    try {
      this.location = Optional.of(location.orElse(System.getenv("GOOGLE_CLOUD_LOCATION")));
    } catch (NullPointerException e) {
      throw new IllegalArgumentException(
          "Location must either be provided or set in the environment variable"
              + " GOOGLE_CLOUD_LOCATION.",
          e);
    }
    if (this.location.get().isEmpty()) {
      throw new IllegalArgumentException("Location must not be empty.");
    }

    this.credentials = Optional.of(credentials.orElseGet(this::defaultCredentials));

    this.httpOptions = defaultHttpOptions(/* vertexAI= */ true, this.location);

    if (customHttpOptions.isPresent()) {
      applyHttpOptions(customHttpOptions.get());
    }
    this.apiKey = Optional.empty();
    this.vertexAI = true;
    this.httpClient = createHttpClient(httpOptions.timeout());
  }

  private OkHttpClient createHttpClient(Optional<Integer> timeout) {
    OkHttpClient.Builder builder = new OkHttpClient().newBuilder();
    if (timeout.isPresent()) {
      builder.connectTimeout(Duration.ofMillis(timeout.get()));
    }
    return builder.build();
  }

  /** Sends a Http request given the http method, path, and request json string. */
  public abstract ApiResponse request(String httpMethod, String path, String requestJson);

  /** Returns the library version. */
  static String libraryVersion() {
    // TODO: Automate revisions to the SDK library version.
    String libraryLabel = "google-genai-sdk/0.1.0";
    String languageLabel = "gl-java/" + JAVA_VERSION.value();
    return libraryLabel + " " + languageLabel;
  }

  /** Returns whether the client is using Vertex AI APIs. */
  public boolean vertexAI() {
    return vertexAI;
  }

  /** Returns the project ID for Vertex AI APIs. */
  public @Nullable String project() {
    return project.orElse(null);
  }

  /** Returns the location for Vertex AI APIs. */
  public @Nullable String location() {
    return location.orElse(null);
  }

  /** Returns the API key for Google AI APIs. */
  public @Nullable String apiKey() {
    return apiKey.orElse(null);
  }

  /** Returns the HttpClient for API calls. */
  OkHttpClient httpClient() {
    return httpClient;
  }

  private Optional<Map<String, String>> getTimeoutHeader(HttpOptions httpOptionsToApply) {
    if (httpOptionsToApply.timeout().isPresent()) {
      int timeoutInSeconds = (int) Math.ceil((double) httpOptionsToApply.timeout().get() / 1000.0);
      // TODO(b/329147724): Document the usage of X-Server-Timeout header.
      return Optional.of(ImmutableMap.of("X-Server-Timeout", Integer.toString(timeoutInSeconds)));
    }
    return Optional.empty();
  }

  private void applyHttpOptions(HttpOptions httpOptionsToApply) {
    HttpOptions.Builder mergedHttpOptionsBuilder = this.httpOptions.toBuilder();
    if (httpOptionsToApply.baseUrl().isPresent()) {
      mergedHttpOptionsBuilder.baseUrl(httpOptionsToApply.baseUrl().get());
    }
    if (httpOptionsToApply.apiVersion().isPresent()) {
      mergedHttpOptionsBuilder.apiVersion(httpOptionsToApply.apiVersion().get());
    }
    if (httpOptionsToApply.timeout().isPresent()) {
      mergedHttpOptionsBuilder.timeout(httpOptionsToApply.timeout().get());
    }
    if (httpOptionsToApply.headers().isPresent()) {
      ImmutableMap<String, String> mergedHeaders =
          ImmutableMap.<String, String>builder()
              .putAll(httpOptionsToApply.headers().orElse(ImmutableMap.of()))
              .putAll(this.httpOptions.headers().orElse(ImmutableMap.of()))
              .putAll(getTimeoutHeader(httpOptionsToApply).orElse(ImmutableMap.of()))
              .buildOrThrow();
      mergedHttpOptionsBuilder.headers(mergedHeaders);
    }
    this.httpOptions = mergedHttpOptionsBuilder.build();
  }

  static HttpOptions defaultHttpOptions(boolean vertexAI, Optional<String> location) {
    ImmutableMap.Builder<String, String> defaultHeaders = ImmutableMap.builder();
    defaultHeaders
        .put("Content-Type", "application/json")
        .put("user-agent", libraryVersion())
        .put("x-goog-api-client", libraryVersion());

    HttpOptions.Builder defaultHttpOptionsBuilder =
        HttpOptions.builder().headers(defaultHeaders.buildOrThrow());

    if (vertexAI && location.isPresent()) {
      defaultHttpOptionsBuilder
          .baseUrl(
              Ascii.equalsIgnoreCase(location.get(), "global")
                  ? "https://aiplatform.googleapis.com"
                  : String.format("https://%s-aiplatform.googleapis.com", location.get()))
          .apiVersion("v1beta1");
    } else if (vertexAI && location.isEmpty()) {
      throw new IllegalArgumentException("Location must be provided for Vertex AI APIs.");
    } else {
      defaultHttpOptionsBuilder
          .baseUrl("https://generativelanguage.googleapis.com")
          .apiVersion("v1beta");
    }
    return defaultHttpOptionsBuilder.build();
  }

  GoogleCredentials defaultCredentials() {
    try {
      return GoogleCredentials.getApplicationDefault()
          .createScoped("https://www.googleapis.com/auth/cloud-platform");
    } catch (IOException e) {
      throw new GenAiIOException(
          "Failed to get application default credentials, please explicitly provide credentials.",
          e);
    }
  }
}
