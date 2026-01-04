package com.google.adk.a2a.converters;

import com.google.adk.events.Event;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Optional;

/**
 * Preprocesses a batch of ADK events prior to invoking a remote A2A agent.
 *
 * <p>The class splits the conversation into two logical buckets:
 *
 * <ul>
 *   <li>The historical session events that should be preserved as-is when relayed over the wire.
 *   <li>The most recent user-authored text event, surfaced separately so it can be supplied as the
 *       pending user input on the {@link com.google.adk.agents.InvocationContext}.
 * </ul>
 *
 * <p>This mirrors the Python A2A implementation where the in-flight user message is maintained
 * separately from the persisted transcript.
 *
 * <p>**EXPERIMENTAL:** Subject to change, rename, or removal in any future patch release. Do not
 * use in production code.
 */
public final class ConversationPreprocessor {

  /**
   * Immutable value that surfaces the results of preprocessing.
   *
   * <p>All fields are deliberately exposed to avoid additional AutoValue dependencies in this
   * internal module.
   */
  public static final class PreparedInput {
    /** Historical events that should remain in the session transcript. */
    public final ImmutableList<Event> historyEvents;

    /** Extracted user message content, if a qualifying text event was found. */
    public final Optional<Content> userContent;

    /** The concrete event that supplied {@link #userContent}, for callers needing metadata. */
    public final Optional<Event> userEvent;

    /**
     * Creates a new instance.
     *
     * @param historyEvents ordered historical events retained in the session stream
     * @param userContent optional content to place on the pending user message
     * @param userEvent optional original event that contained {@code userContent}
     */
    public PreparedInput(
        ImmutableList<Event> historyEvents,
        Optional<Content> userContent,
        Optional<Event> userEvent) {
      this.historyEvents = historyEvents;
      this.userContent = userContent;
      this.userEvent = userEvent;
    }
  }

  private ConversationPreprocessor() {}

  /**
   * Splits the provided event list into history and the latest user-authored text message.
   *
   * @param inputEvents ordered session events, oldest to newest; may be {@code null}
   * @return container encapsulating the derived history, optional user content, and the original
   *     user event when present
   */
  public static PreparedInput extractHistoryAndUserContent(List<Event> inputEvents) {
    if (inputEvents == null || inputEvents.isEmpty()) {
      return new PreparedInput(ImmutableList.of(), Optional.empty(), Optional.empty());
    }

    Content userContent = null;
    int lastTextIndex = -1;
    Event userEvent = null;
    for (int i = inputEvents.size() - 1; i >= 0; i--) {
      Event ev = inputEvents.get(i);
      if (ev.content().isPresent() && ev.content().get().parts().isPresent()) {
        boolean hasText = false;
        for (Part p : ev.content().get().parts().get()) {
          if (p.text().isPresent()) {
            hasText = true;
            break;
          }
        }
        if (hasText) {
          userContent = ev.content().get();
          lastTextIndex = i;
          userEvent = ev;
          break;
        }
      }
    }

    ImmutableList.Builder<Event> historyBuilder = ImmutableList.builder();
    for (int i = 0; i < inputEvents.size(); i++) {
      if (i != lastTextIndex) {
        historyBuilder.add(inputEvents.get(i));
      }
    }

    return new PreparedInput(
        historyBuilder.build(), Optional.ofNullable(userContent), Optional.ofNullable(userEvent));
  }
}
