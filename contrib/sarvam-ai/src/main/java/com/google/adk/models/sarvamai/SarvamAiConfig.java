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

package com.google.adk.models.sarvamai;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Immutable configuration for Sarvam AI services.
 *
 * <p>Supports all Sarvam API parameters including chat completion, STT, TTS, and Vision. Uses the
 * Builder pattern for safe, incremental construction with sensible defaults.
 *
 * <p>API key resolution order: explicit value > {@code SARVAM_API_KEY} environment variable.
 *
 * @author Sandeep Belgavi
 */
public final class SarvamAiConfig {

  public static final String DEFAULT_CHAT_ENDPOINT = "https://api.sarvam.ai/v1/chat/completions";
  public static final String DEFAULT_STT_ENDPOINT = "https://api.sarvam.ai/speech-to-text";
  public static final String DEFAULT_STT_WS_ENDPOINT =
      "wss://api.sarvam.ai/speech-to-text/streaming";
  public static final String DEFAULT_TTS_ENDPOINT = "https://api.sarvam.ai/text-to-speech";
  public static final String DEFAULT_TTS_WS_ENDPOINT =
      "wss://api.sarvam.ai/text-to-speech/streaming";
  public static final String DEFAULT_VISION_ENDPOINT =
      "https://api.sarvam.ai/document-intelligence";
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(30);
  public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(120);
  public static final int DEFAULT_MAX_RETRIES = 3;

  private final String apiKey;
  private final String chatEndpoint;
  private final String sttEndpoint;
  private final String sttWsEndpoint;
  private final String ttsEndpoint;
  private final String ttsWsEndpoint;
  private final String visionEndpoint;
  private final Duration connectTimeout;
  private final Duration readTimeout;
  private final int maxRetries;

  // Chat-specific parameters
  private final OptionalDouble temperature;
  private final OptionalDouble topP;
  private final OptionalInt maxTokens;
  private final Optional<String> reasoningEffort;
  private final Optional<Boolean> wikiGrounding;
  private final OptionalDouble frequencyPenalty;
  private final OptionalDouble presencePenalty;

  // TTS-specific parameters
  private final Optional<String> ttsSpeaker;
  private final Optional<String> ttsModel;
  private final OptionalDouble ttsPace;
  private final OptionalInt ttsSampleRate;

  // STT-specific parameters
  private final Optional<String> sttModel;
  private final Optional<String> sttMode;
  private final Optional<String> sttLanguageCode;

  private SarvamAiConfig(Builder builder) {
    String resolvedKey = builder.apiKey;
    if (Strings.isNullOrEmpty(resolvedKey)) {
      resolvedKey = System.getenv("SARVAM_API_KEY");
    }
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(resolvedKey),
        "Sarvam API key is required. Set via builder or SARVAM_API_KEY environment variable.");
    this.apiKey = resolvedKey;

