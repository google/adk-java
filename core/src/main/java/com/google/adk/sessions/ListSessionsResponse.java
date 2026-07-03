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

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** Response for listing sessions. */
@AutoValue
public abstract class ListSessionsResponse {

  public abstract ImmutableList<Session> sessions();

  public List<String> sessionIds() {
    return sessions().stream().map(Session::id).collect(toImmutableList());
  }

  /** Builder for {@link ListSessionsResponse}. */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder sessions(List<Session> sessions);

    public abstract ListSessionsResponse build();
  }

  public static Builder builder() {
    return new AutoValue_ListSessionsResponse.Builder().sessions(ImmutableList.of());
  }
}
