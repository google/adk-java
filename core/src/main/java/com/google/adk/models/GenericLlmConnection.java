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

package com.google.adk.models;

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a simulated persistent, bidirectional connection for HTTP-based LLMs like OllamaBaseLM
 * and RedbusADG.
 *
 * <p>This class provides a consistent API for sending and receiving messages, handling the
 * underlying HTTP requests and responses, and exposing them as a reactive stream.
 */
public final class GenericLlmConnection implements BaseLlmConnection {

  private static final Logger logger = LoggerFactory.getLogger(GenericLlmConnection.class);

  private final BaseLlm llm;
  private final LlmRequest llmRequest;
  private final PublishProcessor<LlmResponse> responseProcessor = PublishProcessor.create();
  private final Flowable<LlmResponse> responseFlowable = responseProcessor.serialize();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final List<Content> history = new ArrayList<>();

  /**
   * Establishes a new connection.
   *
   * @param llm The LLM to connect to (e.g., OllamaBaseLM, RedbusADG).
   * @param llmRequest The initial request to configure the connection.
   */
  public GenericLlmConnection(BaseLlm llm, LlmRequest llmRequest) {
    this.llm = Objects.requireNonNull(llm, "llm cannot be null");
    this.llmRequest = Objects.requireNonNull(llmRequest, "llmRequest cannot be null");
  }

  @Override
  public Completable sendHistory(List<Content> history) {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          this.history.addAll(history);
        });
  }

  @Override
  public Completable sendContent(Content content) {
    return Completable.fromAction(
        () -> {
          if (closed.get()) {
            throw new IllegalStateException("Connection is closed");
          }
          this.history.add(content);
          LlmRequest newRequest = llmRequest.toBuilder().contents(this.history).build();
          llm.generateContent(newRequest, true)
              .doOnNext(responseProcessor::onNext)
              .doOnError(responseProcessor::onError)
              .doOnComplete(responseProcessor::onComplete)
              .subscribe();
        });
  }

  @Override
  public Completable sendRealtime(Blob blob) {
    return Completable.error(
        new UnsupportedOperationException("Real-time streaming is not supported for this LLM."));
  }

  @Override
  public Flowable<LlmResponse> receive() {
    return responseFlowable;
  }

  @Override
  public void close() {
    closeInternal(null);
  }

  @Override
  public void close(Throwable throwable) {
    Objects.requireNonNull(throwable, "throwable cannot be null for close");
    closeInternal(throwable);
  }

  private void closeInternal(Throwable throwable) {
    if (closed.compareAndSet(false, true)) {
      logger.debug("Closing GenericLlmConnection.", throwable);
      if (throwable == null) {
        responseProcessor.onComplete();
      } else {
        responseProcessor.onError(throwable);
      }
    }
  }

  public static void main(String[] args) throws InterruptedException {
    // Note: This main method is for demonstration purposes.
    // To run it, you must have the required environment variables set.
    // E.g., OLLAMA_API_BASE for Ollama, and ADURL, ADU, ADP for RedbusADG.

    // --- Test for OllamaBaseLM ---
    System.out.println("--- Testing OllamaBaseLM Connection ---");
    // Check if the Ollama environment variable is set before running the test
    if (System.getenv("OLLAMA_API_BASE") != null) {
      try {
        OllamaBaseLM ollamaLlm = new OllamaBaseLM("qwen2.5vl");
        LlmRequest ollamaRequest = LlmRequest.builder().build();
        BaseLlmConnection ollamaConnection = ollamaLlm.connect(ollamaRequest);
        CountDownLatch ollamaLatch = new CountDownLatch(1);

        ollamaConnection
            .receive()
            .doOnNext(
                response -> {
                  response
                      .content()
                      .ifPresent(
                          content ->
                              content
                                  .parts()
                                  .ifPresent(
                                      parts ->
                                          parts.stream()
                                              .findFirst()
                                              .ifPresent(
                                                  part ->
                                                      part.text()
                                                          .ifPresent(
                                                              text ->
                                                                  System.out.println(
                                                                      "Ollama Response:"
                                                                          + " "
                                                                          + text)))));
                })
            .doOnError(
                error -> {
                  System.err.println("Ollama Error: " + error.getMessage());
                  ollamaLatch.countDown();
                })
            .doOnComplete(
                () -> {
                  System.out.println("Ollama stream completed.");
                  ollamaLatch.countDown();
                })
            .subscribe();

        ollamaConnection
            .sendContent(Content.fromParts(Part.fromText("Why is the sky blue?")))
            .blockingAwait();

        if (!ollamaLatch.await(60, TimeUnit.SECONDS)) {
          System.err.println("Ollama test timed out.");
        }
        ollamaConnection.close();
      } catch (Exception e) {
        System.err.println("Failed to run Ollama test: " + e.getMessage());
        e.printStackTrace();
      }
    } else {
      System.out.println("Skipping Ollama test: OLLAMA_API_BASE environment variable not set.");
    }

    System.out.println("\n");

    // --- Test for RedbusADG ---
    System.out.println("--- Testing RedbusADG Connection ---");
    // Check if the RedbusADG environment variables are set
    if (System.getenv("ADURL") != null
        && System.getenv("ADU") != null
        && System.getenv("ADP") != null) {
      try {
        RedbusADG redbusLlm = new RedbusADG("40"); // Example model ID
        LlmRequest redbusRequest = LlmRequest.builder().build();
        BaseLlmConnection redbusConnection = redbusLlm.connect(redbusRequest);
        CountDownLatch redbusLatch = new CountDownLatch(1);

        redbusConnection
            .receive()
            .doOnNext(
                response -> {
                  response
                      .content()
                      .ifPresent(
                          content ->
                              content
                                  .parts()
                                  .ifPresent(
                                      parts ->
                                          parts.stream()
                                              .findFirst()
                                              .ifPresent(
                                                  part ->
                                                      part.text()
                                                          .ifPresent(
                                                              text ->
                                                                  System.out.println(
                                                                      "RedbusADG Response:"
                                                                          + " "
                                                                          + text)))));
                })
            .doOnError(
                error -> {
                  System.err.println("RedbusADG Error: " + error.getMessage());
                  redbusLatch.countDown();
                })
            .doOnComplete(
                () -> {
                  System.out.println("RedbusADG stream completed.");
                  redbusLatch.countDown();
                })
            .subscribe();

        redbusConnection
            .sendContent(
                Content.fromParts(Part.fromText("What are the services offered by Azure?")))
            .blockingAwait();

        if (!redbusLatch.await(60, TimeUnit.SECONDS)) {
          System.err.println("RedbusADG test timed out.");
        }
        redbusConnection.close();
      } catch (Exception e) {
        System.err.println("Failed to run RedbusADG test: " + e.getMessage());
        e.printStackTrace();
      }
    } else {
      System.out.println(
          "Skipping RedbusADG test: ADURL, ADU, or ADP environment variables not set.");
    }
  }
}
