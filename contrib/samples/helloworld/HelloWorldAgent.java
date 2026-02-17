// Copyright 2025 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.example.helloworld;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

/** Implements a simple agent that can roll a die and check prime numbers. */
public class HelloWorldAgent {
  public static final LlmAgent ROOT_AGENT =
      LlmAgent.builder()
          .name("data_processing_agent")
          .description("hello world agent that can roll a dice and check prime numbers.")
          .model("gemini-2.0-flash")
          .instruction(
              """
              You roll dice and answer questions about the outcome of the dice rolls.
              You can roll dice of different sizes.
              You can use multiple tools in parallel by calling functions in parallel(in one request and in one round).
              It is ok to discuss previous dice roles, and comment on the dice rolls.
              When you are asked to roll a die, you must call the roll_die tool with the number of sides. Be sure to pass in an integer. Do not pass in a string. If you don't know how many sides the die has, use 6.
              You should never roll a die on your own.
              When checking prime numbers, call the check_prime tool with a list of integers. Be sure to pass in a list of integers. You should never pass in a string.
              You should not check prime numbers before calling the tool.
              When you are asked to roll a die and check prime numbers, you should always make the following two function calls:
              1. You should first call the roll_die tool to get a roll. If you don't know how many sides the die has, use 6. Wait for the function response before calling the check_prime tool.
              2. After you get the function response from roll_die tool, you should call the check_prime tool with the roll_die result.
                2.1 If user asks you to check primes based on previous rolls, make sure you include the previous rolls in the list.
              3. When you respond, you must include the roll_die result from step 1, and the number of sides the die has.
              You should always perform the previous 3 steps when asking for a roll and checking prime numbers.
              You should not rely on the previous history on prime results.
              """)
          .tools(
              ImmutableList.of(
                  FunctionTool.create(HelloWorldAgent.class, "rollDie"),
                  FunctionTool.create(HelloWorldAgent.class, "checkPrime")))
          .build();

  private HelloWorldAgent() {}

  @SuppressWarnings("unchecked")
  public static ImmutableMap<String, Object> rollDie(int sides, ToolContext toolContext) {
    if (!toolContext.state().containsKey("rolls")) {
      Object unused = toolContext.state().put("rolls", new ArrayList<Integer>());
    }
    int result = new Random().nextInt(sides) + 1;
    ((ArrayList<Integer>) toolContext.state().get("rolls")).add(result);
    return ImmutableMap.of("result", result);
  }

  public static ImmutableMap<String, Object> checkPrime(List<Integer> nums) {
    HashSet<String> primes = new HashSet<>();
    for (int num : nums) {
      boolean isPrime = true;
      for (int i = 2; i <= Math.sqrt(num); i++) {
        if (num % i == 0) {
          isPrime = false;
          break;
        }
      }
      if (isPrime) {
        primes.add(String.valueOf(num));
      }
    }
    return ImmutableMap.of(
        "result",
        primes.isEmpty()
            ? "No prime numbers found."
            : String.join(", ", primes) + " are prime numbers.");
  }
}
