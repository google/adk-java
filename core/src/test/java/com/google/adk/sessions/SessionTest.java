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

package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SessionTest {

  @Test
  public void builder_events_createsMutableCopy() {
    Event event1 =
        Event.builder().author("user").content(Content.fromParts(Part.fromText("hi"))).build();
    Event event2 =
        Event.builder().author("model").content(Content.fromParts(Part.fromText("hello"))).build();
    ImmutableList<Event> immutableList = ImmutableList.of(event1);

    Session session =
        Session.builder("session-id")
            .appName("test-app")
            .userId("test-user")
            .events(immutableList)
            .build();

    // Verify we can add to the list
    session.events().add(event2);

    assertThat(session.events()).containsExactly(event1, event2).inOrder();
  }
}
