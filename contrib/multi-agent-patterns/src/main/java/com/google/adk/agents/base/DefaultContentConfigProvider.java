package com.google.adk.agents.base;

import static com.google.common.collect.ImmutableList.of;
import static org.glassfish.jersey.internal.guava.Preconditions.checkNotNull;

import com.google.adk.agents.config.MultiAgentSystemConfig.ContentConfigContext;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import jakarta.inject.Provider;

// https://adk.dev/agents/models/google-gemini/#error-code-429-resource_exhausted

public class DefaultContentConfigProvider implements Provider<GenerateContentConfig> {

  private static final ImmutableList<Integer> STATUS_CODES = of(429, 500, 503, 504);

  public DefaultContentConfigProvider(ContentConfigContext ccc) {
    this.ccc = checkNotNull(ccc, "contentConfigContext");
  }

  private final ContentConfigContext ccc;

  @Override
  public GenerateContentConfig get() {
    return GenerateContentConfig.builder() //
        .temperature(Double.valueOf(ccc.temperature()).floatValue()) //
        .maxOutputTokens(ccc.maxOutputTokens()) //
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
        .expBase(Double.valueOf(ccc.baseDelay())) // delay multiplier
        .attempts(ccc.maxRetryAttempts()) // max retry attempts
        .httpStatusCodes(STATUS_CODES) // retry on these HTTP errors
        .build(); //
  }
}