    this.chatEndpoint = Objects.requireNonNullElse(builder.chatEndpoint, DEFAULT_CHAT_ENDPOINT);
    this.sttEndpoint = Objects.requireNonNullElse(builder.sttEndpoint, DEFAULT_STT_ENDPOINT);
    this.sttWsEndpoint = Objects.requireNonNullElse(builder.sttWsEndpoint, DEFAULT_STT_WS_ENDPOINT);
    this.ttsEndpoint = Objects.requireNonNullElse(builder.ttsEndpoint, DEFAULT_TTS_ENDPOINT);
    this.ttsWsEndpoint = Objects.requireNonNullElse(builder.ttsWsEndpoint, DEFAULT_TTS_WS_ENDPOINT);
    this.visionEndpoint =
        Objects.requireNonNullElse(builder.visionEndpoint, DEFAULT_VISION_ENDPOINT);
    this.connectTimeout =
        Objects.requireNonNullElse(builder.connectTimeout, DEFAULT_CONNECT_TIMEOUT);
    this.readTimeout = Objects.requireNonNullElse(builder.readTimeout, DEFAULT_READ_TIMEOUT);
    this.maxRetries = builder.maxRetries;
    this.temperature = builder.temperature;
    this.topP = builder.topP;
    this.maxTokens = builder.maxTokens;
    this.reasoningEffort = Optional.ofNullable(builder.reasoningEffort);
    this.wikiGrounding = Optional.ofNullable(builder.wikiGrounding);
    this.frequencyPenalty = builder.frequencyPenalty;
    this.presencePenalty = builder.presencePenalty;
    this.ttsSpeaker = Optional.ofNullable(builder.ttsSpeaker);
    this.ttsModel = Optional.ofNullable(builder.ttsModel);
    this.ttsPace = builder.ttsPace;
    this.ttsSampleRate = builder.ttsSampleRate;
    this.sttModel = Optional.ofNullable(builder.sttModel);
    this.sttMode = Optional.ofNullable(builder.sttMode);
    this.sttLanguageCode = Optional.ofNullable(builder.sttLanguageCode);
  }

  public static Builder builder() {
    return new Builder();
  }

  public String apiKey() {
    return apiKey;
  }

  public String chatEndpoint() {
    return chatEndpoint;
  }

  public String sttEndpoint() {
    return sttEndpoint;
  }

  public String sttWsEndpoint() {
    return sttWsEndpoint;
  }

  public String ttsEndpoint() {
    return ttsEndpoint;
  }

  public String ttsWsEndpoint() {
    return ttsWsEndpoint;
  }

  public String visionEndpoint() {
    return visionEndpoint;
  }

  public Duration connectTimeout() {
    return connectTimeout;
  }

  public Duration readTimeout() {
    return readTimeout;
  }

  public int maxRetries() {
    return maxRetries;
  }

  public OptionalDouble temperature() {
    return temperature;
  }

  public OptionalDouble topP() {
    return topP;
  }

  public OptionalInt maxTokens() {
    return maxTokens;
  }

  public Optional<String> reasoningEffort() {
    return reasoningEffort;
  }

  public Optional<Boolean> wikiGrounding() {
    return wikiGrounding;
  }

  public OptionalDouble frequencyPenalty() {
    return frequencyPenalty;
  }

  public OptionalDouble presencePenalty() {
    return presencePenalty;
  }

  public Optional<String> ttsSpeaker() {
    return ttsSpeaker;
  }

  public Optional<String> ttsModel() {
    return ttsModel;
  }

  public OptionalDouble ttsPace() {
    return ttsPace;
  }

  public OptionalInt ttsSampleRate() {
    return ttsSampleRate;
  }

  public Optional<String> sttModel() {
    return sttModel;
  }

  public Optional<String> sttMode() {
    return sttMode;
  }

  public Optional<String> sttLanguageCode() {
    return sttLanguageCode;
  }

  /** Builder for {@link SarvamAiConfig}. */
  public static final class Builder {
    private String apiKey;
    private String chatEndpoint;
    private String sttEndpoint;
    private String sttWsEndpoint;
    private String ttsEndpoint;
    private String ttsWsEndpoint;
    private String visionEndpoint;
    private Duration connectTimeout;
    private Duration readTimeout;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private OptionalDouble temperature = OptionalDouble.empty();
    private OptionalDouble topP = OptionalDouble.empty();
    private OptionalInt maxTokens = OptionalInt.empty();
    private String reasoningEffort;
    private Boolean wikiGrounding;
    private OptionalDouble frequencyPenalty = OptionalDouble.empty();
    private OptionalDouble presencePenalty = OptionalDouble.empty();
    private String ttsSpeaker;
    private String ttsModel;
    private OptionalDouble ttsPace = OptionalDouble.empty();
    private OptionalInt ttsSampleRate = OptionalInt.empty();
    private String sttModel;
    private String sttMode;
    private String sttLanguageCode;

    private Builder() {}

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder chatEndpoint(String chatEndpoint) {
      this.chatEndpoint = chatEndpoint;
      return this;
    }

    public Builder sttEndpoint(String sttEndpoint) {
      this.sttEndpoint = sttEndpoint;
      return this;
    }

    public Builder sttWsEndpoint(String sttWsEndpoint) {
      this.sttWsEndpoint = sttWsEndpoint;
      return this;
    }

    public Builder ttsEndpoint(String ttsEndpoint) {
      this.ttsEndpoint = ttsEndpoint;
      return this;
    }

    public Builder ttsWsEndpoint(String ttsWsEndpoint) {
      this.ttsWsEndpoint = ttsWsEndpoint;
      return this;
    }

    public Builder visionEndpoint(String visionEndpoint) {
      this.visionEndpoint = visionEndpoint;
      return this;
    }

    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = connectTimeout;
      return this;
    }

    public Builder readTimeout(Duration readTimeout) {
      this.readTimeout = readTimeout;
      return this;
    }

    public Builder maxRetries(int maxRetries) {
      Preconditions.checkArgument(maxRetries >= 0, "maxRetries must be >= 0");
      this.maxRetries = maxRetries;
      return this;
    }

    public Builder temperature(double temperature) {
      Preconditions.checkArgument(
          temperature >= 0 && temperature <= 2, "temperature must be between 0 and 2");
      this.temperature = OptionalDouble.of(temperature);
      return this;
    }

    public Builder topP(double topP) {
      Preconditions.checkArgument(topP >= 0 && topP <= 1, "topP must be between 0 and 1");
      this.topP = OptionalDouble.of(topP);
      return this;
    }

    public Builder maxTokens(int maxTokens) {
      Preconditions.checkArgument(maxTokens > 0, "maxTokens must be > 0");
      this.maxTokens = OptionalInt.of(maxTokens);
      return this;
    }

    public Builder reasoningEffort(String reasoningEffort) {
      Preconditions.checkArgument(
          "low".equals(reasoningEffort)
              || "medium".equals(reasoningEffort)
              || "high".equals(reasoningEffort),
          "reasoningEffort must be one of: low, medium, high");
      this.reasoningEffort = reasoningEffort;
      return this;
    }

    public Builder wikiGrounding(boolean wikiGrounding) {
      this.wikiGrounding = wikiGrounding;
      return this;
    }

    public Builder frequencyPenalty(double frequencyPenalty) {
      Preconditions.checkArgument(
          frequencyPenalty >= -2 && frequencyPenalty <= 2,
          "frequencyPenalty must be between -2 and 2");
      this.frequencyPenalty = OptionalDouble.of(frequencyPenalty);
      return this;
    }

    public Builder presencePenalty(double presencePenalty) {
      Preconditions.checkArgument(
          presencePenalty >= -2 && presencePenalty <= 2,
          "presencePenalty must be between -2 and 2");
      this.presencePenalty = OptionalDouble.of(presencePenalty);
      return this;
    }

    public Builder ttsSpeaker(String ttsSpeaker) {
      this.ttsSpeaker = ttsSpeaker;
      return this;
    }

    public Builder ttsModel(String ttsModel) {
      this.ttsModel = ttsModel;
      return this;
    }

    public Builder ttsPace(double ttsPace) {
      Preconditions.checkArgument(
          ttsPace >= 0.5 && ttsPace <= 2.0, "ttsPace must be between 0.5 and 2.0");
      this.ttsPace = OptionalDouble.of(ttsPace);
      return this;
    }

    public Builder ttsSampleRate(int ttsSampleRate) {
      this.ttsSampleRate = OptionalInt.of(ttsSampleRate);
      return this;
    }

    public Builder sttModel(String sttModel) {
      this.sttModel = sttModel;
      return this;
    }

    public Builder sttMode(String sttMode) {
      Preconditions.checkArgument(
          "transcribe".equals(sttMode)
              || "translate".equals(sttMode)
              || "verbatim".equals(sttMode)
              || "translit".equals(sttMode)
              || "codemix".equals(sttMode),
          "sttMode must be one of: transcribe, translate, verbatim, translit, codemix");
      this.sttMode = sttMode;
      return this;
    }

    public Builder sttLanguageCode(String sttLanguageCode) {
      this.sttLanguageCode = sttLanguageCode;
      return this;
    }

    public SarvamAiConfig build() {
      return new SarvamAiConfig(this);
    }
  }
}
