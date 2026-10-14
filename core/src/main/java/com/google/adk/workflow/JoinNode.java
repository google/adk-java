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

package com.google.adk.workflow;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.adk.agents.Context;
import com.google.adk.annotations.Experimental;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Flowable;
import org.jspecify.annotations.Nullable;

/**
 * A node that waits for every predecessor to complete, then outputs their outputs keyed by
 * predecessor name. Route each predecessor into the join every time it completes: one that routes
 * elsewhere still counts as complete, and if it completes last, the join never runs.
 */
@Experimental
public final class JoinNode extends BaseNode {

  private JoinNode(Builder builder) {
    super(
        builder.name,
        builder.description,
        /* rerunOnResume= */ false,
        /* waitForOutput= */ false,
        NodeConfig.builder().build(),
        builder.inputSchema,
        builder.outputSchema,
        /* stateSchema= */ null);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public boolean requiresAllPredecessors() {
    return true;
  }

  /** Outputs its input unchanged, or nothing when the input is null. */
  @Override
  public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
    return nodeInput == null ? Flowable.empty() : Flowable.just(nodeInput);
  }

  /** Builder for {@link JoinNode}. Only the name is required. */
  public static final class Builder {
    private @Nullable String name;
    private String description = "";
    private @Nullable Schema inputSchema;
    private @Nullable Schema outputSchema;

    private Builder() {}

    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = checkNotNull(name);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder description(String description) {
      this.description = checkNotNull(description);
      return this;
    }

    /** Sets the schema that each predecessor's output must match on its own, not the joined map. */
    @CanIgnoreReturnValue
    public Builder inputSchema(@Nullable Schema inputSchema) {
      this.inputSchema = inputSchema;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder outputSchema(@Nullable Schema outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

    public JoinNode build() {
      checkState(name != null, "JoinNode.Builder requires name to be set.");
      return new JoinNode(this);
    }
  }
}
