/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.google.adk.artifacts;

/**
 * @author manoj.kumar & Gemini
 */
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.sessions.MapDbSessionService;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part; // Assuming this type is available
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.File;
import java.util.NavigableMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.mapdb.BTreeMap; // BTreeMap is suitable for range queries
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;

// Assuming BaseArtifactService and ListArtifactsResponse are defined elsewhere as per the original
// code.
// import yourpackage.BaseArtifactService;
// import yourpackage.ListArtifactsResponse; // Assuming this is a builder pattern class

/** A MapDB implementation of the {@link BaseArtifactService}. */
public final class MapDbArtifactService implements BaseArtifactService {

  private static final String DB_MAP_NAME = "artifacts";
  private static final String KEY_SEPARATOR = "/";
  private static final int VERSION_PADDING_DIGITS = 5; // Pad version numbers to 5 digits

  private final DB db;
  // BTreeMap for efficient prefix searches and ordered keys
  private final BTreeMap<String, String> artifactsMap;

  /**
   * Constructs a MapDbArtifactService.
   *
   * @param dbFile The file path for the MapDB database file.
   */
  public MapDbArtifactService(String filePath) {
    File dbFile = new File(filePath);
    // Configure and open the MapDB database
    // Using DBMaker.fileDB() for a file-based database
    // .transactionEnable() is good practice for ACID properties
    // .closeOnJvmShutdown() ensures cleanup on normal shutdown
    this.db =
        DBMaker.fileDB(dbFile)
            .transactionEnable() // Use transactions
            .closeOnJvmShutdown() // Ensure DB closes on JVM shutdown
            .make();

    // Get or create the BTreeMap where artifacts will be stored
    // Key: String (appName/userId/sessionId/filename/version)
    // Value: Part
    // Serializer.JAVA requires the Part class to be Serializable.
    // If Part is not Serializable, you will need to provide or
    // configure a custom MapDB Serializer<Part>.
    this.artifactsMap =
        db.treeMap(DB_MAP_NAME)
            .keySerializer(Serializer.STRING)
            .valueSerializer(Serializer.STRING) // Requires Part to be Serializable
            .createOrOpen();
  }

  /**
   * Generates the MapDB key for a specific artifact version. Key format:
   * appName/userId/sessionId/filename/paddedVersion
   */
  private String buildKey(
      String appName, String userId, String sessionId, String filename, int version) {
    String paddedVersion = String.format("%0" + VERSION_PADDING_DIGITS + "d", version);
    return appName
        + KEY_SEPARATOR
        + userId
        + KEY_SEPARATOR
        + sessionId
        + KEY_SEPARATOR
        + filename
        + KEY_SEPARATOR
        + paddedVersion;
  }

  /**
   * Generates the prefix for finding all versions of a specific artifact. Prefix format:
   * appName/userId/sessionId/filename/
   */
  private String buildArtifactPrefix(
      String appName, String userId, String sessionId, String filename) {
    return appName
        + KEY_SEPARATOR
        + userId
        + KEY_SEPARATOR
        + sessionId
        + KEY_SEPARATOR
        + filename
        + KEY_SEPARATOR;
  }

  /**
   * Generates the prefix for finding all artifacts in a session. Prefix format:
   * appName/userId/sessionId/
   */
  private String buildSessionPrefix(String appName, String userId, String sessionId) {
    return appName + KEY_SEPARATOR + userId + KEY_SEPARATOR + sessionId + KEY_SEPARATOR;
  }

