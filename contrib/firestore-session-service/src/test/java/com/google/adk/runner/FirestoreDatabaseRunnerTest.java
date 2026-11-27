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
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.GcsArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.FirestoreMemoryService;
import com.google.adk.platform.UuidProvider;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.utils.FirestoreProperties;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteResult;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link FirestoreDatabaseRunner}. */
@ExtendWith(MockitoExtension.class)
public class FirestoreDatabaseRunnerTest {

  private static final String ENV_PROPERTY_NAME = "env";

  @Mock private BaseAgent mockAgent;
  @Mock private Firestore mockFirestore;
  @Mock private Storage mockStorage;

  // Firestore mock chain used by legacy constructor tests
  @Mock private CollectionReference mockRootCollection;
  @Mock private DocumentReference mockUserDocRef;
  @Mock private CollectionReference mockSessionsCollection;
  @Mock private DocumentReference mockSessionDocRef;
  @Mock private DocumentSnapshot mockSessionSnapshot;
  @Mock private CollectionReference mockEventsCollection;
  @Mock private DocumentReference mockEventDocRef;
  @Mock private Query mockQuery;
  @Mock private QuerySnapshot mockQuerySnapshot;
  @Mock private WriteResult mockWriteResult;

  @BeforeEach
  void setUp() {
    FirestoreProperties.resetForTest();
  }

  @AfterEach
  void tearDown() {
    FirestoreProperties.resetForTest();
    System.clearProperty(ENV_PROPERTY_NAME);
  }

  // =========================================================================
  // Legacy constructor tests
  // =========================================================================

  @Nested
  class LegacyConstructors {

    @Test
    void constructor_succeeds() {
      System.setProperty(ENV_PROPERTY_NAME, "default");
      when(mockAgent.name()).thenReturn("test-agent");

      FirestoreDatabaseRunner runner = new FirestoreDatabaseRunner(mockAgent, mockFirestore);
      assertNotNull(runner);
    }

    @Test
    void constructor_withAppName_usesInMemoryArtifactServiceWhenBucketNameIsBlank() {
      try (MockedStatic<FirestoreProperties> mockedProps =
          Mockito.mockStatic(FirestoreProperties.class)) {
        FirestoreProperties mockPropsInstance = mock(FirestoreProperties.class);
        mockedProps.when(FirestoreProperties::getInstance).thenReturn(mockPropsInstance);
        when(mockPropsInstance.getGcsAdkBucketName()).thenReturn(" ");

        Runner runner =
            new FirestoreDatabaseRunner(
                mockAgent, "test-app", new ArrayList<BasePlugin>(), mockFirestore);

        assertEquals("test-app", runner.appName());
        assertInstanceOf(InMemoryArtifactService.class, runner.artifactService());
      }
    }

    @Test
    void constructor_usesInMemoryArtifactServiceWhenBucketNameIsNull() {
      try (MockedStatic<FirestoreProperties> mockedProps =
          Mockito.mockStatic(FirestoreProperties.class)) {
        FirestoreProperties mockPropsInstance = mock(FirestoreProperties.class);
        mockedProps.when(FirestoreProperties::getInstance).thenReturn(mockPropsInstance);
        when(mockPropsInstance.getGcsAdkBucketName()).thenReturn(null);

        Runner runner = new FirestoreDatabaseRunner(mockAgent, mockFirestore);

        assertInstanceOf(InMemoryArtifactService.class, runner.artifactService());
      }
    }

