package com.google.adk.agents.plugins;

import static com.google.api.client.util.Preconditions.checkArgument;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest.Builder;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.common.util.concurrent.RateLimiter;
import io.reactivex.rxjava3.core.Maybe;

public class RateLimitPlugin extends BasePlugin {

  private final RateLimiter rateLimiter;

  public RateLimitPlugin(double requestPerSecond) {
    super("rate-limit-plugin");
    checkArgument(requestPerSecond > 0, "request per second must be greater than zero");
    this.rateLimiter = RateLimiter.create(requestPerSecond);
  }

  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, Builder llmRequest) {
    rateLimiter.acquire();
    return Maybe.empty();
  }
}