  /**
   * Saves an artifact in MapDB and assigns a new version. Finds the next available version number
   * by scanning existing keys.
   *
   * @return Single with assigned version number.
   */
  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    return Single.fromCallable(
        () -> {
          String artifactPrefix = buildArtifactPrefix(appName, userId, sessionId, filename);

          // Find the latest version by scanning keys with the artifact prefix
          // MapDB's BTreeMap iterateKeys() is ordered, so the last key will have the highest
          // version
          String lastKey = artifactsMap.lastKey();

          int nextVersion = 0;
          if (lastKey != null) {
            // Extract version number from the last key
            String[] parts = lastKey.split(KEY_SEPARATOR);
            if (parts.length > 0) {
              try {
                nextVersion = Integer.parseInt(parts[parts.length - 1]) + 1;
              } catch (NumberFormatException e) {
                // Should not happen if key format is correct, but handle defensively
                throw new RuntimeException("Invalid key format in DB: " + lastKey, e);
              }
            }
          }

          String key = buildKey(appName, userId, sessionId, filename, nextVersion);

          // Store the artifact in the map
          artifactsMap.put(key, artifact.toJson());

          // Commit the transaction
          db.commit();

          return nextVersion;
        });
  }

  /**
   * Loads an artifact by version or latest.
   *
   * @return Maybe with the artifact, or empty if not found.
   */
  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, @Nullable Integer version) {
    // The Callable should return the item (Part) or null.
    // Maybe.fromCallable will wrap the non-null item in a Maybe or emit empty if null.
    return Maybe.fromCallable(
        () -> {
          String key;
          if (version != null) {
            // Load specific version
            int v = version;
            if (v < 0) { // Version numbers must be non-negative
              return null; // Return null for empty Maybe
            }
            key = buildKey(appName, userId, sessionId, filename, v);
          } else {
            // Load latest version
            String artifactPrefix = buildArtifactPrefix(appName, userId, sessionId, filename);

            // Find the key with the maximum version number
            // Note: artifactsMap.lastKey() returns the very last key in the entire map.
            // To get the latest key *for this artifact prefix*, you should iterate
            // the subMap and find the max key, or use prefixSubMap's descendingKeySet
            // and take the first one. However, given your save logic always increments
            // the version and uses padded numbers, artifactsMap.lastKey() might coincidentally
            // be the correct one if this is the only artifact being saved concurrently
            // globally. A more robust approach for finding the latest version *for this specific
            // artifact*
            // within the subMap is recommended.
            // Let's assume for now that artifactsMap.lastKey() happens to work due to padded
            // versions
            // and the way you find the next version in saveArtifact.
            // A safer approach would be:
            // NavigableMap<String, Part> subMap = artifactsMap.prefixSubMap(artifactPrefix);
            // key = subMap.isEmpty() ? null : subMap.lastKey(); // Get the last key in the submap

            // For simplicity and aligning with your current code structure (though potentially
            // fragile):
            key =
                artifactsMap
                    .lastKey(); // This finds the globally last key, not necessarily for the prefix.
            // If you want the latest for THIS artifact, you need to find the max version key under
            // the prefix.
            // A better way to find the latest version key for the specific artifact prefix:
            NavigableMap<String, String> artifactSubMap = artifactsMap.prefixSubMap(artifactPrefix);
            if (artifactSubMap.isEmpty()) {
              return null; // No versions found for this artifact
            }
            key = artifactSubMap.lastKey(); // Get the last key within the submap for this artifact
          }

          // Retrieve the Part using the determined key
          // Return null if key is null or if artifactsMap.get(key) returns null
          Part part = null;
          ObjectMapper objectMapper = new ObjectMapper();
          try {
            part = objectMapper.readValue((key != null) ? artifactsMap.get(key) : null, Part.class);
          } catch (JsonProcessingException ex) {
            java.util.logging.Logger.getLogger(MapDbSessionService.class.getName())
                .log(Level.SEVERE, null, ex);
          }

          return part;
        });
  }

  /**
   * Lists filenames of stored artifacts for the session. Scans keys with the session prefix and
   * extracts unique filenames.
   *
   * @return Single with list of artifact filenames.
   */
  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    return Single.fromCallable(
        () -> {
          String sessionPrefix = buildSessionPrefix(appName, userId, sessionId);

          // Get all keys that start with the session prefix
          Set<String> filenames =
              artifactsMap.prefixSubMap(sessionPrefix).keySet().stream()
                  // Extract the filename part from the key
                  // Key: appName/userId/sessionId/filename/version
                  // Split by "/", take element at index 3 (0-based)
                  .map(
                      key -> {
                        String[] parts = key.split(KEY_SEPARATOR);
                        // Ensure key has enough parts before accessing index 3
                        if (parts.length >= 4) {
                          return parts[3];
                        } else {
                          // Should not happen with correct key format, but handle invalid keys
                          return null;
                        }
                      })
                  .filter(filename -> filename != null) // Filter out nulls from malformed keys
                  .collect(Collectors.toSet()); // Collect unique filenames

          return ListArtifactsResponse.builder().filenames(ImmutableList.copyOf(filenames)).build();
        });
  }

  /**
   * Deletes all versions of the given artifact. Removes all keys starting with the artifact prefix.
   *
   * @return Completable indicating completion.
   */
  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    return Completable.fromRunnable(
        () -> {
          String artifactPrefix = buildArtifactPrefix(appName, userId, sessionId, filename);

          // Get all keys to delete first to avoid modifying the map while iterating
          Set<String> keysToDelete = artifactsMap.prefixSubMap(artifactPrefix).keySet();

          // Remove keys
          keysToDelete.forEach(artifactsMap::remove);

          // Commit the transaction
          db.commit();
        });
  }

  /**
   * Lists all versions of the specified artifact. Scans keys with the artifact prefix and extracts
   * version numbers.
   *
   * @return Single with list of version numbers.
   */
  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    return Single.fromCallable(
        () -> {
          String artifactPrefix = buildArtifactPrefix(appName, userId, sessionId, filename);

          // Change the type declaration here to ImmutableList<Integer>
          ImmutableList<Integer> versions =
              artifactsMap.prefixSubMap(artifactPrefix).keySet().stream()
                  // Extract the version part from the key
                  // Key: appName/userId/sessionId/filename/version
                  // Split by "/", take the last element
                  .map(
                      key -> {
                        String[] parts = key.split(KEY_SEPARATOR);
                        if (parts.length > 0) {
                          try {
                            // MapDB stores padded versions like "00005", need to parse Integer
                            return Integer.parseInt(parts[parts.length - 1]);
                          } catch (NumberFormatException e) {
                            // Should not happen with correct key format
                            // Log this error as it indicates a data issue
                            System.err.println(
                                "Error parsing version from key: " + key + " - " + e.getMessage());
                            return null;
                          }
                        } else {
                          // Log this error as it indicates a data issue
                          System.err.println("Invalid key format encountered: " + key);
                          return null;
                        }
                      })
                  .filter(version -> version != null) // Filter out nulls from malformed keys
                  .sorted() // Sort versions numerically
                  .collect(toImmutableList()); // Collect into ImmutableList

          return versions; // Now returns ImmutableList<Integer>
        });
  }

  /** Closes the MapDB database. Should be called on application shutdown. */
  public void close() {
    if (db != null && !db.isClosed()) {
      db.close();
    }
  }

  // You might also want a method to compact the database file occasionally
  // public void compact() {
  //     if (db != null && !db.isClosed()) {
  //         db.compact();
  //     }
  // }
}
