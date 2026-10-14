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

import com.google.adk.agents.Context;
import com.google.adk.annotations.Experimental;
import io.reactivex.rxjava3.core.Flowable;
import org.jspecify.annotations.Nullable;

/**
 * The node that marks where a workflow graph starts. It never runs: the workflow passes its own
 * input to the nodes that {@code Start} has edges to.
 */
@Experimental
public final class Start implements Node {

  /** The start node that every graph uses. */
  public static final Start INSTANCE = new Start();

  /** The reserved name of the start node. */
  static final String NAME = "__START__";

  private Start() {}

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
    return Flowable.empty();
  }
}
