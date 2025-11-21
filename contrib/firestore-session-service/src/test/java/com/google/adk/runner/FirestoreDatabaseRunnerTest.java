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

package com.google.adk.runner;

import static org.junit.Assert.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.Session;
import com.google.adk.utils.FirestoreProperties;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** Test class for {@link FirestoreDatabaseRunner} tests. */
@ExtendWith(MockitoExtension.class)
public class FirestoreDatabaseRunnerTest {

  private static final String ENV_PROPERTY_NAME = "env"; // Renamed for clarity

  @BeforeEach
  public void setup() throws Exception {
    // Reset properties before each test to ensure isolation
    FirestoreProperties.resetForTest();
  }

  @Mock private BaseAgent mockAgent;
  @Mock private Firestore mockFirestore;
  // Mocks for the FirestoreSessionService that will be created internally
  @Mock private CollectionReference mockRootCollection;
  @Mock private DocumentReference mockUserDocRef;
  @Mock private CollectionReference mockSessionsCollection;
  @Mock private DocumentReference mockSessionDocRef;
  @Mock private DocumentSnapshot mockSessionSnapshot;
  // Mocks for state updates
  @Mock private CollectionReference mockAppStateCollection;
  @Mock private DocumentReference mockAppStateDocRef;
  @Mock private CollectionReference mockUserStateRootCollection;
  @Mock private DocumentReference mockUserStateAppDocRef;
  @Mock private CollectionReference mockUserStateUsersCollection;
  @Mock private DocumentReference mockUserStateUserDocRef;
  @Mock private CollectionReference mockEventsCollection;
  @Mock private DocumentReference mockEventDocRef;
  @Mock private Query mockQuery;
  @Mock private QuerySnapshot mockQuerySnapshot;
  @Mock private WriteResult mockWriteResult;

  /**
   * Tests that the constructor succeeds when the required properties are set.
   *
   * @throws Exception
   */
  @Test
  void constructor_succeeds() throws Exception {
    // Ensure the required property is set for the success case
    System.setProperty(ENV_PROPERTY_NAME, "default"); // Loads adk-firestore.properties
    Mockito.when(mockAgent.name()).thenReturn("test-agent");

    FirestoreDatabaseRunner runner = new FirestoreDatabaseRunner(mockAgent, mockFirestore);
    assertNotNull(runner);
  }

  /**
   * Tests that the constructor throws an exception when the GCS bucket name property is missing.
   *
   * @throws Exception
   */
  @Test
  void constructor_withAppName_throwsExceptionWhenBucketNameIsMissing() {
    assertThrows(
        RuntimeException.class,
        () -> {
          // Arrange: Use mockito-inline to mock the static method call.
          try (MockedStatic<FirestoreProperties> mockedProps =
              Mockito.mockStatic(FirestoreProperties.class)) {
            FirestoreProperties mockPropsInstance = mock(FirestoreProperties.class);
            mockedProps.when(FirestoreProperties::getInstance).thenReturn(mockPropsInstance);
            when(mockPropsInstance.getGcsAdkBucketName()).thenReturn(" "); // Empty string

            // Act
            new FirestoreDatabaseRunner(
                mockAgent, "test-app", new ArrayList<BasePlugin>(), mockFirestore);
          }
        });
  }

  /**
   * Tests that the constructor throws an exception when the GCS bucket name is null.
   *
   * @throws Exception
   */
  @Test
  void constructor_throwsExceptionWhenBucketNameIsNull() {
    assertThrows(
        RuntimeException.class,
        () -> {
          // Arrange: Use mockito-inline to mock the static method call.
          try (MockedStatic<FirestoreProperties> mockedProps =
              Mockito.mockStatic(FirestoreProperties.class)) {
            FirestoreProperties mockPropsInstance = mock(FirestoreProperties.class);
            mockedProps.when(FirestoreProperties::getInstance).thenReturn(mockPropsInstance);
            when(mockPropsInstance.getGcsAdkBucketName())
                .thenReturn(null); // Explicitly return null for bucketName
            // Act
            new FirestoreDatabaseRunner(mockAgent, mockFirestore);
          }
        });
  }

