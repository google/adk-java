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
import com.google.adk.agents.Context;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.EventActions;
import com.google.adk.events.ToolConfirmation;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * ToolContext object provides a structured context for executing tools or functions.
 *
 * <p>Extends {@link CallbackContext} (and through it {@link Context}); {@link BaseTool#runAsync}
 * and tool callbacks take this type.
 */
public class ToolContext extends CallbackContext {

  private ToolContext(
      InvocationContext invocationContext,
      EventActions eventActions,
      Optional<String> functionCallId,
      Optional<ToolConfirmation> toolConfirmation,
      @Nullable String eventId) {
    super(invocationContext, eventActions, eventId);
    functionCallId(functionCallId.orElse(null));
    toolConfirmation(toolConfirmation.orElse(null));
  }

  public void setActions(EventActions actions) {
    this.eventActions = actions;
  }

  @SuppressWarnings("unused")
  private void requestCredential() {
    throw new UnsupportedOperationException("Credential request not implemented yet.");
  }

  @SuppressWarnings("unused")
  private void getAuthResponse() {
    throw new UnsupportedOperationException("Auth response retrieval not implemented yet.");
  }

  public static Builder builder(InvocationContext invocationContext) {
    return new Builder(invocationContext);
  }

  public Builder toBuilder() {
    return new Builder(invocationContext)
        .actions(eventActions)
        .functionCallId(functionCallId().orElse(null))
        .toolConfirmation(toolConfirmation().orElse(null))
        .eventId(eventId());
  }

  @Override
  public String toString() {
    return "ToolContext{"
        + "invocationContext="
        + invocationContext
        + ", eventActions="
        + eventActions
        + ", functionCallId="
        + functionCallId()
        + ", toolConfirmation="
        + toolConfirmation()
        + '}';
  }

  /** Builder for {@link ToolContext}. */
  public static final class Builder {
    private final InvocationContext invocationContext;
    private EventActions eventActions = EventActions.builder().build(); // Default empty actions
    private Optional<String> functionCallId = Optional.empty();
    private Optional<ToolConfirmation> toolConfirmation = Optional.empty();
    private String eventId;

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

    @CanIgnoreReturnValue
    public Builder eventId(String eventId) {
      this.eventId = eventId;
      return this;
    }

    public ToolContext build() {
      return new ToolContext(
          invocationContext, eventActions, functionCallId, toolConfirmation, eventId);
    }
  }
}
