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
import com.google.adk.events.EventActions;
import com.google.adk.utils.ApiFutureUtils;
import com.google.adk.utils.Constants;
import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;
import com.google.cloud.firestore.WriteResult;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FirestoreSessionService implements session management using Google Firestore as the backend
 * storage.
 */
public class FirestoreSessionService implements BaseSessionService {
  private static final Logger logger = LoggerFactory.getLogger(FirestoreSessionService.class);
  private final Firestore firestore;
  private static final String ROOT_COLLECTION_NAME = Constants.ROOT_COLLECTION_NAME;
  private static final String EVENTS_SUBCOLLECTION_NAME = Constants.EVENTS_SUBCOLLECTION_NAME;
  private static final String APP_STATE_COLLECTION = Constants.APP_STATE_COLLECTION;
  private static final String USER_STATE_COLLECTION = Constants.USER_STATE_COLLECTION;
  private static final String SESSION_COLLECTION_NAME = Constants.SESSION_COLLECTION_NAME;
  private static final String USER_ID_KEY = Constants.KEY_USER_ID;
  private static final String APP_NAME_KEY = Constants.KEY_APP_NAME;
  private static final String STATE_KEY = Constants.KEY_STATE;
  private static final String ID_KEY = Constants.KEY_ID;
  private static final String UPDATE_TIME_KEY = Constants.KEY_UPDATE_TIME;
  private static final String TIMESTAMP_KEY = Constants.KEY_TIMESTAMP;

  /** Constructor for FirestoreSessionService. */
  public FirestoreSessionService(Firestore firestore) {
    this.firestore = firestore;
  }

  /** Gets the sessions collection reference for a given userId. */
  private CollectionReference getSessionsCollection(String userId) {
    return firestore
        .collection(ROOT_COLLECTION_NAME)
        .document(userId)
        .collection(SESSION_COLLECTION_NAME);
  }

