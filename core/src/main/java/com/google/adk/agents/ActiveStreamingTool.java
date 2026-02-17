/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may not use this file except in compliance with the License.
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

package com.google.adk.agents;

import io.reactivex.rxjava3.disposables.Disposable;
import javax.annotation.Nullable;

/** Manages streaming tool related resources during invocation. */
public class ActiveStreamingTool {
  @Nullable private Disposable task;
  @Nullable private LiveRequestQueue stream;

  public ActiveStreamingTool(Disposable task) {
    this(task, null);
  }

  public ActiveStreamingTool(LiveRequestQueue stream) {
    this(null, stream);
  }

  public ActiveStreamingTool(Disposable task, LiveRequestQueue stream) {
    this.task = task;
    this.stream = stream;
  }

  public ActiveStreamingTool() {}

  /** Returns the active task of this streaming tool. */
  @Nullable
  public Disposable task() {
    return task;
  }

  /** Sets the active task of this streaming tool. */
  public void task(@Nullable Disposable task) {
    this.task = task;
  }

  /** Returns the active stream of this streaming tool. */
  @Nullable
  public LiveRequestQueue stream() {
    return stream;
  }

  /** Sets the active stream of this streaming tool. */
  public void stream(@Nullable LiveRequestQueue stream) {
    this.stream = stream;
  }
}
