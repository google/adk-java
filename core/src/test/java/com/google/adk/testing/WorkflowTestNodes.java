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

import com.google.adk.agents.Context;
import com.google.adk.events.Event;
import com.google.adk.workflow.Edge;
import com.google.adk.workflow.Node;
import com.google.adk.workflow.Workflow;
import com.google.common.collect.ImmutableSet;
import io.reactivex.rxjava3.core.Flowable;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/** Workflows and workflow nodes with common test behaviors, so a test can declare them inline. */
public final class WorkflowTestNodes {

  /** Returns a workflow named {@code wf} that runs {@code edges}. */
  public static Workflow workflow(Edge... edges) {
    return workflow("wf", edges);
  }

  /** Returns a workflow named {@code name} that runs {@code edges}. */
  public static Workflow workflow(String name, Edge... edges) {
    return Workflow.builder().name(name).edges(edges).build();
  }

  /**
   * Returns a node that runs {@code body} and reruns on resume, as required for nodes that run
   * child nodes.
   */
  public static Node dispatcher(
      String name, BiFunction<Context, @Nullable Object, Flowable<?>> body) {
    return new DispatchingNode(name, body);
  }

  /** Returns a node that emits {@code output}. */
  public static Node emitter(String name, Object output) {
    return new TestNode(name, (ctx, input) -> Flowable.just(output));
  }

  /** Returns a node that outputs its input prefixed with {@code prefix} and a colon. */
  public static Node echo(String name, String prefix) {
    return new TestNode(name, (ctx, input) -> Flowable.just(prefix + ":" + input));
  }

  /** Returns a node that fails with {@code failure}. */
  public static Node throwing(String name, Exception failure) {
    return new TestNode(name, (ctx, input) -> Flowable.error(failure));
  }

  /** Returns a node that emits {@code output} and then fails. */
  public static Node emitThenFail(String name, Object output) {
    return new TestNode(
        name,
        (ctx, input) ->
            Flowable.<Object>just(output)
                .concatWith(Flowable.error(new IllegalStateException("failed after its output"))));
  }

  /** Returns a node that waits on the long-running tool call {@code interruptId}. */
  public static Node interrupting(String name, String interruptId) {
    return new TestNode(
        name,
        (ctx, input) ->
            Flowable.just(
                Event.builder()
                    .id(Event.generateEventId())
                    .author("")
                    .longRunningToolIds(ImmutableSet.of(interruptId))
                    .build()));
  }

  /** Returns a node that waits for an output and produces none. */
  public static Node waitingForOutput(String name) {
    return new WaitingNode(name);
  }

  private record DispatchingNode(
      String name, BiFunction<Context, @Nullable Object, Flowable<?>> body) implements Node {

    @Override
    public boolean rerunOnResume() {
      return true;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return body.apply(context, nodeInput);
    }
  }

  private record WaitingNode(String name) implements Node {

    @Override
    public boolean waitForOutput() {
      return true;
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.empty();
    }
  }

  private WorkflowTestNodes() {}
}
