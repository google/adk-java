// /**
//  * @author Sandeep Belgavi
//  * @since 2025-07-31
//  */
// package com.google.adk;

// import static org.junit.jupiter.api.Assertions.*;

// import com.google.adk.artifacts.CassandraArtifactService;
// import com.google.adk.events.Event;
// import com.google.adk.sessions.CassandraSessionService;
// import com.google.adk.sessions.Session;
// import com.google.adk.utils.CassandraDBHelper;
// import com.google.genai.types.Part;
// import io.reactivex.rxjava3.core.Maybe;
// import io.reactivex.rxjava3.core.Single;
// import java.time.Instant;
// import java.util.Collections;
// import java.util.Optional;
// import java.util.UUID;
// import java.util.concurrent.ConcurrentHashMap;
// import java.util.concurrent.TimeUnit;
// import org.junit.jupiter.api.*;

// @TestInstance(TestInstance.Lifecycle.PER_CLASS)
// public class CassandraServiceIntegrationTest {

//   private CassandraSessionService sessionService;
//   private CassandraArtifactService artifactService;
//   private CassandraDBHelper dbHelper;

//   private static final String APP_NAME = "test_app";
//   private String userId;
//   private String sessionId;

//   @BeforeAll
//   void setupEnvironment() {
//     // Set Cassandra environment variables for the test
//     System.setProperty("cassandra_host", "CassandraHostName");
//     System.setProperty("cassandra_port", "9042");
//     System.setProperty("cassandra_user", "User");
//     System.setProperty("cassandra_password", "Password");
//     System.setProperty("cassandra_keyspace", "Keyspace");

//     // Initialize services (they will pick up env vars)
//     sessionService = new CassandraSessionService();
//     artifactService = new CassandraArtifactService(APP_NAME, "artifacts");
//     dbHelper = CassandraDBHelper.getInstance();
//   }

//   @BeforeEach
//   void generateUniqueIds() {
//     userId = "user_" + UUID.randomUUID().toString();
//     sessionId = "session_" + UUID.randomUUID().toString();
//   }

//   @Test
//   void testSessionLifecycle() throws Exception {
//     // 1. Create Session
//     Single<Session> createSessionSingle =
//         sessionService.createSession(
//             APP_NAME,
//             userId,
//             new ConcurrentHashMap<>(Collections.singletonMap("initialKey", "initialValue")),
//             sessionId);
//     Session createdSession = createSessionSingle.blockingGet();
//     assertNotNull(createdSession);
//     assertEquals(sessionId, createdSession.id());
//     assertEquals(APP_NAME, createdSession.appName());
//     assertEquals(userId, createdSession.userId());
//     assertTrue(createdSession.state().containsKey("initialKey"));
//     assertEquals("initialValue", createdSession.state().get("initialKey"));
//     assertTrue(createdSession.events().isEmpty());

//     System.out.println("Created Session: " + createdSession.id());

//     // 2. Get Session
//     Session fetchedSession =
//         sessionService.getSession(APP_NAME, userId, sessionId, Optional.empty()).blockingGet();
//     assertNotNull(fetchedSession);
//     assertEquals(createdSession.id(), fetchedSession.id());
//     assertEquals(createdSession.appName(), fetchedSession.appName());
//     assertEquals(createdSession.userId(), fetchedSession.userId());
//     assertEquals(createdSession.state(), fetchedSession.state());
//     assertEquals(createdSession.events(), fetchedSession.events());

//     System.out.println("Fetched Session: " + fetchedSession.id());

//     // 3. Append Event
//     Event testEvent =
//         Event.builder().id("event1").timestamp(Instant.now().getEpochSecond()).build();
//     sessionService.appendEvent(fetchedSession, testEvent).blockingGet();

//     Session sessionAfterEvent =
//         sessionService.getSession(APP_NAME, userId, sessionId, Optional.empty()).blockingGet();
//     assertNotNull(sessionAfterEvent);
//     assertEquals(1, sessionAfterEvent.events().size());
//     assertEquals("event1", sessionAfterEvent.events().get(0).id());

//     System.out.println("Appended event to session: " + sessionAfterEvent.id());

