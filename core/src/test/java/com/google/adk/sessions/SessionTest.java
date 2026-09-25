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
import static org.junit.Assert.assertThrows;

import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

  @Test
  public void addAndClearEvents_updatesInOrderAndImmutableEventsReturnsSnapshot() {
    Event event1 =
        Event.builder().author("user").content(Content.fromParts(Part.fromText("e1"))).build();
    Event event2 =
        Event.builder().author("model").content(Content.fromParts(Part.fromText("e2"))).build();
    Event event3 =
        Event.builder().author("user").content(Content.fromParts(Part.fromText("e3"))).build();

    Session session = Session.builder("session-id").appName("test-app").userId("test-user").build();

    session.addEvent(event1);
    ImmutableList<Event> snapshotBefore = session.immutableEvents();

    session.addEvents(ImmutableList.of(event2, event3));
    ImmutableList<Event> snapshotAfterAdd = session.immutableEvents();

    assertThat(snapshotBefore).containsExactly(event1);
    assertThat(snapshotAfterAdd).containsExactly(event1, event2, event3).inOrder();

    session.clearEvents();
    assertThat(session.immutableEvents()).isEmpty();
    assertThat(snapshotAfterAdd).containsExactly(event1, event2, event3).inOrder();
  }

  @Test
  public void builder_nullEvents_initializesEmptyNonNullEventsList() {
    Session session = Session.builder("session-id").events(null).build();
    Event event =
        Event.builder().author("user").content(Content.fromParts(Part.fromText("e1"))).build();

    session.addEvent(event);
    assertThat(session.immutableEvents()).containsExactly(event);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing internal Builder.eventsView seam.
  public void helpers_synchronizeOnBackingEventsList() {
    List<String> lockedOps = new ArrayList<>();
    List<Event> lockCheckingList =
        new ArrayList<>() {
          private void requireMonitorHeld(String op) {
            if (!Thread.holdsLock(this)) {
              throw new IllegalStateException("Monitor on backing events list not held for " + op);
            }
            lockedOps.add(op);
          }

          @Override
          public Object[] toArray() {
            requireMonitorHeld("toArray");
            return super.toArray();
          }

          @Override
          public <T> T[] toArray(T[] a) {
            requireMonitorHeld("toArray");
            return super.toArray(a);
          }

          @Override
          public boolean add(Event e) {
            requireMonitorHeld("add");
            return super.add(e);
          }

          @Override
          public boolean addAll(Collection<? extends Event> c) {
            requireMonitorHeld("addAll");
            return super.addAll(c);
          }

          @Override
          public void clear() {
            requireMonitorHeld("clear");
            super.clear();
          }
        };

    assertThrows(IllegalStateException.class, lockCheckingList::toArray);

    Session session =
        Session.builder("session-id")
            .appName("test-app")
            .userId("test-user")
            .eventsView(lockCheckingList)
            .build();

    Event event1 =
        Event.builder().author("user").content(Content.fromParts(Part.fromText("e1"))).build();
    Event event2 =
        Event.builder().author("model").content(Content.fromParts(Part.fromText("e2"))).build();

    session.addEvent(event1);
    session.addEvents(ImmutableList.of(event2));
    assertThat(session.immutableEvents()).containsExactly(event1, event2).inOrder();

    session.clearEvents();
    assertThat(session.immutableEvents()).isEmpty();

    assertThat(lockedOps).containsExactly("add", "addAll", "toArray", "clear", "toArray").inOrder();
  }

  @Test
  public void jsonRoundTrip_doesNotIncludeImmutableEventsProperty() {
    Event event1 =
        Event.builder().author("user").content(Content.fromParts(Part.fromText("hi"))).build();
    Session session = Session.builder("session-id").appName("test-app").userId("test-user").build();
    session.addEvent(event1);

    String json = session.toJson();
    assertThat(json).doesNotContain("immutableEvents");

    Session deserialized = Session.fromJson(json);
    assertThat(deserialized.immutableEvents()).hasSize(1);
    assertThat(deserialized.immutableEvents().get(0).author()).isEqualTo("user");
  }
}
