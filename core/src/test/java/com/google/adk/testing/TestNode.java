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
import com.google.adk.workflow.Node;
import io.reactivex.rxjava3.core.Flowable;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/** A workflow node whose run is given by a function, so a test can declare its node inline. */
public record TestNode(String name, BiFunction<Context, @Nullable Object, Flowable<?>> body)
    implements Node {

  /** Returns a node named {@code name} that emits nothing. */
  public static TestNode of(String name) {
    return new TestNode(name, (context, nodeInput) -> Flowable.empty());
  }

  @Override
  public Flowable<?> runNode(Context context, @Nullable Object nodeInput) {
    return body.apply(context, nodeInput);
  }
}
