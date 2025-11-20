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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.utils.Constants;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteBatch;
import com.google.cloud.firestore.WriteResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.observers.TestObserver;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Test class for {@link FirestoreSessionService}. */
@ExtendWith(MockitoExtension.class)
public class FirestoreSessionServiceTest {

  // Constants for easier testing
  private static final String APP_NAME = "test-app";
  private static final String USER_ID = "test-user-id";
  private static final String SESSION_ID = "test-session-id";
  private static final String EVENT_ID = "test-event-id";
  private static final Instant NOW = Instant.now();

  @Mock private Firestore mockDb;
  @Mock private CollectionReference mockRootCollection;
  @Mock private CollectionReference mockSessionsCollection;
  @Mock private CollectionReference mockEventsCollection;
  @Mock private CollectionReference mockAppStateCollection;
  @Mock private CollectionReference mockUserStateRootCollection;
  @Mock private CollectionReference mockUserStateUsersCollection;
  @Mock private DocumentReference mockUserDocRef;
  @Mock private DocumentReference mockSessionDocRef;
  @Mock private DocumentReference mockEventDocRef;
  @Mock private DocumentReference mockAppStateDocRef;
  @Mock private DocumentReference mockUserStateAppDocRef;
  @Mock private DocumentReference mockUserStateUserDocRef;
  @Mock private DocumentSnapshot mockSessionSnapshot;
  @Mock private QueryDocumentSnapshot mockEventSnapshot;
  @Mock private Query mockQuery;
  @Mock private QuerySnapshot mockQuerySnapshot;
  @Mock private WriteResult mockWriteResult;
  @Mock private WriteBatch mockWriteBatch;

  private FirestoreSessionService sessionService;

