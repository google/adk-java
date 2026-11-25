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

package com.google.adk.flows.llmflows;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.runner.BaseSteeringService;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/** Internal state coordinating steering input persistence and LLM flow completion. */
public final class SteeringState {
  private static final String KEY = SteeringState.class.getName();

  private final BaseAgent owner;
  private final BiFunction<InvocationContext, BaseSteeringService.Input, Single<Event>> createEvent;
  private final BaseSteeringService.Inbox inbox;
  private final Object lock = new Object();

  @GuardedBy("lock")
  private final Set<String> persisting = new HashSet<>();

  @GuardedBy("lock")
  private boolean closed;

  @GuardedBy("lock")
  private boolean inboxClosed;

  public SteeringState(
      InvocationContext context,
      BaseSteeringService.Inbox inbox,
      BiFunction<InvocationContext, BaseSteeringService.Input, Single<Event>> createEvent) {
    this.inbox = inbox;
    owner = context.agent();
    this.createEvent = createEvent;
    context.callbackContextData().put(KEY, this);
  }

  /** Looks up only this agent's queue, even when callback data is shared with children. */
  public static Optional<SteeringState> get(InvocationContext context) {
    SteeringState inputs = (SteeringState) context.callbackContextData().get(KEY);
    return inputs != null && inputs.owner == context.agent()
        ? Optional.of(inputs)
        : Optional.empty();
  }

  /** Validates an additional user message without accepting model output or execution controls. */
  public static Content userContent(Content message) {
    checkArgument(message != null, "Steering message must not be null");
    checkArgument(
        message.role().isEmpty() || message.role().get().equals("user"),
        "Steering requires a user message");
    List<Part> parts = message.parts().orElse(ImmutableList.of());
    checkArgument(!parts.isEmpty(), "Steering message must have parts");
    boolean hasContent = false;
    for (Part part : parts) {
      checkArgument(
          part != null
              && part.functionCall().isEmpty()
              && part.functionResponse().isEmpty()
              && part.executableCode().isEmpty()
              && part.codeExecutionResult().isEmpty()
              && part.toolCall().isEmpty()
              && part.toolResponse().isEmpty()
              && !part.thought().orElse(false)
              && part.thoughtSignature().isEmpty(),
          "Steering accepts user content, not model output or tool responses");
      checkArgument(
          part.text().isPresent() || part.inlineData().isPresent() || part.fileData().isPresent(),
          "Steering part must contain text, inline data, or a file reference");
      if (part.inlineData().isPresent()) {
        checkArgument(
            part.inlineData().get().data().filter(data -> data.length > 0).isPresent(),
            "Steering inline data must not be empty");
        hasContent = true;
      }
      if (part.fileData().isPresent()) {
        checkArgument(
            part.fileData().get().fileUri().filter(uri -> !uri.isBlank()).isPresent(),
            "Steering file URI must not be blank");
        hasContent = true;
      }
      hasContent |= part.text().filter(text -> !text.isBlank()).isPresent();
    }
    checkArgument(hasContent, "Steering message must not be empty");
    return message.toBuilder().role("user").parts(ImmutableList.copyOf(parts)).build();
  }

  /** Emits one input batch and waits for the Runner to persist it before request preprocessing. */
  public Flowable<Event> drain(InvocationContext context) {
    return Flowable.defer(() -> {
      synchronized (lock) {
        if (closed || context.endInvocation()) {
          return Flowable.empty();
        }
      }
      return Flowable.fromIterable(inbox.pending())
          .concatMap(input ->
              createEvent
                  .apply(context, input)
                  .flatMapPublisher(event -> {
                    synchronized (lock) {
                      if (closed) {
                        return Flowable.empty();
                      }
                      persisting.add(event.id());
                    }
                    return Flowable.just(event)
                        .concatWith(
                            PersistBarrier.awaitPersisted(
                                context, ImmutableList.of(event)));
                  }));
    });
  }

  /** Acknowledges only this flow's additional user events after session persistence. */
  public void persisted(Event event) {
    synchronized (lock) {
      if (!persisting.remove(event.id())) {
        return;
      }
    }
    inbox.persisted(event);
  }

  /** Atomically closes normal admission, or keeps the flow running for accepted input. */
  public boolean closeIfIdle() {
    return inbox.closeIfIdle();
  }

  /** Closes on completion, failure, cancellation, a pause, or an execution limit. */
  public void close() {
    synchronized (lock) {
      if (inboxClosed) {
        return;
      }
      closed = true;
      persisting.clear();
    }
    // Stop local consumption immediately, but let a later lifecycle cleanup retry a failed
    // storage close. Do not hold our monitor while the inbox notifies receipt observers.
    inbox.close();
    synchronized (lock) {
      inboxClosed = true;
    }
  }
}
