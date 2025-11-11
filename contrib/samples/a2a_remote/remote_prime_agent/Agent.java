package com.google.adk.samples.a2a_remote.remote_prime_agent;

import static java.util.stream.Collectors.joining;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.GoogleLogger;
import io.reactivex.rxjava3.core.Maybe;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Agent that can check whether numbers are prime. */
public final class Agent {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  public static ImmutableMap<String, Object> checkPrime(List<Integer> nums) {
    logger.atInfo().log("checkPrime called with nums=%s", nums);
    Set<Integer> primes = new HashSet<>();
    for (int num : nums) {
      if (num <= 1) {
        continue;
      }
      boolean isPrime = true;
      for (int i = 2; i <= Math.sqrt(num); i++) {
        if (num % i == 0) {
          isPrime = false;
          break;
        }
      }
      if (isPrime) {
        primes.add(num);
      }
    }
    String result;
    if (primes.isEmpty()) {
      result = "No prime numbers found.";
    } else if (primes.size() == 1) {
      int only = primes.iterator().next();
      // Per request: singular phrasing without article
      result = only + " is prime number.";
    } else {
      result = primes.stream().map(String::valueOf).collect(joining(", ")) + " are prime numbers.";
    }
    logger.atInfo().log("checkPrime result=%s", result);
    return ImmutableMap.of("result", result);
  }

  public static final LlmAgent ROOT_AGENT =
      LlmAgent.builder()
          .model("gemini-2.5-pro")
          .name("check_prime_agent")
          .description("check prime agent that can check whether numbers are prime.")
          .instruction(
              """
                You check whether numbers are prime.

                If the last user message contains numbers, call checkPrime exactly once with exactly
                those integers as a list (e.g., [2]). Never add other numbers. Do not ask for
                clarification. Return only the tool's result.

                Always pass a list of integers to the tool (use a single-element list for one
                number). Never pass strings.
              """)
          // Log the exact contents passed to the LLM request for verification
          .beforeModelCallback(
              (callbackContext, llmRequest) -> {
                try {
                  logger.atInfo().log(
                      "Invocation events (count=%d): %s",
                      callbackContext.events().size(), callbackContext.events());
                } catch (Throwable t) {
                  logger.atWarning().withCause(t).log("BeforeModel logging error");
                }
                return Maybe.empty();
              })
          .afterModelCallback(
              (callbackContext, llmResponse) -> {
                try {
                  String content =
                      llmResponse.content().map(Object::toString).orElse("<empty content>");
                  logger.atInfo().log("AfterModel content=%s", content);
                  llmResponse
                      .errorMessage()
                      .ifPresent(
                          error ->
                              logger.atInfo().log(
                                  "AfterModel errorMessage=%s", error.replace("\n", "\\n")));
                } catch (Throwable t) {
                  logger.atWarning().withCause(t).log("AfterModel logging error");
                }
                return Maybe.empty();
              })
          .tools(ImmutableList.of(FunctionTool.create(Agent.class, "checkPrime")))
          .build();

  private Agent() {}
}
