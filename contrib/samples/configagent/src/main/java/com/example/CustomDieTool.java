package com.example;

import com.google.adk.examples.Example;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.ExampleTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
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

  // Create ExampleTool with predefined examples using the core ADK ExampleTool
  public static final ExampleTool EXAMPLE_TOOL_INSTANCE =
      ExampleTool.builder()
          .addExample(
              Example.builder()
                  .input(Content.fromParts(Part.fromText("Roll a 6-sided die.")))
                  .output(
                      java.util.List.of(Content.fromParts(Part.fromText("I rolled a 4 for you."))))
                  .build())
          .addExample(
              Example.builder()
                  .input(Content.fromParts(Part.fromText("Is 7 a prime number?")))
                  .output(
                      java.util.List.of(
                          Content.fromParts(Part.fromText("Yes, 7 is a prime number."))))
                  .build())
          .addExample(
              Example.builder()
                  .input(
                      Content.fromParts(
                          Part.fromText("Roll a 10-sided die and check if it's prime.")))
                  .output(
                      java.util.List.of(
                          Content.fromParts(Part.fromText("I rolled an 8 for you.")),
                          Content.fromParts(Part.fromText("8 is not a prime number."))))
                  .build())
          .build();

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