    @Test
    void run_withUserInput_createsSessionAndExecutesAgent() throws Exception {
      System.setProperty(ENV_PROPERTY_NAME, "default");
      when(mockAgent.name()).thenReturn("test-agent");

      String userInput = "hello\nexit\n";
      InputStream originalIn = System.in;
      System.setIn(new ByteArrayInputStream(userInput.getBytes()));

      when(mockFirestore.collection("default-adk-session")).thenReturn(mockRootCollection);
      when(mockRootCollection.document(anyString())).thenReturn(mockUserDocRef);
      when(mockUserDocRef.collection(anyString())).thenReturn(mockSessionsCollection);
      when(mockSessionsCollection.document(anyString())).thenReturn(mockSessionDocRef);
      when(mockSessionDocRef.create(anyMap()))
          .thenReturn(ApiFutures.immediateFuture(mockWriteResult));
      when(mockSessionDocRef.update(anyMap()))
          .thenReturn(ApiFutures.immediateFuture(mockWriteResult));
      when(mockSessionDocRef.collection(anyString())).thenReturn(mockEventsCollection);
      when(mockEventsCollection.document()).thenReturn(mockEventDocRef);
      when(mockEventDocRef.getId()).thenReturn("test-event-id");
      when(mockEventsCollection.document(anyString())).thenReturn(mockEventDocRef);
      when(mockEventDocRef.set(anyMap())).thenReturn(ApiFutures.immediateFuture(mockWriteResult));
      when(mockSessionDocRef.get()).thenReturn(ApiFutures.immediateFuture(mockSessionSnapshot));
      when(mockSessionSnapshot.exists()).thenReturn(true);
      when(mockSessionSnapshot.getData())
          .thenReturn(
              Map.of(
                  "id", "test-session-id",
                  "appName", "test-app",
                  "userId", "test-user-id",
                  "updateTime", Instant.now().toString(),
                  "state", Map.of()));
      when(mockSessionSnapshot.getReference()).thenReturn(mockSessionDocRef);
      when(mockEventsCollection.orderBy(anyString())).thenReturn(mockQuery);
      when(mockQuery.get()).thenReturn(ApiFutures.immediateFuture(mockQuerySnapshot));

      Event mockAgentEvent =
          Event.builder()
              .content(Content.builder().parts(Part.fromText("Hi there!")).build())
              .author(mockAgent.name())
              .build();
      when(mockAgent.runAsync(any(InvocationContext.class)))
          .thenReturn(Flowable.just(mockAgentEvent));

      FirestoreDatabaseRunner runner =
          new FirestoreDatabaseRunner(mockAgent, "test-app", mockFirestore);

      Session session =
          runner.sessionService().createSession("test-app", "test-user-id").blockingGet();
      Content userContent = Content.builder().parts(Part.fromText("hello")).build();
      runner
          .runAsync(session.userId(), session.id(), userContent, RunConfig.builder().build())
          .blockingSubscribe();

      ArgumentCaptor<InvocationContext> contextCaptor =
          ArgumentCaptor.forClass(InvocationContext.class);
      verify(mockAgent).runAsync(contextCaptor.capture());
      assertNotNull(contextCaptor.getValue().userContent().get().parts().get().get(0).text());

      System.setIn(originalIn);
    }
  }

  // =========================================================================
  // Builder tests
  // =========================================================================

  @Nested
  class BuilderTests {

    @Test
    void create_withAgentAndFirestore_buildsRunnerWithDefaults() {
      when(mockAgent.name()).thenReturn("test-agent");

      Runner runner = FirestoreDatabaseRunner.create(mockAgent, mockFirestore);

      assertNotNull(runner);
      assertEquals("test-agent", runner.appName());
      assertInstanceOf(FirestoreSessionService.class, runner.sessionService());
      assertInstanceOf(FirestoreMemoryService.class, runner.memoryService());
      // adk-firestore.properties in test resources configures test-bucket
      assertInstanceOf(GcsArtifactService.class, runner.artifactService());
    }

    @Test
    void create_withAgentAppNameAndFirestore_usesCustomAppName() {
      Runner runner = FirestoreDatabaseRunner.create(mockAgent, "custom-app", mockFirestore);

      assertNotNull(runner);
      assertEquals("custom-app", runner.appName());
    }

    @Test
    void builder_withApp_buildsRunnerFromApp() {
      App app = App.builder().name("my_app").rootAgent(mockAgent).build();

      Runner runner = FirestoreDatabaseRunner.newBuilder().app(app).firestore(mockFirestore).build();

      assertNotNull(runner);
      assertEquals("my_app", runner.appName());
      assertEquals(mockAgent, runner.agent());
    }

    @Test
    void builder_withAgentAndApp_throwsIllegalStateException() {
      App app = App.builder().name("my_app").rootAgent(mockAgent).build();

      assertThrows(
          IllegalStateException.class,
          () -> FirestoreDatabaseRunner.newBuilder().agent(mockAgent).app(app));

      assertThrows(
          IllegalStateException.class,
          () -> FirestoreDatabaseRunner.newBuilder().app(app).agent(mockAgent));
    }

    @Test
    void builder_withGcsArtifacts_configuresGcsArtifactService() {
      when(mockAgent.name()).thenReturn("test-agent");

      Runner runner =
          FirestoreDatabaseRunner.newBuilder()
              .agent(mockAgent)
              .firestore(mockFirestore)
              .withGcsArtifacts("test-bucket", mockStorage)
              .build();

      assertInstanceOf(GcsArtifactService.class, runner.artifactService());
    }

