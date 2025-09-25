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
package com.google.adk.models.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** Test to discover Spring AI embedding model interfaces and capabilities. */
class EmbeddingModelDiscoveryTest {

  @Test
  void testSpringAIEmbeddingInterfaces() {
    // This test just verifies that Spring AI embedding interfaces are available
    // and helps us understand the API structure

    // Check if these classes exist and compile
    Class<?> embeddingModelClass = EmbeddingModel.class;
    Class<?> embeddingRequestClass = EmbeddingRequest.class;
    Class<?> embeddingResponseClass = EmbeddingResponse.class;

    System.out.println("EmbeddingModel available: " + embeddingModelClass.getName());
    System.out.println("EmbeddingRequest available: " + embeddingRequestClass.getName());
    System.out.println("EmbeddingResponse available: " + embeddingResponseClass.getName());

    // Print methods to understand the API
    System.out.println("\nEmbeddingModel methods:");
    for (var method : embeddingModelClass.getMethods()) {
      if (method.getDeclaringClass() == embeddingModelClass) {
        System.out.println(
            "  "
                + method.getName()
                + "("
                + java.util.Arrays.toString(method.getParameterTypes())
                + "): "
                + method.getReturnType().getSimpleName());
      }
    }
  }
}
