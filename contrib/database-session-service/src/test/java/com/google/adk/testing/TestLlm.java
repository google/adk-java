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

package com.google.adk.testing;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.adk.agents.LiveRequest;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * A test implementation of {@link BaseLlm}.
 *
 * <p>Supports providing responses via a sequence of {@link LlmResponse} objects or a {@link
 * Supplier} of {@code Flowable<LlmResponse>}. It also captures all standard and live requests for
 * assertion in tests.
 */
public final class TestLlm extends BaseLlm {
  private final List<LlmRequest> llmRequests = Collections.synchronizedList(new ArrayList<>());
  private final List<LiveRequest> liveRequestHistory =
      Collections.synchronizedList(new ArrayList<>());

  private final List<LlmResponse> responseSequence;
  private final AtomicInteger responseIndex = new AtomicInteger(0);

  private final Supplier<Flowable<LlmResponse>> responsesSupplier;
  private final Optional<Throwable> error;

  private TestLlm(
      @Nullable List<LlmResponse> responses,
      @Nullable Supplier<Flowable<LlmResponse>> responsesSupplier,
      @Nullable Throwable error) {
    super("test-llm");
    this.responseSequence = responses;
    this.responsesSupplier = responsesSupplier;
    this.error = Optional.ofNullable(error);
  }

  /**
   * Constructs a TestLlm that serves responses sequentially from the provided list.
   *
   * @param responses A list of LlmResponse objects to be served in order. Can be null or empty.
   */
  public TestLlm(@Nullable List<LlmResponse> responses) {
    this(responses == null ? ImmutableList.of() : ImmutableList.copyOf(responses), null, null);
  }

  /**
   * Constructs a TestLlm that uses the provided supplier to get responses.
   *
   * @param responsesSupplier A supplier that provides a Flowable of LlmResponse.
   */
  public TestLlm(Supplier<Flowable<LlmResponse>> responsesSupplier) {
    this(null, responsesSupplier, null);
  }

  @CanIgnoreReturnValue
  public static TestLlm create(@Nullable List<?> responses, @Nullable Throwable error) {
    if (error != null) {
      return new TestLlm(ImmutableList.of(), null, error);
    }
    if (responses == null || responses.isEmpty()) {
      return new TestLlm(ImmutableList.of(), null, null);
    }

    List<LlmResponse> llmResponses = new ArrayList<>();
    Object first = responses.get(0);
    if (first instanceof LlmResponse) {
      // responses is List<LlmResponse>
      for (Object response : responses) {
        if (response instanceof LlmResponse llmResponse) {
          llmResponses.add(llmResponse);
        } else {
          throw new IllegalArgumentException("Mixed response types in List<?>");
        }
      }
    } else if (first instanceof String) {
      // responses is List<String>
      for (Object item : responses) {
        if (item instanceof String string) {
          llmResponses.add(
              LlmResponse.builder()
                  .content(Content.builder().parts(ImmutableList.of(Part.fromText(string))).build())
                  .build());
        } else {
          throw new IllegalArgumentException("Mixed response types in List<?>");
        }
      }
    } else if (first instanceof Part) {
      // responses is List<Part>
      for (Object item : responses) {
        if (item instanceof Part part) {
          llmResponses.add(
              LlmResponse.builder()
                  .content(Content.builder().parts(ImmutableList.of(part)).build())
                  .build());
        } else {
          throw new IllegalArgumentException("Mixed response types in List<?>");
        }
      }
    } else if (first instanceof List) {
      // responses is List<List<Part>>
      for (Object item : responses) {
        if (item instanceof List) {
          List<?> partList = (List<?>) item;
          if (!partList.isEmpty() && partList.get(0) instanceof Part) {
            llmResponses.add(
                LlmResponse.builder()
                    .content(
                        Content.builder()
                            .parts(partList.stream().map(p -> (Part) p).collect(toImmutableList()))
                            .build())
                    .build());
          } else {
            throw new IllegalArgumentException("Inner list elements are not Part instances.");
          }
        } else {
          throw new IllegalArgumentException("Mixed response types in List<?>");
        }
      }
    } else {
      throw new IllegalArgumentException("Unsupported response type in List<?>" + first.getClass());
    }
    return new TestLlm(llmResponses, null, null);
  }

