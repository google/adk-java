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

package com.example;

import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Tools for the user-defined config agent demo. */
public class CustomDieTool {
  public static final FunctionTool ROLL_DIE_INSTANCE =
      FunctionTool.create(CustomDieTool.class, "rollDie");
  public static final FunctionTool CHECK_PRIME_INSTANCE =
      FunctionTool.create(CustomDieTool.class, "checkPrime");

  @Schema(name = "roll_die", description = "Roll a die with specified number of sides")
  public static Map<String, Object> rollDie(
      @Schema(name = "sides", description = "Number of sides on the die") int sides,
      ToolContext toolContext) {
    if (!toolContext.state().containsKey("rolls")) {
      toolContext.state().put("rolls", new ArrayList<Integer>());
    }
    int result = new Random().nextInt(sides) + 1;
    @SuppressWarnings("unchecked")
    ArrayList<Integer> rolls = (ArrayList<Integer>) toolContext.state().get("rolls");
    rolls.add(result);
    return ImmutableMap.of("result", result);
  }

  @Schema(name = "check_prime", description = "Check if numbers are prime")
  public static Map<String, Object> checkPrime(
      @Schema(name = "nums", description = "List of numbers to check for primality")
          List<Integer> nums) {
    HashSet<String> primes = new HashSet<>();
    for (int num : nums) {
      boolean isPrime = true;
      if (num < 2) {
        isPrime = false;
      } else {
        for (int i = 2; i <= Math.sqrt(num); i++) {
          if (num % i == 0) {
            isPrime = false;
            break;
          }
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
