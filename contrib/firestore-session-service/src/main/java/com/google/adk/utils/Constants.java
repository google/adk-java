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

import java.util.Set;
import java.util.regex.Pattern;

/** Constants used across Firestore session service tests. */
public class Constants {

  /** user events collections */
  public static final String EVENTS_SUBCOLLECTION_NAME = "user-event";

  /** agent app state collection */
  public static final String APP_STATE_COLLECTION = "app-state";

  /** user state colection */
  public static final String USER_STATE_COLLECTION = "user-state";

  /** session collection name */
  public static final String SESSION_COLLECTION_NAME = "sessions";

  /** userId */
  public static final String KEY_USER_ID = "userId";

  /** appName */
  public static final String KEY_APP_NAME = "appName";

  /** timestamp */
  public static final String KEY_TIMESTAMP = "timestamp";

  /** state */
  public static final String KEY_STATE = "state";

  /** id */
  public static final String KEY_ID = "id";

  /** updateTime */
  public static final String KEY_UPDATE_TIME = "updateTime";

  /** user */
  public static final String KEY_USER = "user";

  /** model */
  public static final String KEY_MODEL = "model";

  /** author */
  public static final String KEY_AUTHOR = "author";

  /** Stop words for keyword extraction, loaded from properties. */
  public static final Set<String> STOP_WORDS = FirestoreProperties.getInstance().getStopWords();

  /** Pattern to match words for keyword extraction. */
  public static final Pattern WORD_PATTERN = Pattern.compile("[A-Za-z]+");

  /** root collection name fof firestore */
  public static final String ROOT_COLLECTION_NAME =
      FirestoreProperties.getInstance().getFirebaseRootCollectionName();

  /** private constrctor */
  private Constants() {
    // Prevent instantiation.
  }
}
