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

import com.fasterxml.jackson.databind.JsonNode;
import com.google.adk.JsonBaseModel;
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
    assertThat(request.partial()).hasValue(false);
  }

  @Test
  public void content_withPartialTrue_preservesValue() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> subscriber = queue.get().test();

    queue.content(CONTENT, /* partial= */ true);

    subscriber.assertValueCount(1);
    LiveRequest request = subscriber.values().get(0);
    assertThat(request.content()).hasValue(CONTENT);
    assertThat(request.partial()).hasValue(true);
  }

  @Test
  public void get_keepsRequestsNotTakenForTheNextSubscriber() {
    LiveRequestQueue queue = new LiveRequestQueue();
    TestSubscriber<LiveRequest> first = queue.get().test(1);
    // More than a busy subscriber consumes, and more than the default buffer size.
    for (int i = 0; i < 300; i++) {
      queue.content(Content.fromParts(Part.fromText("request " + i)));
    }

    first.cancel();
    TestSubscriber<LiveRequest> next = queue.get().test();

    first.assertValueCount(1).assertNoErrors();
    next.assertNoErrors();
    assertThat(next.values()).hasSize(299);
    assertThat(next.values().get(0).content().get().text()).isEqualTo("request 1");
  }

  @Test
  public void liveRequest_jsonRoundTrip_preservesPartialTrue() {
    LiveRequest request = LiveRequest.builder().content(CONTENT).partial(true).build();

    JsonNode json = JsonBaseModel.getMapper().valueToTree(request);
    LiveRequest restored = LiveRequest.fromJsonString(request.toJson());

    assertThat(json.get("partial").booleanValue()).isTrue();
    assertThat(restored.content()).hasValue(CONTENT);
    assertThat(restored.partial()).hasValue(true);
  }

  @Test
  public void liveRequest_jsonRoundTrip_preservesPartialFalse() {
    LiveRequest request = LiveRequest.builder().content(CONTENT).partial(false).build();

    JsonNode json = JsonBaseModel.getMapper().valueToTree(request);
    LiveRequest restored = LiveRequest.fromJsonString(request.toJson());

    assertThat(json.get("partial").booleanValue()).isFalse();
    assertThat(restored.content()).hasValue(CONTENT);
    assertThat(restored.partial()).hasValue(false);
  }

  @Test
  public void liveRequest_jsonWithoutPartial_defaultsToNonPartial() {
    LiveRequest request =
        LiveRequest.fromJsonString(
            "{\"content\":{\"role\":\"user\",\"parts\":[{\"text\":\"context\"}]}}");

    assertThat(request.content()).hasValue(CONTENT);
    assertThat(request.partial()).hasValue(false);
  }

  @Test
  public void liveRequest_toBuilder_preservesPartial() {
    LiveRequest request = LiveRequest.builder().content(CONTENT).partial(true).build();

    assertThat(request.toBuilder().build().partial()).hasValue(true);
  }
}