//     // 4. List Events
//     assertEquals(
//         1, sessionService.listEvents(APP_NAME, userId, sessionId).blockingGet().events().size());

//     // 5. Delete Session
//     sessionService.deleteSession(APP_NAME, userId, sessionId).blockingAwait(5, TimeUnit.SECONDS);

//     // Verify session is deleted
//     Maybe<Session> deletedSessionMaybe =
//         sessionService.getSession(APP_NAME, userId, sessionId, Optional.empty());
//     assertTrue(deletedSessionMaybe.isEmpty().blockingGet());

//     System.out.println("Deleted Session: " + sessionId);
//   }

//   @Test
//   void testArtifactLifecycle() throws Exception {
//     String artifactFilename = "test_artifact.txt";
//     Part testPart = Part.fromText("Hello, Cassandra Artifact!");

//     // 1. Save Artifact
//     int version =
//         artifactService
//             .saveArtifact(APP_NAME, userId, sessionId, artifactFilename, testPart)
//             .blockingGet();
//     assertEquals(0, version); // First version is 0

//     System.out.println("Saved artifact: " + artifactFilename + " version: " + version);

//     // 2. Load Artifact (latest)
//     Part loadedPart =
//         artifactService
//             .loadArtifact(APP_NAME, userId, sessionId, artifactFilename, Optional.empty())
//             .blockingGet();
//     assertNotNull(loadedPart);
//     assertEquals("Hello, Cassandra Artifact!", loadedPart.text().get());

//     System.out.println("Loaded latest artifact: " + artifactFilename);

//     // 3. Save another version
//     Part updatedPart = Part.fromText("Updated content!");
//     int newVersion =
//         artifactService
//             .saveArtifact(APP_NAME, userId, sessionId, artifactFilename, updatedPart)
//             .blockingGet();
//     assertEquals(1, newVersion);

//     System.out.println(
//         "Saved new version of artifact: " + artifactFilename + " version: " + newVersion);

//     // 4. Load specific version
//     Part loadedOldVersion =
//         artifactService
//             .loadArtifact(APP_NAME, userId, sessionId, artifactFilename, Optional.of(0))
//             .blockingGet();
//     assertNotNull(loadedOldVersion);
//     assertEquals("Hello, Cassandra Artifact!", loadedOldVersion.text().get());

//     System.out.println("Loaded specific version (0) of artifact: " + artifactFilename);

//     // 5. List Artifact Keys
//     assertEquals(
//         1,
//         artifactService
//             .listArtifactKeys(APP_NAME, userId, sessionId)
//             .blockingGet()
//             .filenames()
//             .size());
//     assertTrue(
//         artifactService
//             .listArtifactKeys(APP_NAME, userId, sessionId)
//             .blockingGet()
//             .filenames()
//             .contains(artifactFilename));

//     System.out.println("Listed artifact keys.");

//     // 6. List Versions
//     assertEquals(
//         2,
//         artifactService
//             .listVersions(APP_NAME, userId, sessionId, artifactFilename)
//             .blockingGet()
//             .size());
//     assertTrue(
//         artifactService
//             .listVersions(APP_NAME, userId, sessionId, artifactFilename)
//             .blockingGet()
//             .contains(0));
//     assertTrue(
//         artifactService
//             .listVersions(APP_NAME, userId, sessionId, artifactFilename)
//             .blockingGet()
//             .contains(1));

//     System.out.println("Listed artifact versions.");

//     // 7. Delete Artifact
//     artifactService
//         .deleteArtifact(APP_NAME, userId, sessionId, artifactFilename)
//         .blockingAwait(5, TimeUnit.SECONDS);

//     // Verify artifact is deleted
//     Maybe<Part> deletedArtifactMaybe =
//         artifactService.loadArtifact(
//             APP_NAME, userId, sessionId, artifactFilename, Optional.empty());
//     assertTrue(deletedArtifactMaybe.isEmpty().blockingGet());

//     System.out.println("Deleted artifact: " + artifactFilename);
//   }

//   @Test
//   void testCreateSession_existingSessionId_updatesSession() throws Exception {
//     String initialSessionId = "existing_session_" + UUID.randomUUID().toString();
//     String initialUserId = "user_" + UUID.randomUUID().toString();