    @Test
    void builder_whenNoGcsBucketConfigured_throwsIllegalStateException() {
      when(mockAgent.name()).thenReturn("test-agent");
      try (MockedStatic<FirestoreProperties> mockedProps =
          Mockito.mockStatic(FirestoreProperties.class)) {
        FirestoreProperties mockPropsInstance = mock(FirestoreProperties.class);
        mockedProps.when(FirestoreProperties::getInstance).thenReturn(mockPropsInstance);
        when(mockPropsInstance.getGcsAdkBucketName()).thenReturn(null);

        Runner runner = 
                FirestoreDatabaseRunner.newBuilder()
                    .agent(mockAgent)
                    .firestore(mockFirestore)                    
                    .build();
        assertInstanceOf(InMemoryArtifactService.class, runner.artifactService());
          
      }
    }

    @Test
    void builder_withExplicitInMemoryArtifactService_succeedsWithoutGcsBucket() {
      when(mockAgent.name()).thenReturn("test-agent");

      Runner runner =
          FirestoreDatabaseRunner.newBuilder()
              .agent(mockAgent)
              .firestore(mockFirestore)
              .artifactService(new InMemoryArtifactService())
              .build();

      assertInstanceOf(InMemoryArtifactService.class, runner.artifactService());
    }

    @Test
    void builder_withExplicitArtifactService_usesProvidedArtifactService() {
      when(mockAgent.name()).thenReturn("test-agent");
      BaseArtifactService mockArtifactService = mock(BaseArtifactService.class);

      Runner runner =
          FirestoreDatabaseRunner.newBuilder()
              .agent(mockAgent)
              .firestore(mockFirestore)
              .artifactService(mockArtifactService)
              .build();

      assertEquals(mockArtifactService, runner.artifactService());
    }

    @Test
    void builder_withCustomSessionAndMemoryServices_doesNotRequireFirestore() {
      when(mockAgent.name()).thenReturn("test-agent");
      BaseSessionService customSessionService = mock(BaseSessionService.class);
      BaseMemoryService customMemoryService = mock(BaseMemoryService.class);

      Runner runner =
          FirestoreDatabaseRunner.newBuilder()
              .agent(mockAgent)
              .sessionService(customSessionService)
              .memoryService(customMemoryService)
              .build();

      assertEquals(customSessionService, runner.sessionService());
      assertEquals(customMemoryService, runner.memoryService());
    }

    @Test
    void builder_missingAgentAndApp_throwsIllegalStateException() {
      assertThrows(
          IllegalStateException.class,
          () -> FirestoreDatabaseRunner.newBuilder().firestore(mockFirestore).build());
    }

    @Test
    void builder_missingFirestoreAndServices_throwsIllegalStateException() {
      assertThrows(
          IllegalStateException.class,
          () -> FirestoreDatabaseRunner.newBuilder().agent(mockAgent).build());
    }

    @Test
    void builder_withCustomProvidersAndScheduler_passesThroughToRunner() {
      when(mockAgent.name()).thenReturn("test-agent");
      InstantSource fixedSource = InstantSource.fixed(Instant.parse("2025-01-01T00:00:00Z"));
      UuidProvider customUuid = () -> "fixed-uuid";
      Plugin mockPlugin = mock(Plugin.class);

      Runner runner =
          FirestoreDatabaseRunner.newBuilder()
              .agent(mockAgent)
              .firestore(mockFirestore)
              .instantSource(fixedSource)
              .uuidProvider(customUuid)
              .scheduler(Schedulers.trampoline())
              .plugins(List.of(mockPlugin))
              .build();

      assertNotNull(runner);
    }

    @Test
    void toRunnerBuilder_allowsFurtherCustomization() {
      when(mockAgent.name()).thenReturn("test-agent");

      Runner.Builder runnerBuilder =
          FirestoreDatabaseRunner.newBuilder()
              .agent(mockAgent)
              .firestore(mockFirestore)
              .toRunnerBuilder();

      Runner runner = runnerBuilder.build();
      assertNotNull(runner);
      assertEquals("test-agent", runner.appName());
    }
  }

