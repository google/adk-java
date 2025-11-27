package com.google.adk.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** JUnit 4 test for FirestoreProperties. */
public class FirestorePropertiesTest {

  private static final String ENV_VAR_NAME = "env";

  /**
   * Helper method to set an environment variable using reflection. This is needed because
   * System.getenv() returns an unmodifiable map.
   */
  private static void setEnv(String key, String value) throws Exception {
    try {
      Map<String, String> env = System.getenv();
      Field field = env.getClass().getDeclaredField("m");
      field.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, String> mutableEnv = (Map<String, String>) field.get(env);
      if (value == null) {
        mutableEnv.remove(key);
      } else {
        mutableEnv.put(key, value);
      }
    } catch (NoSuchFieldException e) {
      // For other OS/JVMs that use a different internal class
      Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
      Field theEnvironmentField = processEnvironmentClass.getDeclaredField("theEnvironment");
      theEnvironmentField.setAccessible(true);
      @SuppressWarnings("unchecked")
      Map<String, String> mutableEnv = (Map<String, String>) theEnvironmentField.get(null);
      if (value == null) {
        mutableEnv.remove(key);
      } else {
        mutableEnv.put(key, value);
      }
    }
  }

  /** Clears the 'env' variable before each test to ensure isolation. */
  @BeforeEach
  public void setup() {
    try {
      // Clear the variable to start from a clean state for each test
      // Also reset the singleton instance to force reloading properties
      FirestoreProperties.resetForTest();
      setEnv(ENV_VAR_NAME, null);
    } catch (Exception e) {
      fail("Failed to set up environment for test: " + e.getMessage());
    }
  }

  /** A cleanup step is good practice, though @Before handles our primary need. */
  @AfterEach
  public void teardown() {
    try {
      FirestoreProperties.resetForTest();
      setEnv(ENV_VAR_NAME, null);
    } catch (Exception e) {
      fail("Failed to tear down environment for test: " + e.getMessage());
    }
  }

  /**
   * Tests that the default properties are loaded when the 'env' environment variable is not set.
   */
  @Test
  void shouldLoadDefaultPropertiesWhenEnvIsNotSet() {
    FirestoreProperties props = FirestoreProperties.getInstance();

    assertEquals("default-adk-session", props.getFirebaseRootCollectionName());
    assertEquals("default_value", props.getProperty("another.default.property"));
    assertEquals("common_default", props.getProperty("common.property"));
    assertNull(props.getProperty("non.existent.property"));
  }

  /**
   * Tests that the default properties are loaded when the 'env' environment variable is set to an
   *
   * @throws Exception
   */
  @Test
  void shouldLoadDefaultPropertiesWhenEnvIsEmpty() throws Exception {
    setEnv(ENV_VAR_NAME, "");
    FirestoreProperties props = FirestoreProperties.getInstance();

    assertEquals("default-adk-session", props.getFirebaseRootCollectionName());
    assertEquals("default_value", props.getProperty("another.default.property"));
  }

  /**
   * Tests that the 'dev' properties are loaded when the 'env' environment variable is set to 'dev'.
   *
   * @throws Exception
   */
  @Test
  void shouldLoadDevPropertiesWhenEnvIsSetToDev() throws Exception {
    setEnv(ENV_VAR_NAME, "dev");
    FirestoreProperties props = FirestoreProperties.getInstance();

    assertEquals("dev-adk-session-override", props.getFirebaseRootCollectionName());
    assertEquals("dev_value", props.getProperty("dev.specific.property"));
    assertEquals("common_dev", props.getProperty("common.property")); // Check overridden property
    // In JUnit 5, the message is the second argument.
    assertNull(
        props.getProperty("another.default.property"),
        "Default-only property should not be present");
  }

  /**
   * Tests that the default properties are loaded when the 'env' environment variable is set to a
   * non-existent environment.
   *
   * @throws Exception
   */
  @Test
  void shouldFallbackToDefaultWhenEnvSpecificFileNotFound() throws Exception {
    // Assuming adk-firestore-nonexistent.properties does not exist
    setEnv(ENV_VAR_NAME, "nonexistent");
    FirestoreProperties props = FirestoreProperties.getInstance();

    assertEquals("default-adk-session", props.getFirebaseRootCollectionName());
    assertEquals("default_value", props.getProperty("another.default.property"));
  }

  /**
   * Tests that the hardcoded default is returned when the property file has no entry for the key.
   *
   * @throws Exception
   */
  @Test
  void shouldReturnHardcodedDefaultIfPropertyFileHasNoEntry() throws Exception {
    // This test requires 'src/test/resources/adk-firestore-empty.properties'
    // which contains some properties but NOT 'firebase.root.collection.name'
    setEnv(ENV_VAR_NAME, "empty");
    FirestoreProperties props = FirestoreProperties.getInstance();

    // Should fall back to the hardcoded default value
    assertEquals("adk-session", props.getFirebaseRootCollectionName());
    // Verify that it did load the correct file
    assertEquals("other_value", props.getProperty("other.key"));
  }

  @Test
  void getInstanceShouldReturnAnInstance() {
    assertNotNull(FirestoreProperties.getInstance());
  }

  /**
   * Tests that the default stop words are returned when the property is not set in the file.
   *
   * @throws Exception
   */
  @Test
  void shouldReturnDefaultStopWordsWhenPropertyNotSet() throws Exception {
    // Set env to load a properties file without the stopwords key
    setEnv(ENV_VAR_NAME, "empty");
    FirestoreProperties props = FirestoreProperties.getInstance();

    // The expected default stop words, matching the hardcoded list in FirestoreProperties
    HashSet<String> expectedStopWords =
        new HashSet<>(
            Arrays.asList(
                "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into",
                "is", "it", "i", "no", "not", "of", "on", "or", "such", "that", "the", "their",
                "then", "there", "these", "they", "this", "to", "was", "will", "with", "what",
                "where", "when", "why", "how", "help", "need", "like", "make", "got", "would",
                "could", "should"));

    assertEquals(expectedStopWords, props.getStopWords());
  }
}
