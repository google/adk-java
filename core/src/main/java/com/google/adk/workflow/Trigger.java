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

import com.google.adk.annotations.Experimental;
import org.jspecify.annotations.Nullable;

/**
 * A queued reason to run a node.
 *
 * @param input the node's input: the workflow's input for a successor of the start node, the
 *     triggering node's output, or, for a node that waits for all its predecessors, their outputs
 *     keyed by node name
 * @param useSubBranch whether the node runs on its own sub-branch, as when the triggering node had
 *     more than one successor
 * @param branch the branch the node inherits instead of the workflow's branch: the triggering
 *     node's branch (which a sub-branch extends) or the branch that a join's predecessors forked
 *     from
 */
@Experimental
record Trigger(@Nullable Object input, boolean useSubBranch, @Nullable String branch) {}