  /** Creates a new session in Firestore. */
  @Override
  public Single<Session> createSession(
      String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");

          String resolvedSessionId =
              Optional.ofNullable(sessionId)
                  .map(String::trim)
                  .filter(s -> !s.isEmpty())
                  .orElseGet(() -> UUID.randomUUID().toString());

          ConcurrentMap<String, Object> initialState =
              (state == null) ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(state);
          logger.info(
              "Creating session for userId: {} with sessionId: {} and initial state: {}",
              userId,
              resolvedSessionId,
              initialState);
          List<Event> initialEvents = new ArrayList<>();
          Instant now = Instant.now();
          Session newSession =
              Session.builder(resolvedSessionId)
                  .appName(appName)
                  .userId(userId)
                  .state(initialState)
                  .events(initialEvents)
                  .lastUpdateTime(now)
                  .build();

          // Convert Session to a Map for Firestore
          Map<String, Object> sessionData = new HashMap<>();
          sessionData.put(ID_KEY, newSession.id());
          sessionData.put(APP_NAME_KEY, newSession.appName());
          sessionData.put(USER_ID_KEY, newSession.userId());
          sessionData.put(UPDATE_TIME_KEY, newSession.lastUpdateTime().toString());
          sessionData.put(STATE_KEY, newSession.state());

          // Asynchronously write to Firestore and wait for the result
          ApiFuture<WriteResult> future =
              getSessionsCollection(userId).document(resolvedSessionId).set(sessionData);
          future.get(); // Block until the write is complete

          return newSession;
        });
  }

  /***
   * Retrieves a session by appName, userId, and sessionId from Firestore.
   */
  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> configOpt) {
    Objects.requireNonNull(appName, "appName cannot be null");
    Objects.requireNonNull(userId, "userId cannot be null");
    Objects.requireNonNull(sessionId, "sessionId cannot be null");
    Objects.requireNonNull(configOpt, "configOpt cannot be null");

    logger.info("Getting session for userId: {} with sessionId: {}", userId, sessionId);
    ApiFuture<DocumentSnapshot> future = getSessionsCollection(userId).document(sessionId).get();

    return ApiFutureUtils.toMaybe(future)
        .flatMap(
            document -> {
              if (!document.exists()) {
                logger.warn("Session not found for sessionId: {}", sessionId);
                return Maybe.error(new SessionNotFoundException("Session not found: " + sessionId));
              }

              Map<String, Object> data = document.getData();
              if (data == null) {
                logger.warn("Session data is null for sessionId: {}", sessionId);
                return Maybe.empty();
              }

              // Fetch events based on config
              GetSessionConfig config = configOpt.orElse(GetSessionConfig.builder().build());
              CollectionReference eventsCollection =
                  document.getReference().collection(EVENTS_SUBCOLLECTION_NAME);
              Query eventsQuery = eventsCollection.orderBy(TIMESTAMP_KEY);

              if (config.afterTimestamp().isPresent()) {
                eventsQuery =
                    eventsQuery.whereGreaterThan(
                        TIMESTAMP_KEY, config.afterTimestamp().get().toString());
              }

              if (config.numRecentEvents().isPresent()) {
                eventsQuery = eventsQuery.limitToLast(config.numRecentEvents().get());
              }

              ApiFuture<List<QueryDocumentSnapshot>> eventsFuture =
                  ApiFutures.transform(
                      eventsQuery.get(),
                      com.google.cloud.firestore.QuerySnapshot::getDocuments,
                      MoreExecutors.directExecutor());

              return ApiFutureUtils.toSingle(eventsFuture)
                  .map(
                      eventDocs -> {
                        List<Event> events = new ArrayList<>();
                        for (DocumentSnapshot eventDoc : eventDocs) {
                          Event event = eventFromMap(eventDoc.getData(), userId);
                          if (event != null) {
                            events.add(event);
                          }
                        }
                        return events;
                      })
                  .map(
                      events -> {
                        ConcurrentMap<String, Object> state =
                            new ConcurrentHashMap<>((Map<String, Object>) data.get(STATE_KEY));
                        return Session.builder((String) data.get(ID_KEY))
                            .appName((String) data.get(APP_NAME_KEY))
                            .userId((String) data.get(USER_ID_KEY))
                            .lastUpdateTime(Instant.parse((String) data.get(UPDATE_TIME_KEY)))
                            .state(state)
                            .events(events)
                            .build();
                      })
                  .toMaybe();
            });
  }

  /**
   * Reconstructs an Event object from a Map retrieved from Firestore.
   *
   * @param data The map representation of the event.
   * @return An Event object, or null if the data is malformed.
   */
  private Event eventFromMap(Map<String, Object> data, String sessionUserId) {
    if (data == null) {
      return null;
    }
    try {
      String author = safeCast(data.get("author"), String.class, "author");
      String timestampStr = safeCast(data.get(TIMESTAMP_KEY), String.class, "timestamp");
      Map<String, Object> contentMap = safeCast(data.get("content"), Map.class, "content");

      if (author == null || timestampStr == null || contentMap == null) {
        logger.warn(
            "Skipping malformed event data due to missing author, timestamp, or content: {}", data);
        return null;
      }

      Instant timestamp = Instant.parse(timestampStr);

      // Reconstruct Content object
      List<Map> partsList = safeCast(contentMap.get("parts"), List.class, "parts.list");
      List<Part> parts = new ArrayList<>();
      if (partsList != null) {
        for (Map<String, Object> partMap : partsList) {
          Part part = null;
          if (partMap.containsKey("text")) {
            part = Part.fromText((String) partMap.get("text"));
          } else if (partMap.containsKey("functionCall")) {
            part =
                functionCallPartFromMap(
                    safeCast(partMap.get("functionCall"), Map.class, "functionCall"));
          } else if (partMap.containsKey("functionResponse")) {
            part =
                functionResponsePartFromMap(
                    safeCast(partMap.get("functionResponse"), Map.class, "functionResponse"));
          } else if (partMap.containsKey("fileData")) {
            part = fileDataPartFromMap(safeCast(partMap.get("fileData"), Map.class, "fileData"));
          }
          if (part != null) {
            parts.add(part);
          }
        }
      }

      // The role of the content should be 'user' or 'model'.
      // An agent's turn is 'model'. A user's turn is 'user'.
      // A special case is a function response, which is authored by the 'user'
      // but represents a response to a model's function call.
      String role;
      boolean hasFunctionResponse = parts.stream().anyMatch(p -> p.functionResponse().isPresent());

      if (hasFunctionResponse) {
        role = "user"; // Function responses are sent with the 'user' role.
      } else {
        // If the author is the user, the role is 'user'. Otherwise, it's 'model'.
        role = author.equalsIgnoreCase(sessionUserId) ? "user" : "model";
      }
      logger.debug("Reconstructed event role: {}", role);
      Content content = Content.builder().role(role).parts(parts).build();

      return Event.builder()
          .author(author)
          .content(content)
          .timestamp(timestamp.toEpochMilli())
          .build();
    } catch (Exception e) {
      logger.error("Failed to parse event from Firestore data: " + data, e);
      return null;
    }
  }

  /**
   * Constructs a FunctionCall Part from a map representation.
   *
   * @param fcMap The map containing the function call 'name' and 'args'.
   * @return A Part containing the FunctionCall.
   */
  private Part functionCallPartFromMap(Map<String, Object> fcMap) {
    if (fcMap == null) {
      return null;
    }
    String name = (String) fcMap.get("name");
    Map<String, Object> args = safeCast(fcMap.get("args"), Map.class, "functionCall.args");
    return Part.fromFunctionCall(name, args);
  }

  /**
   * Constructs a FunctionResponse Part from a map representation.
   *
   * @param frMap The map containing the function response 'name' and 'response'.
   * @return A Part containing the FunctionResponse.
   */
  private Part functionResponsePartFromMap(Map<String, Object> frMap) {
    if (frMap == null) {
      return null;
    }
    String name = (String) frMap.get("name");
    Map<String, Object> response =
        safeCast(frMap.get("response"), Map.class, "functionResponse.response");
    return Part.fromFunctionResponse(name, response);
  }

  /**
   * Constructs a fileData Part from a map representation.
   *
   * @param fdMap The map containing the file data 'fileUri' and 'mimeType'.
   * @return A Part containing the file data.
   */
  private Part fileDataPartFromMap(Map<String, Object> fdMap) {
    if (fdMap == null) return null;
    String fileUri = (String) fdMap.get("fileUri");
    String mimeType = (String) fdMap.get("mimeType");
    return Part.fromUri(fileUri, mimeType);
  }

  /** Converts an Event object to a Map representation suitable for Firestore storage. */
  private Map<String, Object> eventToMap(Session session, Event event) {
    Map<String, Object> data = new HashMap<>();
    // For user-generated events, the author should be the user's ID.
    // The ADK runner sets the author to "user" for the user's turn.
    if ("user".equalsIgnoreCase(event.author())) {
      data.put("author", session.userId());
    } else {
      data.put("author", event.author());
    }
    data.put(TIMESTAMP_KEY, Instant.ofEpochMilli(event.timestamp()).toString());
    data.put(APP_NAME_KEY, session.appName()); // Persist appName with the event

    Map<String, Object> contentData = new HashMap<>();
    List<Map<String, Object>> partsData = new ArrayList<>();
    Set<String> keywords = new HashSet<>();

    event
        .content()
        .flatMap(Content::parts)
        .ifPresent(
            parts -> {
              for (Part part : parts) {
                Map<String, Object> partData = new HashMap<>();
                part.text()
                    .ifPresent(
                        text -> {
                          partData.put("text", text);
                          // Extract keywords only if there is text
                          if (!text.isEmpty()) {

                            Matcher matcher = Constants.WORD_PATTERN.matcher(text);
                            while (matcher.find()) {
                              String word = matcher.group().toLowerCase(Locale.ROOT);
                              if (!Constants.STOP_WORDS.contains(word)) {
                                keywords.add(word);
                              }
                            }
                          }
                        });
                part.functionCall()
                    .ifPresent(
                        fc -> {
                          Map<String, Object> fcMap = new HashMap<>();
                          fc.name().ifPresent(name -> fcMap.put("name", name));
                          fc.args().ifPresent(args -> fcMap.put("args", args));
                          if (!fcMap.isEmpty()) {
                            partData.put("functionCall", fcMap);
                          }
                        });
                part.functionResponse()
                    .ifPresent(
                        fr -> {
                          Map<String, Object> frMap = new HashMap<>();
                          fr.name().ifPresent(name -> frMap.put("name", name));
                          fr.response().ifPresent(response -> frMap.put("response", response));
                          if (!frMap.isEmpty()) {
                            partData.put("functionResponse", frMap);
                          }
                        });
                part.fileData()
                    .ifPresent(
                        fd -> {
                          Map<String, Object> fdMap = new HashMap<>();
                          // When serializing, we assume the artifact service has already converted
                          // the
                          // bytes to a GCS URI.
                          fd.fileUri().ifPresent(uri -> fdMap.put("fileUri", uri));
                          fd.mimeType().ifPresent(mime -> fdMap.put("mimeType", mime));
                          if (!fdMap.isEmpty()) {
                            partData.put("fileData", fdMap);
                          }
                        });

                // Add other part types if necessary
                partsData.add(partData);
              }
            });

    logger.info("Serialized parts data before saving: {}", partsData);
    contentData.put("parts", partsData);
    data.put("content", contentData);
    if (!keywords.isEmpty()) {
      data.put("keywords", new ArrayList<>(keywords)); // Firestore works well with Lists
    }

    return data;
  }

  /** Lists all sessions for a given appName and userId. */
  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");

          logger.info("Listing sessions for userId: {}", userId);

          Query query = getSessionsCollection(userId).whereEqualTo(APP_NAME_KEY, appName);

          ApiFuture<List<QueryDocumentSnapshot>> querySnapshot =
              ApiFutures.transform(
                  query.get(), // Query is already scoped to the user
                  snapshot -> snapshot.getDocuments(),
                  MoreExecutors.directExecutor());

          List<Session> sessions = new ArrayList<>();
          for (DocumentSnapshot document : querySnapshot.get()) {
            Map<String, Object> data = document.getData();
            if (data != null) {
              // Create a session object with empty events and state, as per
              // InMemorySessionService
              Session session =
                  Session.builder((String) data.get(ID_KEY))
                      .appName((String) data.get(APP_NAME_KEY))
                      .userId((String) data.get(USER_ID_KEY))
                      .lastUpdateTime(Instant.parse((String) data.get(UPDATE_TIME_KEY)))
                      .state(new ConcurrentHashMap<>()) // Empty state
                      .events(new ArrayList<>()) // Empty events
                      .build();
              sessions.add(session);
            }
          }

          return ListSessionsResponse.builder().sessions(sessions).build();
        });
  }

  /** Deletes a session and all its associated events from Firestore. */
  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    return Completable.fromAction(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");
          Objects.requireNonNull(sessionId, "sessionId cannot be null");

          logger.info("Deleting session for userId: {} with sessionId: {}", userId, sessionId);

          // Reference to the session document
          com.google.cloud.firestore.DocumentReference sessionRef =
              getSessionsCollection(userId).document(sessionId);

          // 1. Fetch all events in the subcollection to delete them in batches.
          CollectionReference eventsRef = sessionRef.collection(EVENTS_SUBCOLLECTION_NAME);
          com.google.api.core.ApiFuture<com.google.cloud.firestore.QuerySnapshot> eventsQuery =
              eventsRef.get();
          List<QueryDocumentSnapshot> eventDocuments = eventsQuery.get().getDocuments();

          if (!eventDocuments.isEmpty()) {
            List<ApiFuture<List<WriteResult>>> batchCommitFutures = new ArrayList<>();
            // Firestore batches can have up to 500 operations.
            for (int i = 0; i < eventDocuments.size(); i += 500) {
              WriteBatch batch = firestore.batch();
              List<QueryDocumentSnapshot> chunk =
                  eventDocuments.subList(i, Math.min(i + 500, eventDocuments.size()));
              for (QueryDocumentSnapshot doc : chunk) {
                batch.delete(doc.getReference());
              }
              batchCommitFutures.add(batch.commit());
            }
            // Wait for all batch deletions to complete.
            ApiFutures.allAsList(batchCommitFutures).get();
          }

          // 2. Delete the session document itself
          sessionRef.delete().get(); // Block until deletion is complete

          logger.info("Successfully deleted session: {}", sessionId);
        });
  }

  /** Lists all events for a given appName, userId, and sessionId. */
  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(appName, "appName cannot be null");
          Objects.requireNonNull(userId, "userId cannot be null");
          Objects.requireNonNull(sessionId, "sessionId cannot be null");

          logger.info("Listing events for userId: {} with sessionId: {}", userId, sessionId);

          // First, check if the session document exists.
          ApiFuture<DocumentSnapshot> sessionFuture =
              getSessionsCollection(userId).document(sessionId).get();
          DocumentSnapshot sessionDocument = sessionFuture.get(); // Block for the result

          if (!sessionDocument.exists()) {
            logger.warn(
                "Session not found for sessionId: {}. Returning empty list of events.", sessionId);
            throw new SessionNotFoundException(appName + "," + userId + "," + sessionId);
          }

          // Session exists, now fetch the events.
          CollectionReference eventsCollection =
              sessionDocument.getReference().collection(EVENTS_SUBCOLLECTION_NAME);
          Query eventsQuery = eventsCollection.orderBy(TIMESTAMP_KEY);

          ApiFuture<List<QueryDocumentSnapshot>> eventsFuture =
              ApiFutures.transform(
                  eventsQuery.get(),
                  querySnapshot -> querySnapshot.getDocuments(),
                  MoreExecutors.directExecutor());

          List<Event> events = new ArrayList<>();
          for (DocumentSnapshot eventDoc : eventsFuture.get()) {
            Event event = eventFromMap(eventDoc.getData(), userId);
            if (event != null) {
              events.add(event);
            }
          }
          logger.info("Returning {} events for sessionId: {}", events.size(), sessionId);
          return ListEventsResponse.builder().events(events).build();
        });
  }

  /** Appends an event to a session, updating the session state and persisting to Firestore. */
  @CanIgnoreReturnValue
  @Override
  public Single<Event> appendEvent(Session session, Event event) {
    return Single.fromCallable(
        () -> {
          Objects.requireNonNull(session, "session cannot be null");
          Objects.requireNonNull(session.appName(), "session.appName cannot be null");
          Objects.requireNonNull(session.userId(), "session.userId cannot be null");
          Objects.requireNonNull(session.id(), "session.id cannot be null");
          logger.info("appendEvent(S,E) - appending event to sessionId: {}", session.id());
          String appName = session.appName();
          String userId = session.userId();
          String sessionId = session.id();

          List<ApiFuture<WriteResult>> futures = new ArrayList<>();

          // --- Update User/App State ---
          EventActions actions = event.actions();
          if (actions != null) {
            Map<String, Object> stateDelta = actions.stateDelta();
            if (stateDelta != null && !stateDelta.isEmpty()) {
              AtomicBoolean sessionStateChanged = new AtomicBoolean(false);
              Map<String, Object> appStateUpdates = new HashMap<>();
              Map<String, Object> userStateUpdates = new HashMap<>();

              stateDelta.forEach(
                  (key, value) -> {
                    if (key.startsWith("_app_")) {
                      appStateUpdates.put(key.substring("_app_".length()), value);
                    } else if (key.startsWith("_user_")) {
                      userStateUpdates.put(key.substring("_user_".length()), value);
                    } else {
                      // Regular session state
                      sessionStateChanged.set(true);
                      if (value == null) {
                        session.state().remove(key);
                      } else {
                        session.state().put(key, value);
                      }
                    }
                  });

              if (!appStateUpdates.isEmpty()) {
                futures.add(
                    firestore
                        .collection(APP_STATE_COLLECTION)
                        .document(appName)
                        .set(appStateUpdates, com.google.cloud.firestore.SetOptions.merge()));
              }
              if (!userStateUpdates.isEmpty()) {
                futures.add(
                    firestore
                        .collection(USER_STATE_COLLECTION)
                        .document(appName)
                        .collection("users")
                        .document(userId)
                        .set(userStateUpdates, com.google.cloud.firestore.SetOptions.merge()));
              }

              // Only update the session state if it actually changed.
              if (sessionStateChanged.get()) {
                futures.add(
                    getSessionsCollection(userId)
                        .document(sessionId)
                        .update(STATE_KEY, session.state()));
              }
            }
          }

          // Manually add the event to the session's internal list.
          session.events().add(event);
          session.lastUpdateTime(getInstantFromEvent(event));

          // --- Persist event to Firestore ---
          Map<String, Object> eventData = eventToMap(session, event);
          eventData.put(USER_ID_KEY, userId);
          eventData.put(APP_NAME_KEY, appName);
          // Generate a new ID for the event document
          String eventId =
              getSessionsCollection(userId)
                  .document(sessionId)
                  .collection(EVENTS_SUBCOLLECTION_NAME)
                  .document()
                  .getId();
          futures.add(
              getSessionsCollection(userId)
                  .document(sessionId)
                  .collection(EVENTS_SUBCOLLECTION_NAME)
                  .document(eventId)
                  .set(eventData));

          // --- Update the session document in Firestore ---
          Map<String, Object> sessionUpdates = new HashMap<>();
          sessionUpdates.put(
              UPDATE_TIME_KEY, session.lastUpdateTime().toString()); // Always update the timestamp
          futures.add(getSessionsCollection(userId).document(sessionId).update(sessionUpdates));

          // Block and wait for all async Firestore operations to complete.
          // This makes the method effectively synchronous within the reactive chain,
          // ensuring the database is consistent before the runner proceeds.
          ApiFutures.allAsList(futures).get();

          logger.info("Event appended successfully to sessionId: {}", sessionId);
          logger.info("Returning appended event: {}", event.stringifyContent());

          return event;
        });
  }

  /** Converts an event's timestamp to an Instant. Adapt based on actual Event structure. */
  private Instant getInstantFromEvent(Event event) {
    // The event timestamp is in milliseconds since the epoch.
    return Instant.ofEpochMilli(event.timestamp());
  }

  /**
   * Safely casts an object to a specific type, logging a warning and returning null if the cast
   * fails.
   *
   * @param obj The object to cast.
   * @param clazz The target class to cast to.
   * @param fieldName The name of the field being cast, for logging purposes.
   * @return The casted object, or null if the object is not an instance of the target class.
   * @param <T> The target type.
   */
  private <T> T safeCast(Object obj, Class<T> clazz, String fieldName) {
    return safeCast(obj, clazz, fieldName, null);
  }

  /**
   * Safely casts an object to a specific type, logging a warning and returning a default value if
   * the cast fails.
   *
   * @param obj The object to cast.
   * @param clazz The target class to cast to.
   * @param fieldName The name of the field being cast, for logging purposes.
   * @param defaultValue The value to return if the cast fails.
   * @return The casted object, or the default value if the object is not an instance of the target
   *     class.
   * @param <T> The target type.
   */
  private <T> T safeCast(Object obj, Class<T> clazz, String fieldName, T defaultValue) {
    if (obj == null) {
      return defaultValue;
    }
    if (clazz.isInstance(obj)) {
      return clazz.cast(obj);
    }
    logger.warn(
        "Type mismatch for field '{}'. Expected {} but got {}. Returning default value.",
        fieldName,
        clazz.getName(),
        obj.getClass().getName());
    return defaultValue;
  }
}