//     // Create initial session
//     sessionService
//         .createSession(APP_NAME, initialUserId, new ConcurrentHashMap<>(), initialSessionId)
//         .blockingGet();

//     // Update state of the existing session
//     ConcurrentHashMap<String, Object> updatedState = new ConcurrentHashMap<>();
//     updatedState.put("key1", "value1");
//     Session updatedSession =
//         sessionService
//             .createSession(APP_NAME, initialUserId, updatedState, initialSessionId)
//             .blockingGet();

//     assertNotNull(updatedSession);
//     assertEquals(initialSessionId, updatedSession.id());
//     assertEquals("value1", updatedSession.state().get("key1"));

//     // Verify by fetching
//     Session fetched =
//         sessionService
//             .getSession(APP_NAME, initialUserId, initialSessionId, Optional.empty())
//             .blockingGet();
//     assertNotNull(fetched);
//     assertEquals("value1", fetched.state().get("key1"));
//   }

//   @Test
//   void testGetSession_nonExistentSession_returnsEmpty() {
//     Maybe<Session> sessionMaybe =
//         sessionService.getSession(APP_NAME, userId, "non_existent_session", Optional.empty());
//     assertTrue(sessionMaybe.isEmpty().blockingGet());
//   }

//   @Test
//   void testListSessions_noSessions_returnsEmptyList() {
//     assertEquals(0, sessionService.listSessions(APP_NAME,
// userId).blockingGet().sessions().size());
//   }

//   @Test
//   void testAppendEvent_multipleEvents_verifiesOrder() throws Exception {
//     sessionService
//         .createSession(APP_NAME, userId, new ConcurrentHashMap<>(), sessionId)
//         .blockingGet();

//     Event event1 =
// Event.builder().id("event1").timestamp(Instant.now().getEpochSecond()).build();
//     Event event2 =
//         Event.builder().id("event2").timestamp(Instant.now().getEpochSecond() + 1).build();

//     sessionService
//         .appendEvent(
//             sessionService.getSession(APP_NAME, userId, sessionId,
// Optional.empty()).blockingGet(),
//             event1)
//         .blockingGet();
//     sessionService
//         .appendEvent(
//             sessionService.getSession(APP_NAME, userId, sessionId,
// Optional.empty()).blockingGet(),
//             event2)
//         .blockingGet();

//     Session fetchedSession =
//         sessionService.getSession(APP_NAME, userId, sessionId, Optional.empty()).blockingGet();
//     assertEquals(2, fetchedSession.events().size());
//     assertEquals("event1", fetchedSession.events().get(0).id());
//     assertEquals("event2", fetchedSession.events().get(1).id());
//   }

//   @Test
//   void testDeleteSession_nonExistentSession_completesSuccessfully() {
//     sessionService
//         .deleteSession(APP_NAME, userId, "non_existent_session")
//         .blockingAwait(5, TimeUnit.SECONDS);
//     // No exception means it completed successfully
//   }

//   @Test
//   void testListEvents_sessionWithNoEvents_returnsEmptyList() throws Exception {
//     sessionService
//         .createSession(APP_NAME, userId, new ConcurrentHashMap<>(), sessionId)
//         .blockingGet();
//     assertEquals(
//         0, sessionService.listEvents(APP_NAME, userId, sessionId).blockingGet().events().size());
//   }

//   @Test
//   void testListEvents_nonExistentSession_returnsEmptyList() {
//     assertEquals(
//         0,
//         sessionService
//             .listEvents(APP_NAME, userId, "non_existent_session")
//             .blockingGet()
//             .events()
//             .size());
//   }

//   @Test
//   void testSaveArtifact_sameFilenameDifferentContent_createsNewVersion() throws Exception {
//     String filename = "versioned_artifact.txt";
//     Part part1 = Part.fromText("Content V1");
//     Part part2 = Part.fromText("Content V2");

//     int v1 =
//         artifactService.saveArtifact(APP_NAME, userId, sessionId, filename, part1).blockingGet();
//     assertEquals(0, v1);

