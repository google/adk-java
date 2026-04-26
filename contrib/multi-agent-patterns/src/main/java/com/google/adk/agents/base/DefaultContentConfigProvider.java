package com.google.adk.agents.base;

import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import jakarta.inject.Provider;

// https://adk.dev/agents/models/google-gemini/#error-code-429-resource_exhausted

public class DefaultContentConfigProvider implements Provider<GenerateContentConfig> {

  @Override
  public GenerateContentConfig get() {
    return GenerateContentConfig.builder() //
        .temperature(Double.valueOf(0.1).floatValue()) //
        .maxOutputTokens(2000) //
        .httpOptions(httpOptions()) //
        .build();
  }

  private HttpOptions httpOptions() {
    return HttpOptions.builder() //
        .retryOptions(retryOptions()) //
        .build(); //
  }

  private HttpRetryOptions retryOptions() {
    return HttpRetryOptions.builder()
        .initialDelay(1.0) //
        .expBase(15.0) // delay multiplier
        .attempts(1) // max retry attempts
        .jitter(1.0) //
        .httpStatusCodes(429, 500, 503, 504) // retry on these HTTP errors
        .build(); //
  }
}
