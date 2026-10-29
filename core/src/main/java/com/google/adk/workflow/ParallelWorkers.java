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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.Context;
import com.google.adk.annotations.Experimental;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.jspecify.annotations.Nullable;

/**
 * Static factories for workflow nodes that run a node once per item of a list input. Each item runs
 * as a child of the returned node on its own branch, and the node outputs the items' results as a
 * list in input order. A failed item cancels the others and fails the node, while an item that asks
 * for input stops queued items from starting.
 */
@Experimental
public final class ParallelWorkers {

  /**
   * Returns a node that runs {@code node} on every item at once. Otherwise it behaves as {@link
   * #parallelNode(Node, int)} describes.
   *
   * @throws WorkflowConfigurationException if {@code node} is {@link Start}
   */
  public static Node parallelNode(Node node) {
    return parallelNode(node, Integer.MAX_VALUE);
  }

  /**
   * Returns a node that runs {@code node} on each item of a list input, at most {@code
   * maxParallelWorkers} at a time and starting them in input order; the returned node takes {@code
   * node}'s name, description, state schema and {@link Node#requiresAllPredecessors()}, and leaves
   * its config and input and output schemas to each item's run. A text-only {@link Content}, such
   * as a user message, is read as JSON, and a JSON list fans out. Its text reaches the items as a
   * {@code String} if it is not JSON, or if it is a non-list value and the item's input schema
   * takes a string; any other non-list input runs as one item.
   *
   * @throws WorkflowConfigurationException if {@code node} is {@link Start} or {@code
   *     maxParallelWorkers} is less than 1
   */
  public static Node parallelNode(Node node, int maxParallelWorkers) {
    if (node instanceof Start) {
      throw new WorkflowConfigurationException("parallelNode cannot wrap Start.");
    }
    if (maxParallelWorkers < 1) {
      throw new WorkflowConfigurationException(
          String.format("maxParallelWorkers must be at least 1, but was %s.", maxParallelWorkers));
    }
    return new ParallelWorkerNode(node, maxParallelWorkers);
  }

  private static final class ParallelWorkerNode extends BaseNode {

    private static final ObjectReader JSON_READER =
        JsonBaseModel.getMapper()
            .readerFor(Object.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    // No run ID: items subscribe in input order, so each gets the same generated ID on resume.
    private static final RunNodeConfig ITEM_CONFIG =
        RunNodeConfig.builder().useSubBranch(true).build();

    private final Node worker;
    private final int maxParallelWorkers;

    ParallelWorkerNode(Node worker, int maxParallelWorkers) {
      super(
          worker.name(),
          worker.description(),
          /* rerunOnResume= */ true,
          /* waitForOutput= */ false,
          NodeConfig.builder().build(),
          /* inputSchema= */ null,
          /* outputSchema= */ null,
          worker.stateSchema().orElse(null));
      this.worker = worker;
      this.maxParallelWorkers = maxParallelWorkers;
    }

    @Override
    public boolean requiresAllPredecessors() {
      return worker.requiresAllPredecessors();
    }

    @Override
    public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
      return Flowable.defer(
          () -> {
            Object input = nodeInput instanceof Content content ? unwrap(content) : nodeInput;
            List<?> items = input instanceof List<?> list ? list : Collections.singletonList(input);
            return new FanOut(context, items).run();
          });
    }

    /**
     * Returns the JSON value of text-only {@code content}, or its text if that is not JSON or is a
     * non-list value for an item that takes a string; any other content is returned as it is.
     */
    private @Nullable Object unwrap(Content content) {
      List<Part> parts = content.parts().orElse(ImmutableList.of());
      if (parts.isEmpty() || parts.stream().anyMatch(part -> part.text().isEmpty())) {
        return content;
      }
      String text = content.text();
      Object json;
      try {
        json = JSON_READER.readValue(text);
      } catch (JsonProcessingException e) {
        return text;
      }
      if (json instanceof List<?> list) {
        return list;
      }
      return worker.inputSchema().map(ParallelWorkerNode::acceptsString).orElse(false)
          ? text
          : json;
    }

    private static boolean acceptsString(Schema schema) {
      return schema.type().map(type -> type.knownEnum() == Type.Known.STRING).orElse(false)
          || schema.anyOf().orElse(ImmutableList.of()).stream()
              .anyMatch(ParallelWorkerNode::acceptsString);
    }

    private final class FanOut {
      private final Context context;
      private final List<?> items;
      // Written by the item runs, which may finish on different threads.
      private final AtomicReferenceArray<Object> outputs;
      private final AtomicReferenceArray<DynamicNodeFailedException> failures;
      private final AtomicBoolean interrupted = new AtomicBoolean();

      FanOut(Context context, List<?> items) {
        this.context = context;
        this.items = items;
        this.outputs = new AtomicReferenceArray<>(items.size());
        this.failures = new AtomicReferenceArray<>(items.size());
      }

      Flowable<?> run() {
        return Flowable.range(0, items.size())
            // An interrupt stops only the items that had to wait for a slot.
            .takeWhile(index -> index < maxParallelWorkers || !interrupted.get())
            .flatMapSingle(this::runItem, /* delayErrors= */ false, maxParallelWorkers)
            // A failed item stops the rest; the typed parameter rules out takeUntil(Publisher).
            .takeUntil((Boolean failed) -> failed)
            .ignoreElements()
            .andThen(Flowable.defer(this::finish));
      }

      /** Runs one item, or replays its result on resume, and emits whether it failed. */
      private Single<Boolean> runItem(int index) {
        return context
            .runNode(worker, items.get(index), ITEM_CONFIG)
            .doOnSuccess(output -> outputs.set(index, output))
            .ignoreElement()
            .toSingleDefault(false)
            .onErrorResumeNext(error -> settle(index, error));
      }

      /**
       * Records a failure so the lowest index wins a race, and keeps an interrupt from ending the
       * rest.
       */
      private Single<Boolean> settle(int index, Throwable error) {
        if (error instanceof DynamicNodeFailedException failure) {
          failures.set(index, failure);
          return Single.just(true);
        }
        if (error instanceof NodeInterruptedException) {
          // The runtime has already added the item's interrupt IDs to this node.
          interrupted.set(true);
          return Single.just(false);
        }
        return Single.error(error);
      }

      /**
       * Fails with the lowest-index failure, or else waits with the items that asked for input, or
       * else outputs the items' results. Each failure's error event stays at its item.
       */
      private Flowable<?> finish() {
        List<@Nullable Object> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
          DynamicNodeFailedException failure = failures.get(i);
          if (failure != null) {
            return Flowable.error(failure);
          }
          results.add(outputs.get(i));
        }
        if (interrupted.get()) {
          return Flowable.error(new NodeInterruptedException());
        }
        return Flowable.just(Collections.unmodifiableList(results));
      }
    }
  }

  private ParallelWorkers() {}
}
