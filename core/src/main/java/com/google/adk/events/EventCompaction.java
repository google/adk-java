package com.google.adk.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.auto.value.AutoValue;
import com.google.genai.types.Content;

/** The compaction of the events. */
@AutoValue
@JsonDeserialize(builder = EventCompaction.Builder.class)
public abstract class EventCompaction {

  @JsonProperty("startTimestamp")
  public abstract long startTimestamp();

  @JsonProperty("endTimestamp")
  public abstract long endTimestamp();

  @JsonProperty("compactedContent")
  public abstract Content compactedContent();

  public static Builder builder() {
    return new AutoValue_EventCompaction.Builder();
  }

  /** Builder for {@link EventCompaction}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @JsonCreator
    static Builder create() {
      return builder();
    }

    @JsonProperty("startTimestamp")
    public abstract Builder startTimestamp(long startTimestamp);

    @JsonProperty("endTimestamp")
    public abstract Builder endTimestamp(long endTimestamp);

    @JsonProperty("compactedContent")
    public abstract Builder compactedContent(Content compactedContent);

    public abstract EventCompaction build();
  }
}
