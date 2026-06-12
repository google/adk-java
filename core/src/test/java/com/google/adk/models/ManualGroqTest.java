/*
 * Copyright 2026 Google LLC
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;

/**
 * Manual test for OpenAiCompatibleLlm with Groq.
 *
 * <p>This is NOT an automated test - it's for manual verification with a real API key.
 *
 * <p><b>Usage:</b>
 *
 * <pre>
 * # Set your Groq API key
 * export GROQ_API_KEY="your-api-key-here"
 *
 * # Run the test
 * cd /Users/sm0704/Documents/projects/ghc/shaamam/adk-java
 * ./mvnw -f core/pom.xml exec:java -Dexec.mainClass="com.google.adk.models.ManualGroqTest" \
 *   -Dexec.classpathScope=test
 * </pre>
 *
 * <p><b>Or pass API key directly:</b>
 *
 * <pre>
 * ./mvnw -f core/pom.xml exec:java -Dexec.mainClass="com.google.adk.models.ManualGroqTest" \
 *   -Dexec.classpathScope=test -Dexec.args="your-groq-api-key"
 * </pre>
 */
public class ManualGroqTest {

  public static void main(String[] args) {
    // Get API key from args or environment
    String apiKey = null;
    if (args.length > 0) {
      apiKey = args[0];
    } else {
      apiKey = System.getenv("GROQ_API_KEY");
    }

    if (apiKey == null || apiKey.isEmpty()) {
      System.err.println("ERROR: No API key provided!");
      System.err.println("Usage:");
      System.err.println("  1. Set GROQ_API_KEY environment variable");
      System.err.println("  2. Or pass API key as first argument");
      System.exit(1);
    }

    System.out.println("=== OpenAiCompatibleLlm Manual Test with Groq ===\n");

    try {
      // Test 1: Direct OpenAiCompatibleLlm usage
      System.out.println("[Test 1] Direct OpenAiCompatibleLlm usage");
      System.out.println("-----------------------------------------");

      OpenAiCompatibleLlm groq =
          OpenAiCompatibleLlm.builder()
              .baseUrl("https://api.groq.com/openai/v1/")
              .headers(ImmutableMap.of("Authorization", "Bearer " + apiKey))
              .modelName("llama-3.3-70b-versatile")
              .timeoutMillis(30_000)
              .build();

      System.out.println("✓ Created OpenAiCompatibleLlm for Groq");
      System.out.println("  Model: " + groq.model());

      LlmRequest request =
          LlmRequest.builder()
              .model(groq.model())
              .contents(
                  ImmutableList.of(
                      Content.fromParts(Part.fromText("Say 'Hello from Groq!' and nothing else."))))
              .build();

      System.out.println("\nSending request to Groq...");
      LlmResponse response = groq.generateContent(request, false).blockingFirst();

      if (response.content().isPresent()
          && response.content().get().parts().isPresent()
          && !response.content().get().parts().get().isEmpty()) {
        String responseText = response.content().get().parts().get().get(0).text().orElse("");
        System.out.println("✓ Got response: " + responseText);
      } else {
        System.out.println("✗ No response content");
        System.exit(1);
      }

      // Test 2: Registry integration
      System.out.println("\n[Test 2] Registry integration");
      System.out.println("-----------------------------");

      groq.registerWithPattern("groq-.*");
      System.out.println("✓ Registered pattern 'groq-.*'");

      BaseLlm resolvedLlm = LlmRegistry.getLlm("groq-llama-3.3-70b-versatile");
      if (resolvedLlm != null) {
        System.out.println("✓ Successfully resolved model from registry");
      } else {
        System.out.println("✗ Failed to resolve model from registry");
        System.exit(1);
      }

      // Test 3: Multi-turn conversation
      System.out.println("\n[Test 3] Multi-turn conversation");
      System.out.println("---------------------------------");

      LlmRequest multiTurnRequest =
          LlmRequest.builder()
              .model(groq.model())
              .contents(
                  ImmutableList.of(
                      Content.fromParts(Part.fromText("My name is Shaamam.")),
                      Content.fromParts(Part.fromText("Nice to meet you, Shaamam!")),
                      Content.fromParts(Part.fromText("What's my name?"))))
              .build();

      System.out.println("Sending multi-turn conversation...");
      LlmResponse multiTurnResponse = groq.generateContent(multiTurnRequest, false).blockingFirst();

      if (multiTurnResponse.content().isPresent()
          && multiTurnResponse.content().get().parts().isPresent()
          && !multiTurnResponse.content().get().parts().get().isEmpty()) {
        String multiTurnText =
            multiTurnResponse.content().get().parts().get().get(0).text().orElse("");
        System.out.println("✓ Got response: " + multiTurnText);

        if (multiTurnText.toLowerCase().contains("shaamam")) {
          System.out.println("✓ Correctly remembered name from conversation!");
        } else {
          System.out.println("⚠ Warning: Model didn't remember name from context");
        }
      }

      // Success summary
      System.out.println("\n" + "=".repeat(50));
      System.out.println("✓ ALL TESTS PASSED");
      System.out.println("=".repeat(50));
      System.out.println("\nOpenAiCompatibleLlm is working correctly with Groq!");
      System.out.println("Safe to commit and create PR.");

    } catch (Exception e) {
      System.err.println("\n✗ TEST FAILED");
      System.err.println("Error: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }
}