  /**
   * Tests that run with user input creates a session and executes the agent.
   *
   * @throws Exception
   */
  @Test
  void run_withUserInput_createsSessionAndExecutesAgent() throws Exception {
    // Arrange
    System.setProperty(ENV_PROPERTY_NAME, "default"); // Ensure properties are loaded
    when(mockAgent.name()).thenReturn("test-agent");

    // Mock user input
    String userInput = "hello\nexit\n";
    InputStream originalIn = System.in;
    System.setIn(new ByteArrayInputStream(userInput.getBytes()));

    // Mock the Firestore calls that SessionService will make
    when(mockFirestore.collection("default-adk-session")).thenReturn(mockRootCollection);
    when(mockRootCollection.document(anyString())).thenReturn(mockUserDocRef);
    when(mockUserDocRef.collection(anyString())).thenReturn(mockSessionsCollection);
    when(mockSessionsCollection.document(anyString())).thenReturn(mockSessionDocRef);

    when(mockSessionDocRef.set(anyMap())).thenReturn(ApiFutures.immediateFuture(mockWriteResult));
    when(mockSessionDocRef.update(anyMap()))
        .thenReturn(ApiFutures.immediateFuture(mockWriteResult));
    // Mock the event sub-collection chain
    when(mockSessionDocRef.collection(anyString())).thenReturn(mockEventsCollection);
    // THIS IS THE MISSING MOCK: Stub the no-arg document() call for ID generation.
    when(mockEventsCollection.document()).thenReturn(mockEventDocRef);
    when(mockEventDocRef.getId()).thenReturn("test-event-id");
    when(mockEventsCollection.document(anyString())).thenReturn(mockEventDocRef);
    when(mockEventDocRef.set(anyMap())).thenReturn(ApiFutures.immediateFuture(mockWriteResult));
    // THIS IS THE MISSING MOCK: Stub the get() call on the session document.
    when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
    when(mockSessionSnapshot.exists()).thenReturn(true);
    // Provide a complete map for the session data
    when(mockSessionSnapshot.getData())
        .thenReturn(
            Map.of(
                "id", "test-session-id",
                "appName", "test-app",
                "userId", "test-user-id",
                "updateTime", Instant.now().toString(),
                "state", Map.of()));
    when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef);
    // THIS IS THE MISSING MOCK: Stub the orderBy() call on the events collection.
    when(mockEventsCollection.orderBy(anyString())).thenReturn(mockQuery);
    when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));

    // Mock Agent behavior
    Event mockAgentEvent =
        Event.builder()
            .content(Content.builder().parts(Part.fromText("Hi there!")).build())
            .author(mockAgent.name())
            .build();
    when(mockAgent.runAsync(any(InvocationContext.class)))
        .thenReturn(Flowable.just(mockAgentEvent));

    // Create runner, which will internally create a FirestoreSessionService
    // using our mocked Firestore object.
    FirestoreDatabaseRunner runner =
        new FirestoreDatabaseRunner(mockAgent, "test-app", mockFirestore);

    // Act
    // First, create a session to get a valid session ID
    Session session =
        runner.sessionService().createSession("test-app", "test-user-id").blockingGet();
    // Now, run the agent with the user input
    Content userContent = Content.builder().parts(Part.fromText("hello")).build();
    runner
        .runAsync(session.userId(), session.id(), userContent, RunConfig.builder().build())
        .blockingSubscribe(); // block until the flow completes

    // Assert
    ArgumentCaptor<InvocationContext> contextCaptor =
        ArgumentCaptor.forClass(InvocationContext.class);
    verify(mockAgent).runAsync(contextCaptor.capture());
    assertNotNull(contextCaptor.getValue().userContent().get().parts().get().get(0).text());

    // Restore original System.in
    System.setIn(originalIn);
  }

  /**
   * Cleans up after each test.
   *
   * @throws Exception
   */
  @AfterEach
  public void teardown() throws Exception {
    // Clean up the environment variable after each test
    FirestoreProperties.resetForTest(); // Reset the singleton instance
    System.clearProperty(ENV_PROPERTY_NAME); // Clear the system property
  }
}
