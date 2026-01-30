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
package com.google.adk.testing;

import com.google.adk.agents.Callbacks.AfterAgentCallback;
import com.google.adk.agents.Callbacks.AfterAgentCallbackSync;
import com.google.adk.agents.Callbacks.AfterModelCallback;
import com.google.adk.agents.Callbacks.AfterModelCallbackSync;
import com.google.adk.agents.Callbacks.AfterToolCallback;
import com.google.adk.agents.Callbacks.AfterToolCallbackSync;
import com.google.adk.agents.Callbacks.BeforeAgentCallback;
import com.google.adk.agents.Callbacks.BeforeAgentCallbackSync;
import com.google.adk.agents.Callbacks.BeforeModelCallback;
import com.google.adk.agents.Callbacks.BeforeModelCallbackSync;
import com.google.adk.agents.Callbacks.BeforeToolCallback;
import com.google.adk.agents.Callbacks.BeforeToolCallbackSync;
import com.google.adk.events.Event;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * A test helper that wraps an {@link AtomicBoolean} and provides factory methods for creating
 * callbacks that update the boolean when called.
 *
 * @param <T> The type of the result returned by the callback.
 */
public final class TestCallback<T> {
  private final AtomicBoolean called = new AtomicBoolean(false);
  private final Optional<T> result;

  private TestCallback(Optional<T> result) {
    this.result = result;
  }

  /** Creates a {@link TestCallback} that returns the given result. */
  public static <T> TestCallback<T> returning(T result) {
    return new TestCallback<>(Optional.of(result));
  }

  /** Creates a {@link TestCallback} that returns an empty result. */
  public static <T> TestCallback<T> returningEmpty() {
    return new TestCallback<>(Optional.empty());
  }

  /** Returns true if the callback was called. */
  public boolean wasCalled() {
    return called.get();
  }

  /** Marks the callback as called. */
  public void markAsCalled() {
    called.set(true);
  }

  private Maybe<T> callMaybe() {
    called.set(true);
    return result.map(Maybe::just).orElseGet(Maybe::empty);
  }

  private Optional<T> callOptional() {
    called.set(true);
    return result;
  }

  /**
   * Returns a {@link Supplier} that marks this callback as called and returns a {@link Flowable}
   * with an event containing the given content.
   */
  public Supplier<Flowable<Event>> asRunAsyncImplSupplier(Content content) {
    return () ->
        Flowable.defer(
            () -> {
              markAsCalled();
              return Flowable.just(Event.builder().content(content).build());
            });
  }

  /**
   * Returns a {@link Supplier} that marks this callback as called and returns a {@link Flowable}
   */
  public Supplier<Flowable<Event>> asRunAsyncImplSupplier(String contentText) {
    return asRunAsyncImplSupplier(Content.fromParts(Part.fromText(contentText)));
  }

  /**
   * Returns a {@link Supplier} that marks this callback as called and returns a {@link Flowable}
   * with an event containing the given content.
   */
  public Supplier<Flowable<Event>> asRunLiveImplSupplier(Content content) {
    return () ->
        Flowable.defer(
            () -> {
              markAsCalled();
              return Flowable.just(Event.builder().content(content).build());
            });
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is Content.
  public BeforeAgentCallback asBeforeAgentCallback() {
    return (unusedCtx) -> (Maybe<Content>) callMaybe();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is Content.
  public BeforeAgentCallbackSync asBeforeAgentCallbackSync() {
    return (unusedCtx) -> (Optional<Content>) callOptional();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is Content.
  public AfterAgentCallback asAfterAgentCallback() {
    return (unusedCtx) -> (Maybe<Content>) callMaybe();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is Content.
  public AfterAgentCallbackSync asAfterAgentCallbackSync() {
    return (unusedCtx) -> (Optional<Content>) callOptional();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is LlmResponse.
  public BeforeModelCallback asBeforeModelCallback() {
    return (unusedCtx, unusedReq) -> (Maybe<LlmResponse>) callMaybe();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is LlmResponse.
  public BeforeModelCallbackSync asBeforeModelCallbackSync() {
    return (unusedCtx, unusedReq) -> (Optional<LlmResponse>) callOptional();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is LlmResponse.
  public AfterModelCallback asAfterModelCallback() {
    return (unusedCtx, unusedRes) -> (Maybe<LlmResponse>) callMaybe();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is LlmResponse.
  public AfterModelCallbackSync asAfterModelCallbackSync() {
    return (unusedCtx, unusedRes) -> (Optional<LlmResponse>) callOptional();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is Map<String, Object>.
  public BeforeToolCallback asBeforeToolCallback() {
    return (unusedCtx, unusedTool, unusedToolArgs, unusedToolCtx) ->
        (Maybe<Map<String, Object>>) callMaybe();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is Map<String, Object>.
  public BeforeToolCallbackSync asBeforeToolCallbackSync() {
    return (unusedCtx, unusedTool, unusedToolArgs, unusedToolCtx) ->
        (Optional<Map<String, Object>>) callOptional();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is Map<String, Object>.
  public AfterToolCallback asAfterToolCallback() {
    return (unusedCtx, unusedTool, unusedToolArgs, unusedToolCtx, unusedRes) ->
        (Maybe<Map<String, Object>>) callMaybe();
  }

  @SuppressWarnings("unchecked") // This cast is safe if T is Map<String, Object>.
  public AfterToolCallbackSync asAfterToolCallbackSync() {
    return (unusedCtx, unusedTool, unusedToolArgs, unusedToolCtx, unusedRes) ->
        (Optional<Map<String, Object>>) callOptional();
  }
}
