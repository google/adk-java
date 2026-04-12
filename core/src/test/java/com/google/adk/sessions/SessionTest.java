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
