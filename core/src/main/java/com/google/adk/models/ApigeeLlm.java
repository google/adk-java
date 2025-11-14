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
package com.google.adk.models;

import static com.google.common.base.StandardSystemProperty.JAVA_VERSION;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.adk.Version;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Flowable;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link BaseLlm} implementation for calling an Apigee proxy.
 *
 * <p>This class uses {@link Gemini} via composition and allows requests to be routed through an
 * Apigee proxy. The model string format allows for specifying the provider (Gemini or Vertex AI),
 * API version, and model ID.
 */
public class ApigeeLlm extends BaseLlm {
  private static final String GOOGLE_GENAI_USE_VERTEXAI_ENV_VARIABLE_NAME =
      "GOOGLE_GENAI_USE_VERTEXAI";
  private static final String APIGEE_PROXY_URL_ENV_VARIABLE_NAME = "APIGEE_PROXY_URL";
  private static final ImmutableMap<String, String> TRACKING_HEADERS;

  static {
    String frameworkLabel = "google-adk/" + Version.JAVA_ADK_VERSION;
    String languageLabel = "gl-java/" + JAVA_VERSION.value();
    String versionHeaderValue = String.format("%s %s", frameworkLabel, languageLabel);
    TRACKING_HEADERS =
        ImmutableMap.of(
            "x-goog-api-client", versionHeaderValue,
            "user-agent", versionHeaderValue);
  }

  private final Gemini geminiDelegate;
  private final Client apiClient;
  private final HttpOptions httpOptions;

  /**
   * Constructs a new ApigeeLlm instance.
   *
   * @param modelName The name of the Apigee model to use.
   * @param proxyUrl The URL of the Apigee proxy.
   * @param customHeaders A map of custom headers to be sent with the request.
   */
  public ApigeeLlm(String modelName, String proxyUrl, Map<String, String> customHeaders) {
    super(modelName);

    boolean isVertexAi = identifyVertexAi(modelName);
    String apiVersion = identifyApiVersion(modelName);

    String effectiveProxyUrl = proxyUrl;
    if (isNullOrEmpty(effectiveProxyUrl)) {
      effectiveProxyUrl = System.getenv(APIGEE_PROXY_URL_ENV_VARIABLE_NAME);
    }

    // Build the Client
    HttpOptions.Builder httpOptionsBuilder =
        HttpOptions.builder().baseUrl(effectiveProxyUrl).headers(TRACKING_HEADERS);
    if (!apiVersion.isEmpty()) {
      httpOptionsBuilder.apiVersion(apiVersion);
    }
    if (customHeaders != null) {
      httpOptionsBuilder.headers(
          ImmutableMap.<String, String>builder()
              .putAll(TRACKING_HEADERS)
              .putAll(customHeaders)
              .buildOrThrow());
    }
    this.httpOptions = httpOptionsBuilder.build();
    Client.Builder apiClientBuilder = Client.builder().httpOptions(this.httpOptions);
    if (isVertexAi) {
      apiClientBuilder.vertexAI(true);
    }

    apiClient = apiClientBuilder.build();
    this.geminiDelegate = new Gemini(modelName, apiClient);
  }

  /**
   * Constructs a new ApigeeLlm instance for testing purposes.
   *
   * @param modelName The name of the Apigee model to use.
   * @param geminiDelegate The Gemini delegate to use for making API calls.
   */
  ApigeeLlm(String modelName, Gemini geminiDelegate) {
    super(modelName);
    this.apiClient = null;
    this.httpOptions = null;
    this.geminiDelegate = geminiDelegate;
  }

  /**
   * Returns the genai {@link com.google.genai.Client} instance for making API calls for testing
   * purposes.
   *
   * @return the genai {@link com.google.genai.Client} instance.
   */
  Client getApiClient() {
    return this.apiClient;
  }

  /**
   * Returns the {@link HttpOptions} instance for making API calls for testing purposes.
   *
   * @return the {@link HttpOptions} instance.
   */
  HttpOptions getHttpOptions() {
    return this.httpOptions;
  }

  private static boolean identifyVertexAi(String model) {
    return !model.startsWith("apigee/gemini/")
        && (model.startsWith("apigee/vertex_ai/")
            || isEnvEnabled(GOOGLE_GENAI_USE_VERTEXAI_ENV_VARIABLE_NAME));
  }

  private static String identifyApiVersion(String model) {
    String modelPart = model.substring("apigee/".length());
    String[] components = modelPart.split("/", -1);
    if (components.length == 3) {
      return components[1];
    }
    if (components.length == 2) {
      if (!components[0].equals("vertex_ai")
          && !components[0].equals("gemini")
          && components[0].startsWith("v")) {
        return components[0];
      }
    }
    return "";
  }