  @Test 
  void builder_withCustomArtifactServiceAndGcsBucket_usesCustomArtifactService() {
    when(mockAgent.name()).thenReturn("test-agent");
    BaseArtifactService customArtifactService = mock(BaseArtifactService.class);

    Runner runner =
        FirestoreDatabaseRunner.newBuilder()
            .agent(mockAgent)
            .firestore(mockFirestore)
            .withGcsArtifacts("test-bucket", mockStorage)
            .artifactService(customArtifactService)
            .build();

    assertEquals(customArtifactService, runner.artifactService());
  }

  @Test
  void builder_withCustomServices_wiresServicesCorrectly() {
    System.setProperty(ENV_PROPERTY_NAME, "default");
    try {
      Firestore mockFirestore = mock(Firestore.class);
      String appName = "test-app";
      GcsArtifactService customArtifactService =
          new GcsArtifactService("test-bucket", mockStorage);

      Runner runner =
          FirestoreDatabaseRunner.newBuilder()
              .agent(mockAgent)
              .appName(appName)
              .firestore(mockFirestore)
              .artifactService(customArtifactService)
              .build();

      assertEquals(appName, runner.appName());
      assertInstanceOf(FirestoreSessionService.class, runner.sessionService());
      assertSame(customArtifactService, runner.artifactService());
    } finally {
      System.clearProperty(ENV_PROPERTY_NAME);
    }
  }

  @Test
  void builder_withCustomServices_wiresServicesCorrectlyNoArtifactService() {
    System.setProperty(ENV_PROPERTY_NAME, "default");
    try {
      Firestore mockFirestore = mock(Firestore.class);
      String appName = "test-app";
      GcsArtifactService customArtifactService =
          new GcsArtifactService("test-bucket", mockStorage);

      Runner runner =
          FirestoreDatabaseRunner.newBuilder()
              .agent(mockAgent)
              .appName(appName)
              .firestore(mockFirestore)             
              .build();

      assertEquals(appName, runner.appName());
      assertInstanceOf(FirestoreSessionService.class, runner.sessionService());
      //assertSame(customArtifactService, runner.artifactService());
    } finally {
      System.clearProperty(ENV_PROPERTY_NAME);
    }
  }  
  
  @Test
  void builder_withE2eCustomService_runsAgentAndPersistsSession() {
    System.setProperty(ENV_PROPERTY_NAME, "default");
    try {
      String appName = "test-app";
      Firestore firestore = FirestoreOptions.getDefaultInstance().getService();
      GcsArtifactService customArtifactService =
          new GcsArtifactService("test-bucket", mockStorage);

      BaseAgent testAgent =
          new BaseAgent("test-agent", "Test agent", List.of(), List.of(), List.of()) {
            @Override
            protected Flowable<Event> runAsyncImpl(InvocationContext invocationContext) {
              return Flowable.just(
                  Event.builder()
                      .id(Event.generateEventId())
                      .invocationId(invocationContext.invocationId())
                      .author(name())
                      .content(Content.fromParts(Part.fromText("Hello from test agent!")))
                      .build());
            }

            @Override
            protected Flowable<Event> runLiveImpl(InvocationContext invocationContext) {
              return Flowable.error(new UnsupportedOperationException("Live mode not supported"));
            }
          };

      Runner runner =
          FirestoreDatabaseRunner.newBuilder()
              .agent(testAgent)
              .appName(appName)
              .firestore(firestore)
              .artifactService(customArtifactService)
              .build();

      assertSame(customArtifactService, runner.artifactService());

      Session session =
          runner.sessionService().createSession(appName, "user1234").blockingGet();
      assertNotNull(session);

      RunConfig runConfig = RunConfig.builder().build();
      Content userContent = Content.fromParts(Part.fromText("Hello, agent!"));

      List<Event> events =
          runner
              .runAsync(session.userId(), session.id(), userContent, runConfig)
              .toList()
              .blockingGet();

      assertEquals(1, events.size());
      assertEquals("test-agent", events.get(0).author());
      assertEquals("Hello from test agent!", events.get(0).stringifyContent());
    } finally {
      System.clearProperty(ENV_PROPERTY_NAME);
    }
  }

