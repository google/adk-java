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

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests thread safety of StreamingResponseAggregator.
 *
 * <p>These tests verify that the aggregator correctly handles concurrent access from multiple
 * threads without data corruption or race conditions.
 */
class StreamingResponseAggregatorThreadSafetyTest {

  @Test
  void testConcurrentProcessStreamingResponse() throws InterruptedException {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    int numberOfThreads = 10;
    int responsesPerThread = 100;
    ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
    CountDownLatch latch = new CountDownLatch(numberOfThreads);
    AtomicInteger successCount = new AtomicInteger(0);

    for (int i = 0; i < numberOfThreads; i++) {
      final int threadNum = i;
      executor.submit(
          () -> {
            try {
              for (int j = 0; j < responsesPerThread; j++) {
                Content content =
                    Content.builder()
                        .role("model")
                        .parts(List.of(Part.fromText("Thread" + threadNum + "_Response" + j)))
                        .build();
                LlmResponse response = LlmResponse.builder().content(content).build();
                LlmResponse result = aggregator.processStreamingResponse(response);
                assertThat(result).isNotNull();
                successCount.incrementAndGet();
              }
            } finally {
              latch.countDown();
            }
          });
    }

    // Wait for all threads to complete
    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    // Verify all responses were processed
    assertThat(successCount.get()).isEqualTo(numberOfThreads * responsesPerThread);

    // Verify the aggregator contains all the text
    LlmResponse finalResponse = aggregator.getFinalResponse();
    assertThat(finalResponse.content()).isPresent();
    String aggregatedText = finalResponse.content().get().parts().get().get(0).text().get();

    // Verify all thread responses are present
    for (int i = 0; i < numberOfThreads; i++) {
      for (int j = 0; j < responsesPerThread; j++) {
        assertThat(aggregatedText).contains("Thread" + i + "_Response" + j);
      }
    }
  }

  @Test
  void testConcurrentResetAndProcess() throws InterruptedException {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    int numberOfOperations = 100;
    ExecutorService executor = Executors.newFixedThreadPool(5);
    CountDownLatch latch = new CountDownLatch(numberOfOperations);
    List<Exception> exceptions = new ArrayList<>();

    for (int i = 0; i < numberOfOperations; i++) {
      final int operationNum = i;
      executor.submit(
          () -> {
            try {
              if (operationNum % 3 == 0) {
                // Reset operation
                aggregator.reset();
              } else if (operationNum % 3 == 1) {
                // Process operation
                Content content =
                    Content.builder()
                        .role("model")
                        .parts(List.of(Part.fromText("Text" + operationNum)))
                        .build();
                LlmResponse response = LlmResponse.builder().content(content).build();
                aggregator.processStreamingResponse(response);
              } else {
                // GetFinalResponse operation
                LlmResponse finalResponse = aggregator.getFinalResponse();
                assertThat(finalResponse).isNotNull();
              }
            } catch (Exception e) {
              exceptions.add(e);
            } finally {
              latch.countDown();
            }
          });
    }

    // Wait for all operations to complete
    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    // Verify no exceptions occurred
    assertThat(exceptions).isEmpty();
  }

  @Test
  void testConcurrentReadOperations() throws InterruptedException {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();

    // Add some initial content
    Content content =
        Content.builder().role("model").parts(List.of(Part.fromText("Initial text"))).build();
    LlmResponse response = LlmResponse.builder().content(content).build();
    aggregator.processStreamingResponse(response);

    int numberOfThreads = 20;
    ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
    CountDownLatch latch = new CountDownLatch(numberOfThreads);
    AtomicInteger readCount = new AtomicInteger(0);

    for (int i = 0; i < numberOfThreads; i++) {
      executor.submit(
          () -> {
            try {
              // Perform multiple read operations
              for (int j = 0; j < 100; j++) {
                boolean empty = aggregator.isEmpty();
                int length = aggregator.getAccumulatedTextLength();
                assertThat(empty).isFalse();
                assertThat(length).isGreaterThan(0);
                readCount.incrementAndGet();
              }
            } finally {
              latch.countDown();
            }
          });
    }

    // Wait for all threads to complete
    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    // Verify all reads completed
    assertThat(readCount.get()).isEqualTo(numberOfThreads * 100);
  }

  @Test
  void testThreadSafetyWithFunctionCalls() throws InterruptedException {
    StreamingResponseAggregator aggregator = new StreamingResponseAggregator();
    int numberOfThreads = 5;
    ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
    CountDownLatch latch = new CountDownLatch(numberOfThreads);

    for (int i = 0; i < numberOfThreads; i++) {
      final int threadNum = i;
      executor.submit(
          () -> {
            try {
              // Add text content
              Content textContent =
                  Content.builder()
                      .role("model")
                      .parts(List.of(Part.fromText("Text from thread " + threadNum)))
                      .build();
              aggregator.processStreamingResponse(
                  LlmResponse.builder().content(textContent).build());

              // Add function call
              Content functionContent =
                  Content.builder()
                      .role("model")
                      .parts(
                          List.of(
                              Part.fromFunctionCall(
                                  "function_" + threadNum, java.util.Map.of("arg", threadNum))))
                      .build();
              aggregator.processStreamingResponse(
                  LlmResponse.builder().content(functionContent).build());
            } finally {
              latch.countDown();
            }
          });
    }

    // Wait for all threads to complete
    assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    executor.shutdown();

    // Verify the final response contains all content
    LlmResponse finalResponse = aggregator.getFinalResponse();
    assertThat(finalResponse.content()).isPresent();

    List<Part> parts = finalResponse.content().get().parts().get();
    assertThat(parts).hasSizeGreaterThanOrEqualTo(2); // At least text and function calls

    // Verify text content
    String aggregatedText = parts.get(0).text().get();
    for (int i = 0; i < numberOfThreads; i++) {
      assertThat(aggregatedText).contains("Text from thread " + i);
    }

    // Count function calls
    long functionCallCount = parts.stream().filter(part -> part.functionCall().isPresent()).count();
    assertThat(functionCallCount).isEqualTo(numberOfThreads);
  }
}
