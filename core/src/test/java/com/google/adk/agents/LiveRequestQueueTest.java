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

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LiveRequestQueueTest {

  private static final Content CONTENT = Content.fromParts(Part.fromText("context"));

  @Test
  public void content_defaultsToCompletingTurn() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> subscriber = queue.get().test();

    queue.content(CONTENT);

    subscriber.assertValueCount(1);
    LiveRequest request = subscriber.values().get(0);
    assertThat(request.content()).hasValue(CONTENT);
    assertThat(request.turnComplete()).hasValue(true);
  }

  @Test
  public void content_withTurnCompleteFalse_preservesValue() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> subscriber = queue.get().test();

    queue.content(CONTENT, false);

    subscriber.assertValueCount(1);
    LiveRequest request = subscriber.values().get(0);
    assertThat(request.content()).hasValue(CONTENT);
    assertThat(request.turnComplete()).hasValue(false);
  }

  @Test
  public void liveRequest_jsonRoundTrip_preservesTurnComplete() {
    LiveRequest request = LiveRequest.builder().content(CONTENT).turnComplete(false).build();

    LiveRequest restored = LiveRequest.fromJsonString(request.toJson());

    assertThat(restored.content()).hasValue(CONTENT);
    assertThat(restored.turnComplete()).hasValue(false);
  }
}
