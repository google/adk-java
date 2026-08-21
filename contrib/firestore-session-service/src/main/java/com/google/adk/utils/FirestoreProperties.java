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
package com.google.adk.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder class to test that the FirestoreProperties file is correctly included in the test
 * resources.
 */
public class FirestoreProperties {

  /** Logger for this class. */
  private static final Logger logger = LoggerFactory.getLogger(FirestoreProperties.class);

  /** The default property file name. */
  private static final String DEFAULT_PROPERTY_FILE_NAME = "adk-firestore.properties";

  /** The template for the environment-specific property file name. */
  private static final String ENV_PROPERTY_FILE_TEMPLATE = "adk-firestore-%s.properties";

  private static volatile FirestoreProperties INSTANCE = new FirestoreProperties();

  private final Properties properties;

  private final String firebaseRootCollectionNameKey = "firebase.root.collection.name";
  private final String firebaseRootCollectionDefaultValue = "adk-session";

  private final String gcsAdkBucketNameKey = "gcs.adk.bucket.name";

  private final String keywordExtractionStopWordsKey = "keyword.extraction.stopwords";

  /** Default stop words for keyword extraction, used as a fallback. */
  private final Set<String> DEFAULT_STOP_WORDS =
      new HashSet<>(
          Arrays.asList(
              "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into",
              "is", "it", "i", "no", "not", "of", "on", "or", "such", "that", "the", "their",
              "then", "there", "these", "they", "this", "to", "was", "will", "with", "what",
              "where", "when", "why", "how", "help", "need", "like", "make", "got", "would",
              "could", "should"));

  /**
   * Private constructor to initialize the properties by attempting to load the environment-specific
   * file first, then falling back to the default.
   */
  private FirestoreProperties() {
    this.properties = new Properties();
    InputStream inputStream = null;

    // 1. Check for the "env" environment variable
    String env = System.getenv("env");
    if (env != null && !env.trim().isEmpty()) {
      String environmentSpecificFileName = String.format(ENV_PROPERTY_FILE_TEMPLATE, env);
      inputStream = loadResourceAsStream(environmentSpecificFileName);
    }

    // 2. If the environment-specific file was not found, try the default
    if (inputStream == null) {
      inputStream = loadResourceAsStream(DEFAULT_PROPERTY_FILE_NAME);
    }

    // 3. Load the properties from the found input stream
    if (inputStream != null) {
      try {
        this.properties.load(inputStream);
      } catch (IOException e) {
        logger.error("Failed to load properties file.", e);
        throw new RuntimeException("Failed to load properties file.", e);
      } finally {
        try {
          inputStream.close();
        } catch (IOException e) {
          // Log and ignore
          logger.warn("Failed to close properties file input stream.", e);
        }
      }
    }
  }

  /**
   * Helper method to load a resource as a stream.
   *
   * @param resourceName the name of the resource to load
   * @return an InputStream for the resource, or null if not found
   */
  private InputStream loadResourceAsStream(String resourceName) {
    return FirestoreProperties.class.getClassLoader().getResourceAsStream(resourceName);
  }

  /**
   * Functionality to read a property from the loaded properties file.
   *
   * @param key the property key
   * @return the property value, or null if not found
   */
  public String getProperty(String key) {
    return this.properties.getProperty(key);
  }

  /**
   * Get the root collection name from the properties file, or return the default value if not
   * found.
   *
   * @return the root collection name
   */
  public String getFirebaseRootCollectionName() {
    return this.properties.getProperty(
        firebaseRootCollectionNameKey, firebaseRootCollectionDefaultValue);
  }

  /**
   * Get the stop words for keyword extraction from the properties file, or return the default set
   * if not found.
   *
   * @return the set of stop words
   */
  public Set<String> getStopWords() {
    String stopwordsProp = this.getProperty(keywordExtractionStopWordsKey);
    if (stopwordsProp != null && !stopwordsProp.trim().isEmpty()) {
      return new HashSet<>(Arrays.asList(stopwordsProp.split("\\s*,\\s*")));
    }
    // Fallback to the default hardcoded list if the property is not set
    return DEFAULT_STOP_WORDS;
  }

  /**
   * Get the GCS ADK bucket name from the properties file.
   *
   * @return the GCS ADK bucket name
   */
  public String getGcsAdkBucketName() {
    return this.properties.getProperty(gcsAdkBucketNameKey);
  }

  /**
   * Returns a singleton instance of FirestoreProperties.
   *
   * @return the FirestoreProperties instance
   */
  public static FirestoreProperties getInstance() {
    if (INSTANCE == null) {

      INSTANCE = new FirestoreProperties();
    }
    return INSTANCE;
  }

  /** Resets the singleton instance. For testing purposes only. */
  public static void resetForTest() {
    INSTANCE = null;
  }
}
