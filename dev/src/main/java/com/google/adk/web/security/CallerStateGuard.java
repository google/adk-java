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

package com.google.adk.web.security;

import com.google.adk.sessions.State;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates caller-supplied session state received over the development web API.
 *
 * <p>Keys prefixed with {@link State#APP_PREFIX} or {@link State#USER_PREFIX} are stored in app- or
 * user-scoped state that is shared across sessions. Such keys are rejected when supplied by an HTTP
 * caller, so the development server cannot be used to write cross-session state from external
 * input. Programmatic callers using the session APIs directly are unaffected.
 */
public final class CallerStateGuard {

  private CallerStateGuard() {}

  /**
   * Rejects caller-supplied state containing cross-session ({@code app:}/{@code user:}) keys.
   *
   * @param state caller-supplied state map (may be {@code null} or empty)
   * @throws ResponseStatusException with {@link HttpStatus#BAD_REQUEST} if a disallowed key is
   *     found
   */
  public static void validateCallerState(Map<String, Object> state) {
    if (state == null || state.isEmpty()) {
      return;
    }
    for (String key : state.keySet()) {
      if (key == null) {
        continue;
      }
      if (key.startsWith(State.APP_PREFIX) || key.startsWith(State.USER_PREFIX)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Caller-supplied state may not write the key '"
                + key
                + "'. Keys prefixed with '"
                + State.APP_PREFIX
                + "' or '"
                + State.USER_PREFIX
                + "' are shared across sessions and cannot be set via the development web API.");
      }
    }
  }
}
