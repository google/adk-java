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

package com.google.adk.agents;

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.MulticastProcessor;

/** A queue of live requests to be sent to the model. */
public final class LiveRequestQueue {
  private final FlowableProcessor<LiveRequest> processor;

  public LiveRequestQueue() {
    MulticastProcessor<LiveRequest> processor = MulticastProcessor.<LiveRequest>create();
    // Unbounded, as in ADK Python, so requests waiting on a slow send are buffered rather than
    // rejected.
    processor.startUnbounded();
    this.processor = processor.toSerialized();
  }

  public void close() {
    processor.onNext(LiveRequest.builder().close(true).build());
    processor.onComplete();
  }

  public void content(Content content) {
    content(content, /* partial= */ false);
  }

  /**
   * Sends content to the model, optionally as a partial update that leaves the current turn open.
   *
   * @param content the content to send
   * @param partial whether this content is a partial update that does not complete the current turn
   */
  public void content(Content content, boolean partial) {
    processor.onNext(LiveRequest.builder().content(content).partial(partial).build());
  }

  public void realtime(Blob blob) {
    processor.onNext(LiveRequest.builder().blob(blob).build());
  }

  public void send(LiveRequest request) {
    processor.onNext(request);
    if (request.shouldClose()) {
      processor.onComplete();
    }
  }

  public Flowable<LiveRequest> get() {
    return processor;
  }
}