//     int v2 =
//         artifactService.saveArtifact(APP_NAME, userId, sessionId, filename, part2).blockingGet();
//     assertEquals(1, v2);

//     Part loadedV0 =
//         artifactService
//             .loadArtifact(APP_NAME, userId, sessionId, filename, Optional.of(0))
//             .blockingGet();
//     assertEquals("Content V1", loadedV0.text().get());

//     Part loadedV1 =
//         artifactService
//             .loadArtifact(APP_NAME, userId, sessionId, filename, Optional.of(1))
//             .blockingGet();
//     assertEquals("Content V2", loadedV1.text().get());

//     Part loadedLatest =
//         artifactService
//             .loadArtifact(APP_NAME, userId, sessionId, filename, Optional.empty())
//             .blockingGet();
//     assertEquals("Content V2", loadedLatest.text().get());

//     assertEquals(
//         2,
//         artifactService.listVersions(APP_NAME, userId, sessionId,
// filename).blockingGet().size());
//   }

//   @Test
//   void testLoadArtifact_nonExistentArtifact_returnsEmpty() {
//     Maybe<Part> artifactMaybe =
//         artifactService.loadArtifact(
//             APP_NAME, userId, sessionId, "non_existent_artifact.txt", Optional.empty());
//     assertTrue(artifactMaybe.isEmpty().blockingGet());
//   }

//   @Test
//   void testLoadArtifact_nonExistentVersion_returnsEmpty() throws Exception {
//     String filename = "single_version_artifact.txt";
//     Part part = Part.fromText("Only one version");
//     artifactService.saveArtifact(APP_NAME, userId, sessionId, filename, part).blockingGet();

//     Maybe<Part> artifactMaybe =
//         artifactService.loadArtifact(APP_NAME, userId, sessionId, filename, Optional.of(999));
//     assertTrue(artifactMaybe.isEmpty().blockingGet());
//   }

//   @Test
//   void testListArtifactKeys_noArtifacts_returnsEmptyList() {
//     assertEquals(
//         0,
//         artifactService
//             .listArtifactKeys(APP_NAME, userId, sessionId)
//             .blockingGet()
//             .filenames()
//             .size());
//   }

//   @Test
//   void testListVersions_artifactWithNoVersions_returnsEmptyList() {
//     assertEquals(
//         0,
//         artifactService
//             .listVersions(APP_NAME, userId, sessionId, "no_versions.txt")
//             .blockingGet()
//             .size());
//   }

//   @Test
//   void testDeleteArtifact_nonExistentArtifact_completesSuccessfully() {
//     artifactService
//         .deleteArtifact(APP_NAME, userId, sessionId, "non_existent_artifact.txt")
//         .blockingAwait(5, TimeUnit.SECONDS);
//     // No exception means it completed successfully
//   }

//   @Test
//   void testDeleteArtifact_thenLoad_returnsEmpty() throws Exception {
//     String filename = "to_be_deleted.txt";
//     Part part = Part.fromText("This will be deleted");
//     artifactService.saveArtifact(APP_NAME, userId, sessionId, filename, part).blockingGet();

//     artifactService
//         .deleteArtifact(APP_NAME, userId, sessionId, filename)
//         .blockingAwait(5, TimeUnit.SECONDS);

//     Maybe<Part> deletedArtifactMaybe =
//         artifactService.loadArtifact(APP_NAME, userId, sessionId, filename, Optional.empty());
//     assertTrue(deletedArtifactMaybe.isEmpty().blockingGet());
//   }

//   @AfterAll
//   void teardown() {
//     try {
//       if (sessionService != null) {
//         sessionService.close();
//       }
//       if (dbHelper != null) {
//         dbHelper.close(); // Close the CqlSession managed by the helper
//       }
//     } catch (Exception e) {
//       System.err.println("Error during test teardown: " + e.getMessage());
//     }

//     // Clear system properties to avoid affecting other tests or subsequent runs
//     System.clearProperty("cassandra_host");
//     System.clearProperty("cassandra_port");
//     System.clearProperty("cassandra_user");
//     System.clearProperty("cassandra_password");
//     System.clearProperty("cassandra_keyspace");
//   }
// }
