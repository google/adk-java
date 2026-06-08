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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Unit tests for {@link CallerStateGuard}. */
public class CallerStateGuardTest {

  @Test
  public void validateCallerState_nullState_doesNotThrow() {
    CallerStateGuard.validateCallerState(null);
  }

  @Test
  public void validateCallerState_emptyState_doesNotThrow() {
    CallerStateGuard.validateCallerState(ImmutableMap.of());
  }

  @Test
  public void validateCallerState_plainSessionScopedKey_doesNotThrow() {
    CallerStateGuard.validateCallerState(ImmutableMap.of("userContext", "Alice"));
  }

  @Test
  public void validateCallerState_tempPrefix_doesNotThrow() {
    CallerStateGuard.validateCallerState(ImmutableMap.of("temp:scratch", "x"));
  }

  @Test
  public void validateCallerState_underscorePrefixedKey_doesNotThrow() {
    CallerStateGuard.validateCallerState(ImmutableMap.of("_adk_replay_config", ImmutableMap.of()));
  }

  @Test
  public void validateCallerState_appPrefix_throwsBadRequest() {
    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () ->
                CallerStateGuard.validateCallerState(ImmutableMap.of("app:operationalScope", "x")));

    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(exception.getReason()).contains("app:operationalScope");
  }

  @Test
  public void validateCallerState_userPrefix_throwsBadRequest() {
    ResponseStatusException exception =
        assertThrows(
            ResponseStatusException.class,
            () -> CallerStateGuard.validateCallerState(ImmutableMap.of("user:role", "admin")));

    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(exception.getReason()).contains("user:role");
  }

  @Test
  public void validateCallerState_mixedValidAndScopedKeys_throwsBadRequest() {
    Map<String, Object> state = new HashMap<>();
    state.put("userContext", "Alice");
    state.put("app:scope", "everything");

    assertThrows(ResponseStatusException.class, () -> CallerStateGuard.validateCallerState(state));
  }
}
