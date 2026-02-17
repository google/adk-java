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
package com.google.adk.plugins;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Maybe;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plugin that filters the LLM request {@link Content} list to reduce its size, for example to
 * adhere to context window limits.
 *
 * <p>This plugin can be configured to trim the conversation history based on one or both of the
 * following criteria:
 *
 * <ul>
 *   <li><b>{@code numInvocationsToKeep(N)}:</b> Retains only the last {@code N} model turns and any
 *       preceding user turns. If multiple user messages appear consecutively before a model
 *       message, all of them are kept as part of that model invocation window.
 *   <li><b>{@code customFilter()}:</b> Applies a custom {@link UnaryOperator} to filter the list of
 *       {@link Content} objects. If {@code numInvocationsToKeep} is also specified, the custom
 *       filter is applied <i>after</i> the invocation-based trimming occurs.
 * </ul>
 *
 * <p><b>Function Call Handling:</b> The plugin ensures that if a {@link FunctionResponse} is
 * included in the filtered list, its corresponding {@link FunctionCall} is also included. If
 * filtering would otherwise exclude the {@link FunctionCall}, the window is automatically expanded
 * to include it, preventing orphaned function responses.
 *
 * <p>If no filtering options are provided, this plugin has no effect. If the {@code customFilter}
 * throws an exception during execution, filtering is aborted, and the {@link LlmRequest} is not
 * modified.
 */
public class ContextFilterPlugin extends BasePlugin {
  private static final Logger logger = LoggerFactory.getLogger(ContextFilterPlugin.class);
  private static final String MODEL_ROLE = "model";
  private static final String USER_ROLE = "user";

  private final Optional<Integer> numInvocationsToKeep;
  private final Optional<UnaryOperator<List<Content>>> customFilter;

  protected ContextFilterPlugin(Builder builder) {
    super(builder.name);
    this.numInvocationsToKeep = builder.numInvocationsToKeep;
    this.customFilter = builder.customFilter;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link ContextFilterPlugin}. */
  public static class Builder {
    private Optional<Integer> numInvocationsToKeep = Optional.empty();
    private Optional<UnaryOperator<List<Content>>> customFilter = Optional.empty();
    private String name = "context_filter_plugin";

    @CanIgnoreReturnValue
    public Builder numInvocationsToKeep(int numInvocationsToKeep) {
      checkArgument(numInvocationsToKeep > 0, "numInvocationsToKeep must be positive");
      this.numInvocationsToKeep = Optional.of(numInvocationsToKeep);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder customFilter(UnaryOperator<List<Content>> customFilter) {
      this.customFilter = Optional.of(customFilter);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public ContextFilterPlugin build() {
      return new ContextFilterPlugin(this);
    }
  }

  /**
   * Filters the LLM request context by trimming recent turns and applying any custom filter.
   *
   * <p>If {@code numInvocationsToKeep} is set, this method retains only the most recent model turns
   * and their preceding user turns. It ensures that function calls and responses remain paired. If
   * a {@code customFilter} is provided, it is applied to the list after trimming.
   *
   * @param callbackContext The context of the callback.
   * @param llmRequest The request builder whose contents will be updated in place.
   * @return {@link Maybe#empty()} as this plugin only modifies the request builder.
   */
  @Override
  public Maybe<LlmResponse> beforeModelCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest) {
    try {
      List<Content> contents = llmRequest.build().contents();
      if (contents == null || contents.isEmpty()) {
        return Maybe.empty();
      }

      List<Content> effectiveContents = new ArrayList<>(contents);

      if (numInvocationsToKeep.isPresent()) {
        effectiveContents =
            trimContentsByInvocations(numInvocationsToKeep.get(), effectiveContents);
      }

      if (customFilter.isPresent()) {
        effectiveContents = customFilter.get().apply(effectiveContents);
      }

      llmRequest.contents(effectiveContents);
    } catch (RuntimeException e) {
      logger.error("Failed to reduce context for request", e);
    }

    return Maybe.empty();
  }

  private List<Content> trimContentsByInvocations(int numInvocations, List<Content> contents) {
    // If the number of model turns is within limits, no trimming is necessary.
    long modelTurnCount =
        contents.stream().filter(c -> hasRole(c, MODEL_ROLE)).limit(numInvocations + 1).count();
    if (modelTurnCount < numInvocations + 1) {
      return contents;
    }
    int candidateSplitIndex = findNthModelTurnStartIndex(numInvocations, contents);
    // Ensure that if a function response is kept, its corresponding function call is also kept.
    int finalSplitIndex = adjustIndexForToolCalls(candidateSplitIndex, contents);
    // The Nth model turn can be preceded by user turns; expand window to include them.
    while (finalSplitIndex > 0
        && hasRole(contents.get(finalSplitIndex - 1), USER_ROLE)
        && !isFunctionResponse(contents.get(finalSplitIndex - 1))) {
      finalSplitIndex--;
    }
    return new ArrayList<>(contents.subList(finalSplitIndex, contents.size()));
  }

  private int findNthModelTurnStartIndex(int numInvocations, List<Content> contents) {
    int modelTurnsToFind = numInvocations;
    for (int i = contents.size() - 1; i >= 0; i--) {
      if (hasRole(contents.get(i), MODEL_ROLE)) {
        modelTurnsToFind--;
        if (modelTurnsToFind == 0) {
          int startIndex = i;
          // Include all preceding user messages in the same turn.
          while (startIndex > 0 && hasRole(contents.get(startIndex - 1), USER_ROLE)) {
            startIndex--;
          }
          return startIndex;
        }
      }
    }
    return 0;
  }

  /**
   * Adjusts the split index to ensure that if a {@link FunctionResponse} is included in the trimmed
   * list, its corresponding {@link FunctionCall} is also included.
   *
   * <p>This prevents orphaning function responses by expanding the conversation window backward
   * (i.e., reducing {@code splitIndex}) to include the earliest function call corresponding to any
   * function response that would otherwise be included.
   *
   * @param splitIndex The candidate index before which messages might be trimmed.
   * @param contents The full list of content messages.
   * @return An adjusted split index, guaranteed to be less than or equal to {@code splitIndex}.
   */
  private int adjustIndexForToolCalls(int splitIndex, List<Content> contents) {
    Set<String> neededCallIds = new HashSet<>();
    int finalSplitIndex = splitIndex;
    for (int i = contents.size() - 1; i >= 0; i--) {
      Optional<List<Part>> partsOptional = contents.get(i).parts();
      if (partsOptional.isPresent()) {
        for (Part part : partsOptional.get()) {
          part.functionResponse().flatMap(FunctionResponse::id).ifPresent(neededCallIds::add);
          part.functionCall().flatMap(FunctionCall::id).ifPresent(neededCallIds::remove);
        }
      }
      if (i <= finalSplitIndex && neededCallIds.isEmpty()) {
        finalSplitIndex = i;
        break;
      } else if (i == 0) {
        finalSplitIndex = 0;
      }
    }
    return finalSplitIndex;
  }

  private boolean isFunctionResponse(Content content) {
    return content
        .parts()
        .map(parts -> parts.stream().anyMatch(p -> p.functionResponse().isPresent()))
        .orElse(false);
  }

  private boolean hasRole(Content content, String role) {
    return content.role().map(r -> r.equals(role)).orElse(false);
  }
}
