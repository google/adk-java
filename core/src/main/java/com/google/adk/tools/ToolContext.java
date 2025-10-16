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

package com.google.adk.tools;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.EventActions;
import com.google.adk.memory.SearchMemoryResponse;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.reactivex.rxjava3.core.Single;
import java.util.Optional;
import javax.annotation.Nullable;

/** ToolContext object provides a structured context for executing tools or functions. */
public class ToolContext extends CallbackContext {
  private Optional<String> functionCallId = Optional.empty();
  private Optional<ToolConfirmation> toolConfirmation = Optional.empty();

  private ToolContext(
      InvocationContext invocationContext,
      EventActions eventActions,
      Optional<String> functionCallId,
      Optional<ToolConfirmation> toolConfirmation) {
    super(invocationContext, eventActions);
    this.functionCallId = functionCallId;
    this.toolConfirmation = toolConfirmation;
  }

  public EventActions actions() {
    return this.eventActions;
  }

  public void setActions(EventActions actions) {
    this.eventActions = actions;
  }

  public Optional<String> functionCallId() {
    return functionCallId;
  }

  public void functionCallId(String functionCallId) {
    this.functionCallId = Optional.ofNullable(functionCallId);
  }

  public Optional<ToolConfirmation> toolConfirmation() {
    return toolConfirmation;
  }

  public void toolConfirmation(ToolConfirmation toolConfirmation) {
    this.toolConfirmation = Optional.ofNullable(toolConfirmation);
  }

  @SuppressWarnings("unused")
  private void requestCredential() {
    // TODO: b/414678311 - Implement credential request logic. Make this public.
    throw new UnsupportedOperationException("Credential request not implemented yet.");
  }

  @SuppressWarnings("unused")
  private void getAuthResponse() {
    // TODO: b/414678311 - Implement auth response retrieval logic. Make this public.
    throw new UnsupportedOperationException("Auth response retrieval not implemented yet.");
  }

  /**
   * Requests confirmation for the given function call.
   *
   * @param hint A hint to the user on how to confirm the tool call.
   * @param payload The payload used to confirm the tool call.
   */
  public void requestConfirmation(@Nullable String hint, @Nullable Object payload) {
    if (functionCallId.isEmpty()) {
      throw new IllegalStateException("function_call_id is not set.");
    }
    this.eventActions
        .requestedToolConfirmations()
        .put(functionCallId.get(), ToolConfirmation.builder().hint(hint).payload(payload).build());
  }

  /**
   * Requests confirmation for the given function call.
   *
   * @param hint A hint to the user on how to confirm the tool call.
   */
  public void requestConfirmation(@Nullable String hint) {
    requestConfirmation(hint, null);
  }

  /** Requests confirmation for the given function call. */
  public void requestConfirmation() {
    requestConfirmation(null, null);
  }

  /** Searches the memory of the current user. */
  public Single<SearchMemoryResponse> searchMemory(String query) {
    if (invocationContext.memoryService() == null) {
      throw new IllegalStateException("Memory service is not initialized.");
    }
    return invocationContext
        .memoryService()
        .searchMemory(
            invocationContext.session().appName(), invocationContext.session().userId(), query);
  }

  public static Builder builder(InvocationContext invocationContext) {
    return new Builder(invocationContext);
  }

  public Builder toBuilder() {
    return new Builder(invocationContext)
        .actions(eventActions)
        .functionCallId(functionCallId.orElse(null))
        .toolConfirmation(toolConfirmation.orElse(null));
  }

  /** Builder for {@link ToolContext}. */
  public static final class Builder {
    private final InvocationContext invocationContext;
    private EventActions eventActions = EventActions.builder().build(); // Default empty actions
    private Optional<String> functionCallId = Optional.empty();
    private Optional<ToolConfirmation> toolConfirmation = Optional.empty();

    private Builder(InvocationContext invocationContext) {
      this.invocationContext = invocationContext;
    }

    @CanIgnoreReturnValue
    public Builder actions(EventActions actions) {
      this.eventActions = actions;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder functionCallId(String functionCallId) {
      this.functionCallId = Optional.ofNullable(functionCallId);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder toolConfirmation(ToolConfirmation toolConfirmation) {
      this.toolConfirmation = Optional.ofNullable(toolConfirmation);
      return this;
    }

    public ToolContext build() {
      return new ToolContext(invocationContext, eventActions, functionCallId, toolConfirmation);
    }
  }
}
