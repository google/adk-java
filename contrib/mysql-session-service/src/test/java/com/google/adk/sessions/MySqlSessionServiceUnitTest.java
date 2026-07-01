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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link MySqlSessionService}. These tests use a mock {@link MySqlDBHelper} and do
 * not require a running database.
 */
class MySqlSessionServiceUnitTest {

  private MySqlDBHelper mockDbHelper;
  private MySqlSessionService sessionService;

  @BeforeEach
  void setUp() {
    mockDbHelper = mock(MySqlDBHelper.class);
    sessionService = new MySqlSessionService(mockDbHelper);
  }

  @Test
  void createSession_validInput_delegatesToDbHelper() {
    String appName = "testApp";
    String userId = "testUser";

    when(mockDbHelper.saveSession(any(Session.class))).thenReturn(Completable.complete());
    when(mockDbHelper.getInitialState(anyString(), anyString()))
        .thenReturn(Single.just(new ConcurrentHashMap<>()));

    sessionService.createSession(appName, userId, null, null).blockingGet();

    verify(mockDbHelper).saveSession(any(Session.class));
    verify(mockDbHelper).getInitialState(eq(appName), eq(userId));
  }

  @Test
  void createSession_mergesGlobalState() {
    String appName = "testApp";
    String userId = "testUser";

    // Global state
    ConcurrentHashMap<String, Object> globalState = new ConcurrentHashMap<>();
    globalState.put("_user_pref", "dark_mode");
    globalState.put("shared_key", "global_value");

    // Initial Session state
    ConcurrentHashMap<String, Object> initialSessionState = new ConcurrentHashMap<>();
    initialSessionState.put("shared_key", "session_value");

    when(mockDbHelper.saveSession(any(Session.class))).thenReturn(Completable.complete());
    when(mockDbHelper.getInitialState(anyString(), anyString()))
        .thenReturn(Single.just(globalState));

    Session session =
        sessionService.createSession(appName, userId, initialSessionState, null).blockingGet();

    // Verify global only key is present
    assertThat(session.state()).containsEntry("_user_pref", "dark_mode");
    // Verify session key overrides global key
    assertThat(session.state()).containsEntry("shared_key", "session_value");

    // Verify that saveSession was called WITH the merged state
    ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
    verify(mockDbHelper).saveSession(sessionCaptor.capture());
    Session savedSession = sessionCaptor.getValue();
    assertThat(savedSession.state()).containsEntry("_user_pref", "dark_mode");
    assertThat(savedSession.state()).containsEntry("shared_key", "session_value");
  }

  @Test
  void getSession_validInput_delegatesToDbHelper() {
    when(mockDbHelper.getSession(anyString(), anyString(), anyString(), any()))
        .thenReturn(io.reactivex.rxjava3.core.Maybe.empty());

    sessionService.getSession("app", "user", "session1", java.util.Optional.empty());
    verify(mockDbHelper).getSession("app", "user", "session1", java.util.Optional.empty());
  }
}