  @CanIgnoreReturnValue
  public static TestLlm create(@Nullable List<?> responses) {
    return create(responses, null);
  }

  @CanIgnoreReturnValue
  public static TestLlm create(String... responses) {
    return create(Arrays.asList(responses), null);
  }

  @CanIgnoreReturnValue
  public static TestLlm create(LlmResponse... responses) {
    return create(Arrays.asList(responses), null);
  }

  @CanIgnoreReturnValue
  public static TestLlm create(Part... responses) {
    return create(Arrays.asList(responses), null);
  }

  @Override
  public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
    llmRequests.add(llmRequest);

    if (error.isPresent()) {
      return Flowable.error(error.get());
    }

    if (this.responseSequence != null) {
      // Sequential discrete response mode
      int currentIndex = responseIndex.getAndIncrement();
      if (currentIndex < responseSequence.size()) {
        LlmResponse nextResponse = responseSequence.get(currentIndex);
        return Flowable.just(nextResponse);
      } else {
        return Flowable.error(
            new NoSuchElementException(
                "TestLlm (List mode) out of responses. Requested response for LLM call "
                    + llmRequests.size()
                    + " (index "
                    + currentIndex
                    + ") but only "
                    + responseSequence.size()
                    + " were configured."));
      }
    } else if (this.responsesSupplier != null) {
      // Legacy/streaming supplier mode
      return responsesSupplier.get();
    } else {
      // Should not happen if constructors are used properly
      return Flowable.error(new IllegalStateException("TestLlm not initialized with responses."));
    }
  }

  @Override
  public BaseLlmConnection connect(LlmRequest llmRequest) {
    llmRequests.add(llmRequest);
    return new TestLlmConnection();
  }

  public ImmutableList<LlmRequest> getRequests() {
    return ImmutableList.copyOf(llmRequests);
  }

  public LlmRequest getLastRequest() {
    return Iterables.getLast(llmRequests);
  }

  /** Returns an immutable list of all {@link LiveRequest}s sent to the live connection. */
  public ImmutableList<LiveRequest> getLiveRequestHistory() {
    return ImmutableList.copyOf(liveRequestHistory);
  }

  public boolean waitForStreamingToolResults(String toolName, int expectedCount, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    String prefix = "Function " + toolName + " returned:";

    Predicate<LiveRequest> isStreamingToolResult =
        req ->
            req.content()
                .filter(
                    content ->
                        content.role().orElse("").equals("user")
                            && content.text() != null
                            && content.text().startsWith(prefix))
                .isPresent();

    long currentCount = 0;
    while (Instant.now().isBefore(deadline)) {
      currentCount = getLiveRequestHistory().stream().filter(isStreamingToolResult).count();
      if (currentCount >= expectedCount) {
        return true;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /** A test implementation of {@link BaseLlmConnection} for {@link TestLlm}. */
  private final class TestLlmConnection implements BaseLlmConnection {

    @Override
    public Completable sendHistory(List<Content> history) {
      return Completable.complete();
    }

    @Override
    public Completable sendContent(Content content) {
      liveRequestHistory.add(LiveRequest.builder().content(content).build());
      return Completable.complete();
    }

    @Override
    public Completable sendRealtime(Blob blob) {
      liveRequestHistory.add(LiveRequest.builder().blob(blob).build());
      return Completable.complete();
    }

    @Override
    public Flowable<LlmResponse> receive() {
      if (error.isPresent()) {
        return Flowable.error(error.get());
      }
      if (responseSequence != null) {
        return Flowable.fromIterable(responseSequence);
      } else if (responsesSupplier != null) {
        return responsesSupplier.get();
      } else {
        return Flowable.error(new IllegalStateException("TestLlm not initialized with responses."));
      }
    }

    @Override
    public void close() {
      liveRequestHistory.add(LiveRequest.builder().close(true).build());
    }

    @Override
    public void close(Throwable throwable) {
      close();
    }
  }
}