  /** Sets up the FirestoreSessionService and common mock behaviors before each test. */
  @BeforeEach
  public void setup() {
    sessionService = new FirestoreSessionService(mockDb);

    // Mock the chain of calls for session and event retrieval
    lenient()
        .when(mockDb.collection(Constants.ROOT_COLLECTION_NAME))
        .thenReturn(mockRootCollection);
    lenient().when(mockRootCollection.document(USER_ID)).thenReturn(mockUserDocRef);
    lenient()
        .when(mockUserDocRef.collection(Constants.SESSION_COLLECTION_NAME))
        .thenReturn(mockSessionsCollection);
    lenient()
        .when(mockSessionDocRef.collection(Constants.EVENTS_SUBCOLLECTION_NAME))
        .thenReturn(mockEventsCollection);
    lenient().when(mockEventsCollection.orderBy(Constants.KEY_TIMESTAMP)).thenReturn(mockQuery);

    // Mock state collections with distinct mocks for each collection
    lenient()
        .when(mockDb.collection(Constants.APP_STATE_COLLECTION))
        .thenReturn(mockAppStateCollection);
    lenient().when(mockAppStateCollection.document(APP_NAME)).thenReturn(mockAppStateDocRef);
    lenient()
        .when(mockDb.collection(Constants.USER_STATE_COLLECTION))
        .thenReturn(mockUserStateRootCollection);
    lenient()
        .when(mockUserStateRootCollection.document(APP_NAME))
        .thenReturn(mockUserStateAppDocRef);
    lenient()
        .when(mockUserStateAppDocRef.collection("users"))
        .thenReturn(mockUserStateUsersCollection);
    lenient()
        .when(mockUserStateUsersCollection.document(USER_ID))
        .thenReturn(mockUserStateUserDocRef);

    // Default mock for writes
    lenient()
        .when(mockSessionDocRef.set(anyMap()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));
    lenient()
        .when(mockSessionDocRef.update(anyMap()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));
    lenient()
        .when(mockSessionDocRef.update(anyString(), any()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));
    lenient()
        .when(mockSessionDocRef.delete())
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));
    lenient()
        .when(mockEventDocRef.set(anyMap()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));
    lenient()
        .when(mockAppStateDocRef.set(anyMap(), any(SetOptions.class)))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));
    lenient()
        .when(mockUserStateUserDocRef.set(anyMap(), any(SetOptions.class)))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));

    // Mock for batch operations in deleteSession
    lenient().when(mockDb.batch()).thenReturn(mockWriteBatch);
    lenient()
        .when(mockWriteBatch.commit())
        .thenReturn(ApiFutures.immediateFuture(ImmutableList.of(mockWriteResult)));
  }

  /** Tests that getSession returns the expected Session when it exists in Firestore. */
  @Test
  void getSession_sessionExists_returnsSession() {
    // Arrange
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true);
    when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef);
    when(mockSessionSnapshot.getData())
        .thenReturn(
            ImmutableMap.of(
                "id",
                SESSION_ID,
                "appName",
                APP_NAME,
                "userId",
                USER_ID,
                "updateTime",
                NOW.toString(),
                "state",
                Collections.emptyMap()));
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));
    when(mockQuerySnapshot.getDocuments()).thenReturn(Collections.emptyList());

    // Act
    TestObserver<Session> testObserver =
        sessionService.getSession(APP_NAME, USER_ID, SESSION_ID, Optional.empty()).test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertNoErrors();
    testObserver.assertValue(
        session -> {
          assertThat(session.id()).isEqualTo(SESSION_ID);
          assertThat(session.userId()).isEqualTo(USER_ID);
          return true;
        });
  }

  /** Tests that getSession emits an error when the session does not exist. */
  @Test
  void getSession_sessionDoesNotExist_emitsError() {
    // Arrange
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(false);

    // Act
    TestObserver<Session> testObserver =
        sessionService.getSession(APP_NAME, USER_ID, SESSION_ID, Optional.empty()).test();

    // Assert
    testObserver.assertError(SessionNotFoundException.class);
  }

  /** Tests that getSession returns Maybe.empty() when the session exists but has null data. */
  @Test
  void getSession_sessionExistsWithNullData_returnsEmpty() {
    // Arrange
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true);
    when(mockSessionSnapshot.getData()).thenReturn(null);

    // Act
    TestObserver<Session> testObserver =
        sessionService.getSession(APP_NAME, USER_ID, SESSION_ID, Optional.empty()).test();

    // Assert
    testObserver.assertNoErrors();
    testObserver.assertComplete();
    testObserver.assertNoValues();
  }

  /** Tests that getSession returns Maybe.empty() when the session exists but has empty data. */
  @Test
  void getSession_withNumRecentEvents_appliesLimitToQuery() {
    // Arrange
    int numEvents = 5;
    GetSessionConfig config = GetSessionConfig.builder().numRecentEvents(numEvents).build();

    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true);
    when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef);
    when(mockSessionSnapshot.getData())
        .thenReturn(
            ImmutableMap.of(
                "id", SESSION_ID,
                "appName", APP_NAME,
                "userId", USER_ID,
                "updateTime", NOW.toString(),
                "state", Collections.emptyMap()));
    when(mockQuery.limitToLast(numEvents)).thenReturn(mockQuery); // Mock the limit call
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));
    when(mockQuerySnapshot.getDocuments()).thenReturn(Collections.emptyList());

    // Act
    sessionService.getSession(APP_NAME, USER_ID, SESSION_ID, Optional.of(config)).test();

    // Assert
    verify(mockQuery).limitToLast(numEvents);
  }

  /** Tests that getSession applies the afterTimestamp filter to the events query when specified. */
  @Test
  void getSession_withAfterTimestamp_appliesFilterToQuery() {
    // Arrange
    Instant timestamp = Instant.now().minusSeconds(60);
    GetSessionConfig config = GetSessionConfig.builder().afterTimestamp(timestamp).build();

    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true);
    when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef);
    when(mockSessionSnapshot.getData()).thenReturn(ImmutableMap.of("state", Map.of()));
    when(mockQuery.whereGreaterThan(Constants.KEY_TIMESTAMP, timestamp.toString()))
        .thenReturn(mockQuery);
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));

    // Act
    sessionService.getSession(APP_NAME, USER_ID, SESSION_ID, Optional.of(config)).test();

    // Assert
    verify(mockQuery).whereGreaterThan(Constants.KEY_TIMESTAMP, timestamp.toString());
  }

  // --- createSession Tests ---

  /** Tests that createSession creates a new session with the specified session ID. */
  @Test
  void createSession_withSessionId_returnsNewSession() {
    // Arrange
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    // Act
    TestObserver<Session> testObserver =
        sessionService
            .createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), SESSION_ID)
            .test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(
        session -> {
          assertThat(session.id()).isEqualTo(SESSION_ID);
          return true;
        });
    verify(mockSessionDocRef).set(anyMap());
  }

  /** Tests that createSession creates a new session with a generated session ID. */
  @Test
  void createSession_withNullSessionId_generatesNewId() {
    // Arrange
    // Capture the dynamically generated session ID
    ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
    when(mockSessionsCollection.document(sessionIdCaptor.capture())).thenReturn(mockSessionDocRef);

    // Act
    TestObserver<Session> testObserver =
        sessionService.createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), null).test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(
        session -> {
          // Check that the returned session has the same ID that was generated
          assertThat(session.id()).isEqualTo(sessionIdCaptor.getValue());
          // Check that the generated ID looks like a UUID
          assertThat(session.id()).isNotNull();
          assertThat(session.id()).isNotEmpty();
          return true;
        });
    verify(mockSessionDocRef).set(anyMap());
  }

  /** Tests that createSession creates a new session with an empty session ID. */
  @Test
  void createSession_withEmptySessionId_generatesNewId() {
    // Arrange
    ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
    when(mockSessionsCollection.document(sessionIdCaptor.capture())).thenReturn(mockSessionDocRef);

    // Act
    TestObserver<Session> testObserver =
        sessionService.createSession(APP_NAME, USER_ID, new ConcurrentHashMap<>(), "  ").test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(
        session -> {
          assertThat(session.id()).isNotNull();
          assertThat(session.id()).isNotEmpty();
          assertThat(session.id()).isNotEqualTo("  ");
          return true;
        });
    verify(mockSessionDocRef).set(anyMap());
  }

  /** Tests that createSession creates a new session with an empty state when null state is */
  @Test
  void createSession_withNullState_createsSessionWithEmptyState() {
    // Arrange
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);

    // Act
    TestObserver<Session> testObserver =
        sessionService.createSession(APP_NAME, USER_ID, null, SESSION_ID).test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(
        session -> {
          assertThat(session.state()).isNotNull();
          assertThat(session.state()).isEmpty();
          return true;
        });
  }

  /** Tests that createSession throws NullPointerException when appName is null. */
  @Test
  void createSession_withNullAppName_throwsNullPointerException() {
    sessionService
        .createSession(null, USER_ID, null, SESSION_ID)
        .test()
        .assertError(NullPointerException.class);
  }

  // --- appendEvent Tests ---
  /** Tests that appendEvent persists the event and updates the session's updateTime. */
  @Test
  @SuppressWarnings("unchecked")
  void appendEvent_persistsEventAndUpdatesSession() {
    // Arrange
    Session session =
        Session.builder(SESSION_ID)
            .appName(APP_NAME)
            .userId(USER_ID)
            .state(new ConcurrentHashMap<>())
            .build();
    Event event =
        Event.builder()
            .author(Constants.KEY_USER)
            .content(Content.builder().parts(List.of(Part.fromText("hello world"))).build())
            .build();
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockEventsCollection.document()).thenReturn(mockEventDocRef);
    when(mockEventDocRef.getId()).thenReturn(EVENT_ID);
    when(mockEventsCollection.document(EVENT_ID)).thenReturn(mockEventDocRef);
    // Add the missing mock for the final session update call
    when(mockSessionDocRef.update(anyMap()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));

    // Act
    TestObserver<Event> testObserver = sessionService.appendEvent(session, event).test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(event);

    ArgumentCaptor<Map<String, Object>> eventCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockEventDocRef).set(eventCaptor.capture());
    assertThat(eventCaptor.getValue()).containsEntry(Constants.KEY_AUTHOR, USER_ID);
    List<String> keywords = (List<String>) eventCaptor.getValue().get("keywords");
    assertThat(keywords).containsExactly("hello", "world");
  }

  /** Tests that appendAndGet correctly serializes and deserializes events with all part types. */
  @Test
  void appendAndGet_withAllPartTypes_serializesAndDeserializesCorrectly() {
    // ARRANGE (Serialization part)
    Session session =
        Session.builder(SESSION_ID)
            .appName(APP_NAME)
            .userId(USER_ID)
            .state(new ConcurrentHashMap<>())
            .build();

    Event complexEvent =
        Event.builder()
            .author("model")
            .content(
                Content.builder()
                    .parts(
                        ImmutableList.of(
                            Part.fromText("Here are the results."),
                            Part.fromFunctionCall("search_tool", ImmutableMap.of("query", "genai")),
                            Part.fromFunctionResponse(
                                "search_tool", ImmutableMap.of("result", "Google AI")),
                            Part.fromUri("gs://bucket/file.png", "image/png")))
                    .build())
            .build();

    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockEventsCollection.document()).thenReturn(mockEventDocRef);
    when(mockEventDocRef.getId()).thenReturn(EVENT_ID);
    when(mockEventsCollection.document(EVENT_ID)).thenReturn(mockEventDocRef);
    when(mockSessionDocRef.update(anyMap()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));

    // ACT (Serialization part)
    sessionService.appendEvent(session, complexEvent).test().assertComplete();

    when(mockSessionSnapshot.getData())
        .thenReturn(
            ImmutableMap.of(
                "id", SESSION_ID,
                "appName", APP_NAME,
                "userId", USER_ID,
                "updateTime", NOW.toString(),
                "state", session.state())); // Use the session's state after appendEvent

    // ARRANGE (Deserialization part)
    ArgumentCaptor<Map<String, Object>> eventDataCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockEventDocRef).set(eventDataCaptor.capture());
    Map<String, Object> savedEventData = eventDataCaptor.getValue();

    QueryDocumentSnapshot mockComplexEventSnapshot = mock(QueryDocumentSnapshot.class);
    when(mockComplexEventSnapshot.getData()).thenReturn(savedEventData);

    // Set up mocks for the getSession call right before it's used.
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockComplexEventSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true); // This was the missing mock
    when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef); // This is the fix
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockEventsCollection.orderBy(Constants.KEY_TIMESTAMP)).thenReturn(mockQuery);
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));

    // ACT & ASSERT (Deserialization part)
    TestObserver<Session> testObserver =
        sessionService.getSession(APP_NAME, USER_ID, SESSION_ID, Optional.empty()).test();
    testObserver.awaitCount(1);
    testObserver.assertNoErrors();
    testObserver.assertValue(
        retrievedSession -> {
          assertThat(retrievedSession.events()).hasSize(1);
          Event retrievedEvent = retrievedSession.events().get(0);

          assertThat(retrievedEvent.content().get().parts().get().get(0).text().get())
              .isEqualTo("Here are the results.");
          assertThat(
                  retrievedEvent.content().get().parts().get().get(1).functionCall().get().name())
              .hasValue("search_tool");
          assertThat(
                  retrievedEvent
                      .content()
                      .get()
                      .parts()
                      .get()
                      .get(2)
                      .functionResponse()
                      .get()
                      .name())
              .hasValue("search_tool");
          assertThat(retrievedEvent.content().get().parts().get().get(3).fileData().get().fileUri())
              .hasValue("gs://bucket/file.png");
          assertThat(
                  retrievedEvent.content().get().parts().get().get(3).fileData().get().mimeType())
              .hasValue("image/png");
          return true;
        });
  }

  /**
   * A wrapper class that implements ConcurrentMap but delegates to a HashMap. This is a workaround
   * to allow putting null values, which ConcurrentHashMap forbids, for testing state removal logic.
   */
  private static class HashMapAsConcurrentMap<K, V> extends AbstractMap<K, V>
      implements ConcurrentMap<K, V> {
    private final HashMap<K, V> map;

    public HashMapAsConcurrentMap(Map<K, V> map) {
      this.map = new HashMap<>(map);
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
      return map.entrySet();
    }

    @Override
    public V putIfAbsent(K key, V value) {
      return map.putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
      return map.remove(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
      return map.replace(key, oldValue, newValue);
    }

    @Override
    public V replace(K key, V value) {
      return map.replace(key, value);
    }
  }

  /** Tests that appendEvent with only app state deltas updates the correct stores. */
  @Test
  void appendEvent_withAppOnlyStateDeltas_updatesCorrectStores() {
    // Arrange
    Session session =
        Session.builder(SESSION_ID)
            .appName(APP_NAME)
            .userId(USER_ID)
            .state(new ConcurrentHashMap<>())
            .build();

    EventActions actions =
        EventActions.builder()
            .stateDelta(
                new ConcurrentHashMap<>(
                    ImmutableMap.of("_app_appKey", "appValue"))) // Corrected type and key
            .build();

    Event event =
        Event.builder()
            .author("model")
            .content(Content.builder().parts(List.of(Part.fromText("..."))).build())
            .actions(actions)
            .build();

    // Mock Firestore interactions for appendEvent
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockEventsCollection.document()).thenReturn(mockEventDocRef);
    when(mockEventDocRef.getId()).thenReturn(EVENT_ID);
    when(mockEventsCollection.document(EVENT_ID)).thenReturn(mockEventDocRef);
    when(mockSessionDocRef.update(anyMap()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));

    // Act
    sessionService.appendEvent(session, event).test().assertComplete();

    // Assert
    // Make sure only the app state is updated
    ArgumentCaptor<Map<String, Object>> appStateCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockAppStateDocRef).set(appStateCaptor.capture(), any(SetOptions.class));
    assertThat(appStateCaptor.getValue()).containsEntry("appKey", "appValue");

    // Verify that session state and user state are not updated
    verify(mockUserStateUsersCollection, never()).document(anyString());
    ArgumentCaptor<Map<String, Object>> sessionUpdateCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockSessionDocRef).update(sessionUpdateCaptor.capture());
    assertThat(sessionUpdateCaptor.getValue()).doesNotContainKey(Constants.KEY_STATE);
  }

  /** Tests that appendEvent with only user state deltas updates the correct stores. */
  @Test
  void appendEvent_withUserOnlyStateDeltas_updatesCorrectStores() {
    // Arrange
    Session session =
        Session.builder(SESSION_ID)
            .appName(APP_NAME)
            .userId(USER_ID)
            .state(new ConcurrentHashMap<>())
            .build();

    EventActions actions =
        EventActions.builder()
            .stateDelta(new ConcurrentHashMap<>(ImmutableMap.of("_user_userKey", "userValue")))
            .build();

    Event event =
        Event.builder()
            .author("model")
            .content(Content.builder().parts(List.of(Part.fromText("..."))).build())
            .actions(actions)
            .build();

    // Mock Firestore interactions for appendEvent
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockEventsCollection.document()).thenReturn(mockEventDocRef);
    when(mockEventDocRef.getId()).thenReturn(EVENT_ID);
    when(mockEventsCollection.document(EVENT_ID)).thenReturn(mockEventDocRef);
    when(mockSessionDocRef.update(anyMap()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));

    // Act
    sessionService.appendEvent(session, event).test().assertComplete();

    // Assert
    // Make sure only the user state is updated
    ArgumentCaptor<Map<String, Object>> userStateCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockUserStateUserDocRef).set(userStateCaptor.capture(), any(SetOptions.class));
    assertThat(userStateCaptor.getValue()).containsEntry("userKey", "userValue");

    // Verify that app state and session state are not updated
    verify(mockAppStateDocRef, never()).set(anyMap(), any(SetOptions.class));
    verify(mockSessionDocRef, never()).update(eq(Constants.KEY_STATE), any());
  }

  /**
   * Tests that appendEvent with all types of state deltas updates the correct stores and session
   * state.
   */
  @Test
  void appendEvent_withAllStateDeltas_updatesCorrectStores() {
    // Arrange
    Session session =
        Session.builder(SESSION_ID)
            .appName(APP_NAME)
            .userId(USER_ID)
            .state(new ConcurrentHashMap<>()) // The session state itself must be concurrent
            .build();
    session.state().put("keyToRemove", "someValue");

    Map<String, Object> stateDeltaMap = new HashMap<>();
    stateDeltaMap.put("sessionKey", "sessionValue");
    stateDeltaMap.put("_app_appKey", "appValue");
    stateDeltaMap.put("_user_userKey", "userValue");
    stateDeltaMap.put("keyToRemove", null);

    // Use the wrapper to satisfy the ConcurrentMap interface for the builder
    EventActions actions =
        EventActions.builder().stateDelta(new HashMapAsConcurrentMap<>(stateDeltaMap)).build();

    Event event =
        Event.builder()
            .author("model")
            .content(Content.builder().parts(List.of(Part.fromText("..."))).build())
            .actions(actions)
            .build();

    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockEventsCollection.document()).thenReturn(mockEventDocRef);
    when(mockEventDocRef.getId()).thenReturn(EVENT_ID);
    // THIS IS THE MISSING MOCK: Stub the call to get the document by its specific ID.
    when(mockEventsCollection.document(EVENT_ID)).thenReturn(mockEventDocRef);
    // Add the missing mock for the final session update call
    when(mockSessionDocRef.update(anyMap()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));

    // Act
    sessionService.appendEvent(session, event).test().assertComplete();

    // Assert
    assertThat(session.state()).containsEntry("sessionKey", "sessionValue");
    assertThat(session.state()).doesNotContainKey("keyToRemove");

    ArgumentCaptor<Map<String, Object>> appStateCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockAppStateDocRef).set(appStateCaptor.capture(), any(SetOptions.class));
    assertThat(appStateCaptor.getValue()).containsEntry("appKey", "appValue");

    ArgumentCaptor<Map<String, Object>> userStateCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockUserStateUserDocRef).set(userStateCaptor.capture(), any(SetOptions.class));
    assertThat(userStateCaptor.getValue()).containsEntry("userKey", "userValue");
  }

  /** Tests that getSession skips malformed events and returns only the well-formed ones. */
  @Test
  @SuppressWarnings("unchecked")
  void getSession_withMalformedEvent_skipsEventAndReturnsOthers() {
    // Arrange
    // A valid event document
    QueryDocumentSnapshot mockValidEventSnapshot = mock(QueryDocumentSnapshot.class);
    Map<String, Object> validEventData =
        ImmutableMap.of(
            Constants.KEY_AUTHOR,
            USER_ID,
            "timestamp",
            NOW.toString(),
            "content",
            ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of("text", "a valid event"))));
    when(mockValidEventSnapshot.getData()).thenReturn(validEventData);

    // A malformed event document (missing timestamp)
    QueryDocumentSnapshot mockMalformedEventSnapshot = mock(QueryDocumentSnapshot.class);
    Map<String, Object> malformedEventData =
        ImmutableMap.of(Constants.KEY_AUTHOR, USER_ID, "content", Map.of());
    when(mockMalformedEventSnapshot.getData()).thenReturn(malformedEventData);

    // Mock the session document itself
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true);
    when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef);
    // FIX: Provide a complete session data map to avoid NPEs in the Session.builder()
    when(mockSessionSnapshot.getData())
        .thenReturn(
            ImmutableMap.of(
                "id", SESSION_ID,
                "appName", APP_NAME,
                "userId", USER_ID,
                "updateTime", NOW.toString(),
                "state", Collections.emptyMap()));

    // Mock the query for events to return both the valid and malformed documents
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));
    when(mockQuerySnapshot.getDocuments())
        .thenReturn(ImmutableList.of(mockMalformedEventSnapshot, mockValidEventSnapshot));

    // Act
    TestObserver<Session> testObserver =
        sessionService.getSession(APP_NAME, USER_ID, SESSION_ID, Optional.empty()).test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertNoErrors();
    testObserver.assertValue(
        session -> {
          assertThat(session.events()).hasSize(1); // Only the valid event should be present
          assertThat(session.events().get(0).content().get().parts().get().get(0).text().get())
              .isEqualTo("a valid event");
          return true;
        });
  }

  /***
   * Tests that getSession skips events with null data.
   */
  @Test
  void getSession_withNullEventData_skipsEvent() {
    // Arrange
    // This test covers the `if (data == null)` branch in eventFromMap.
    QueryDocumentSnapshot mockNullDataEventSnapshot = mock(QueryDocumentSnapshot.class);
    when(mockNullDataEventSnapshot.getData()).thenReturn(null); // The specific condition to test

    // Mock the session document itself
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true);
    when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef);
    when(mockSessionSnapshot.getData())
        .thenReturn(
            ImmutableMap.of(
                "id", SESSION_ID,
                "appName", APP_NAME,
                "userId", USER_ID,
                "updateTime", NOW.toString(),
                "state", Collections.emptyMap()));

    // Mock the query for events to return the document with null data
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockNullDataEventSnapshot));

    // Act
    TestObserver<Session> testObserver =
        sessionService.getSession(APP_NAME, USER_ID, SESSION_ID, Optional.empty()).test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertNoErrors();
    testObserver.assertValue(
        session -> {
          assertThat(session.events()).isEmpty(); // Assert that the null-data event was skipped
          return true;
        });
  }

  /** Tests that getSession skips events with unparseable timestamps. */
  @Test
  void getSession_withUnparseableEvent_triggersCatchBlockAndSkipsEvent() {
    // Arrange
    // This test covers the `catch (Exception e)` block in eventFromMap.
    QueryDocumentSnapshot mockBadTimestampEvent = mock(QueryDocumentSnapshot.class);
    Map<String, Object> badData =
        ImmutableMap.of(
            Constants.KEY_AUTHOR,
            USER_ID,
            "timestamp",
            "not-a-valid-timestamp", // This will cause Instant.parse() to fail
            "content",
            ImmutableMap.of("parts", Collections.emptyList()));
    when(mockBadTimestampEvent.getData()).thenReturn(badData);

    // Mock session and event query setup (similar to other tests)
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true);
    when(mockSessionSnapshot.getData())
        .thenReturn(
            ImmutableMap.of(
                "id",
                SESSION_ID,
                "appName",
                APP_NAME,
                "userId",
                USER_ID,
                "updateTime",
                NOW.toString(),
                "state",
                Collections.emptyMap()));
    when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.collection(Constants.EVENTS_SUBCOLLECTION_NAME))
        .thenReturn(mockEventsCollection);
    when(mockEventsCollection.orderBy(Constants.KEY_TIMESTAMP)).thenReturn(mockQuery);
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockBadTimestampEvent));

    // Act & Assert
    sessionService
        .getSession(APP_NAME, USER_ID, SESSION_ID, Optional.empty())
        .test()
        .assertNoErrors() // The error should be caught and logged, not propagated
        .assertValue(session -> session.events().isEmpty()); // The bad event should be skipped
  }

  // --- listEvents Tests ---

  /** Tests that listEvents returns the expected events when the session exists. */
  @Test
  void listEvents_sessionExists_returnsEvents() {
    // Arrange
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true);
    when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef);
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));
    Map<String, Object> eventData =
        ImmutableMap.of(
            Constants.KEY_AUTHOR,
            USER_ID,
            "timestamp",
            NOW.toString(),
            "content",
            ImmutableMap.of("parts", ImmutableList.of(ImmutableMap.of("text", "an event"))));
    when(mockEventSnapshot.getData()).thenReturn(eventData);
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockEventSnapshot));

    // Act
    TestObserver<ListEventsResponse> testObserver =
        sessionService.listEvents(APP_NAME, USER_ID, SESSION_ID).test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(
        response -> {
          assertThat(response.events()).hasSize(1);
          assertThat(response.events().get(0).author()).isEqualTo(USER_ID);
          return true;
        });
  }

  /** Tests that listEvents throws SessionNotFoundException when the session does not exist. */
  @Test
  void listEvents_sessionDoesNotExist_throwsException() {
    // Arrange
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(false);

    // Act
    TestObserver<ListEventsResponse> testObserver =
        sessionService.listEvents(APP_NAME, USER_ID, SESSION_ID).test();

    // Assert
    testObserver.assertError(SessionNotFoundException.class);
  }

  // --- deleteSession Tests ---
  /** Tests that deleteSession deletes all events and the session itself. */
  @Test
  void deleteSession_deletesEventsAndSession() {
    // Arrange
    when(mockSessionsCollection.document(SESSION_ID)).thenReturn(mockSessionDocRef);
    when(mockSessionDocRef.collection(Constants.EVENTS_SUBCOLLECTION_NAME))
        .thenReturn(mockEventsCollection);
    when(mockEventsCollection.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));

    // Mock an event document to be deleted
    QueryDocumentSnapshot mockEventDoc = mock(QueryDocumentSnapshot.class);
    DocumentReference mockEventDocRefToDelete = mock(DocumentReference.class);
    when(mockEventDoc.getReference()).thenReturn(mockEventDocRefToDelete);
    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockEventDoc));

    // Act
    sessionService.deleteSession(APP_NAME, USER_ID, SESSION_ID).test().assertComplete();

    // Assert
    verify(mockWriteBatch, times(1)).delete(mockEventDocRefToDelete); // Verify batch delete
    verify(mockWriteBatch, times(1)).commit();
    verify(mockSessionDocRef, times(1)).delete();
  }

  // --- listSessions Tests ---
  /** Tests that listSessions returns a list of sessions for the specified app and user. */
  @Test
  void listSessions_returnsListOfSessions() {
    // Arrange
    when(mockSessionsCollection.whereEqualTo(Constants.KEY_APP_NAME, APP_NAME))
        .thenReturn(mockQuery);
    // Mock the query for listSessions
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));

    // Mock the documents returned by the query
    QueryDocumentSnapshot mockDoc1 = mock(QueryDocumentSnapshot.class);
    Map<String, Object> doc1Data =
        ImmutableMap.of(
            "id",
            "session-1",
            "appName",
            APP_NAME,
            "userId",
            USER_ID,
            "updateTime",
            NOW.toString());
    when(mockDoc1.getData()).thenReturn(doc1Data);

    QueryDocumentSnapshot mockDoc2 = mock(QueryDocumentSnapshot.class);
    Map<String, Object> doc2Data =
        ImmutableMap.of(
            "id",
            "session-2",
            "appName",
            APP_NAME,
            "userId",
            USER_ID,
            "updateTime",
            NOW.plusSeconds(10).toString());
    when(mockDoc2.getData()).thenReturn(doc2Data);

    when(mockQuerySnapshot.getDocuments()).thenReturn(ImmutableList.of(mockDoc1, mockDoc2));

    // Act
    TestObserver<ListSessionsResponse> testObserver =
        sessionService.listSessions(APP_NAME, USER_ID).test();

    // Assert
    testObserver.awaitCount(1);
    testObserver.assertComplete();
    testObserver.assertValue(
        response -> {
          assertThat(response.sessions()).hasSize(2);
          assertThat(response.sessions().get(0).id()).isEqualTo("session-1");
          assertThat(response.sessions().get(1).id()).isEqualTo("session-2");
          return true;
        });
  }
}