  /**
   * Returns a new Builder for constructing {@link ApigeeLlm} instances.
   *
   * @return a new {@link Builder}
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ApigeeLlm}. */
  public static class Builder {
    private String modelName;
    private String proxyUrl;
    private Map<String, String> customHeaders = new HashMap<>();

    protected Builder() {}

    /**
     * Sets the model string. The model string specifies the LLM provider (e.g., Vertex AI, Gemini),
     * API version, and the model ID.
     *
     * <p><b>Format:</b> {@code apigee/[<provider>/][<version>/]<model_id>}
     *
     * <p><b>Components:</b>
     *
     * <ul>
     *   <li><b>{@code provider}</b> (optional): {@code vertex_ai} or {@code gemini}. If omitted,
     *       behavior depends on the {@code GOOGLE_GENAI_USE_VERTEXAI} environment variable. If that
     *       is not set to {@code TRUE} or {@code 1}, it defaults to {@code gemini}.
     *   <li><b>{@code version}</b> (optional): The API version (e.g., {@code v1}, {@code v1beta}).
     *       If omitted, the default version for the provider is used.
     *   <li><b>{@code model_id}</b> (required): The model identifier (e.g., {@code
     *       gemini-2.5-flash}).
     * </ul>
     *
     * <p><b>Examples:</b>
     *
     * <ul>
     *   <li>{@code apigee/gemini-2.5-flash}
     *   <li>{@code apigee/v1/gemini-2.5-flash}
     *   <li>{@code apigee/vertex_ai/gemini-2.5-flash}
     *   <li>{@code apigee/gemini/v1/gemini-2.5-flash}
     *   <li>{@code apigee/vertex_ai/v1beta/gemini-2.5-flash}
     * </ul>
     *
     * @param modelName the model string.
     * @return this builder.
     */
    @CanIgnoreReturnValue
    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    /**
     * Sets the URL of the Apigee proxy. If not set, it will be read from the {@code
     * APIGEE_PROXY_URL} environment variable.
     *
     * @param proxyUrl the Apigee proxy URL.
     * @return this builder.
     */
    @CanIgnoreReturnValue
    public Builder proxyUrl(String proxyUrl) {
      this.proxyUrl = proxyUrl;
      return this;
    }

    /**
     * Sets a dictionary of headers to be sent with the request.
     *
     * @param customHeaders the custom headers.
     * @return this builder.
     */
    @CanIgnoreReturnValue
    public Builder customHeaders(Map<String, String> customHeaders) {
      this.customHeaders = customHeaders;
      return this;
    }

    /**
     * Builds the {@link ApigeeLlm} instance.
     *
     * @return a new {@link ApigeeLlm} instance.
     * @throws NullPointerException if modelName is null.
     * @throws IllegalArgumentException if the model string is invalid.
     */
    public ApigeeLlm build() {
      if (!validateModelString(modelName)) {
        throw new IllegalArgumentException("Invalid model string: " + modelName);
      }

      return new ApigeeLlm(modelName, proxyUrl, customHeaders);
    }
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    String modelToUse = llmRequest.model().orElse(model());
    String modelId = getModelId(modelToUse);
    LlmRequest newLlmRequest = llmRequest.toBuilder().model(modelId).build();
    return geminiDelegate.generateContent(newLlmRequest, stream);
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    String modelToUse = llmRequest.model().orElse(model());
    String modelId = getModelId(modelToUse);
    LlmRequest newLlmRequest = llmRequest.toBuilder().model(modelId).build();
    return geminiDelegate.connect(newLlmRequest);
  }

  private static boolean validateModelString(String model) {
    if (!model.startsWith("apigee/")) {
      return false;
    }
    String modelPart = model.substring("apigee/".length());
    if (modelPart.isEmpty()) {
      return false;
    }
    String[] components = modelPart.split("/", -1);
    if (components.length == 1) {
      return true;
    }
    if (components.length == 3) {
      if (!components[0].equals("vertex_ai") && !components[0].equals("gemini")) {
        return false;
      }
      return components[1].startsWith("v");
    }
    if (components.length == 2) {
      if (components[0].equals("vertex_ai") || components[0].equals("gemini")) {
        return true;
      }
      return components[0].startsWith("v");
    }
    return false;
  }

  private static boolean isEnvEnabled(String envVarName) {
    String value = System.getenv(envVarName);
    if (value == null) {
      value = "0";
    }
    return Ascii.equalsIgnoreCase(value, "true") || value.equals("1");
  }

  private static String getModelId(String model) {
    String modelPart = model.substring("apigee/".length());
    String[] components = modelPart.split("/", -1);
    return components[components.length - 1];
  }
}
