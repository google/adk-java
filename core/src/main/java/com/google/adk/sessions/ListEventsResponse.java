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

package com.google.adk.sessions;

import com.google.adk.events.Event;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;

/** Response for listing events. */
@AutoValue
public abstract class ListEventsResponse {

  public abstract ImmutableList<Event> events();

  public abstract Optional<String> nextPageToken();

  /** Builder for {@link ListEventsResponse}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder events(List<Event> events);

    public abstract Builder nextPageToken(String nextPageToken);

    public abstract ListEventsResponse build();
  }

  public static Builder builder() {
    return new AutoValue_ListEventsResponse.Builder().events(ImmutableList.of());
  }
}