  @Test
  void builder_withMultiplePluginCalls_accumulatesPlugins() {
    Firestore mockFirestore = mock(Firestore.class);
    Plugin plugin1 = new BasePlugin("plugin-1") {};
    Plugin plugin2 = new BasePlugin("plugin-2") {};
    Plugin plugin3 = new BasePlugin("plugin-3") {};

    Runner runner =
        FirestoreDatabaseRunner.newBuilder()
            .agent(mockAgent)
            .appName("test-app")
            .firestore(mockFirestore)
            .plugins(plugin1)
            .plugins(plugin2, plugin3)
            .build();

    assertSame(plugin1, runner.pluginManager().getPlugin("plugin-1").orElseThrow());
    assertSame(plugin2, runner.pluginManager().getPlugin("plugin-2").orElseThrow());
    assertSame(plugin3, runner.pluginManager().getPlugin("plugin-3").orElseThrow());
  }

  @Test
  void builder_withGcsArtifactsBucketOnly_usesDefaultStorageOptions() {
    Firestore mockFirestore = mock(Firestore.class);
    StorageOptions mockStorageOptions = mock(StorageOptions.class);
    Storage mockDefaultStorage = mock(Storage.class);
    when(mockStorageOptions.getService()).thenReturn(mockDefaultStorage);

    try (MockedStatic<StorageOptions> storageOptionsStatic = mockStatic(StorageOptions.class)) {
      storageOptionsStatic.when(StorageOptions::getDefaultInstance).thenReturn(mockStorageOptions);

      Runner runner =
          FirestoreDatabaseRunner.newBuilder()
              .agent(mockAgent)
              .appName("test-app")
              .firestore(mockFirestore)
              .withGcsArtifacts("test-bucket")
              .build();

      assertInstanceOf(GcsArtifactService.class, runner.artifactService());
      storageOptionsStatic.verify(StorageOptions::getDefaultInstance);
      verify(mockStorageOptions).getService();
    }
  }

  @Test
    void resolveDefaultArtifactService_whenBucketIsNull_returnsInMemoryArtifactService() {
      assertInMemoryArtifactServiceForBucket(null);
    }

    @Test
    void resolveDefaultArtifactService_whenBucketIsEmpty_returnsInMemoryArtifactService() {
      assertInMemoryArtifactServiceForBucket("");
    }

    @Test
    void resolveDefaultArtifactService_whenBucketIsBlank_returnsInMemoryArtifactService() {
      assertInMemoryArtifactServiceForBucket("   ");
    }

    private void assertInMemoryArtifactServiceForBucket(String bucketName) {
      try (MockedStatic<FirestoreProperties> mockedProps =
              Mockito.mockStatic(FirestoreProperties.class);
          MockedStatic<StorageOptions> mockedStorageOptions =
              Mockito.mockStatic(StorageOptions.class)) {
        FirestoreProperties mockPropsInstance = mock(FirestoreProperties.class);
        mockedProps.when(FirestoreProperties::getInstance).thenReturn(mockPropsInstance);
        when(mockPropsInstance.getGcsAdkBucketName()).thenReturn(bucketName);

        Runner runner =
            new FirestoreDatabaseRunner(
                mockAgent, "test-app", new ArrayList<BasePlugin>(), mockFirestore);

        assertInstanceOf(InMemoryArtifactService.class, runner.artifactService());
        mockedStorageOptions.verifyNoInteractions();
      }
    }

      @Test
    void resolveDefaultArtifactService_whenBucketConfigured_returnsGcsArtifactService() {
      StorageOptions mockStorageOptions = mock(StorageOptions.class);
      Storage mockStorage = mock(Storage.class);
      when(mockStorageOptions.getService()).thenReturn(mockStorage);

      try (MockedStatic<FirestoreProperties> mockedProps =
              Mockito.mockStatic(FirestoreProperties.class);
          MockedStatic<StorageOptions> mockedStorageOptions =
              Mockito.mockStatic(StorageOptions.class)) {
        FirestoreProperties mockPropsInstance = mock(FirestoreProperties.class);
        mockedProps.when(FirestoreProperties::getInstance).thenReturn(mockPropsInstance);
        when(mockPropsInstance.getGcsAdkBucketName()).thenReturn("my-test-bucket");
        mockedStorageOptions
            .when(StorageOptions::getDefaultInstance)
            .thenReturn(mockStorageOptions);

        Runner runner =
            new FirestoreDatabaseRunner(
                mockAgent, "test-app", new ArrayList<BasePlugin>(), mockFirestore);

        assertInstanceOf(GcsArtifactService.class, runner.artifactService());
        mockedStorageOptions.verify(StorageOptions::getDefaultInstance);
        verify(mockStorageOptions).getService();
      }
    }
}
