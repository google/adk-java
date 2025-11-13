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

import com.google.adk.agents.BaseAgent;
import com.google.adk.memory.FirestoreMemoryService;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.sessions.FirestoreSessionService;
import com.google.adk.utils.FirestoreProperties;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.storage.StorageOptions;

/** FirestoreDatabaseRunner */
public class FirestoreDatabaseRunner extends Runner {

  /** Constructor for FirestoreDatabaseRunner */
  public FirestoreDatabaseRunner(BaseAgent baseAgent, Firestore firestore) {
    this(baseAgent, baseAgent.name(), new java.util.ArrayList<>(), firestore);
  }

  /** Constructor for FirestoreDatabaseRunner with appName */
  public FirestoreDatabaseRunner(BaseAgent baseAgent, String appName, Firestore firestore) {
    this(baseAgent, appName, new java.util.ArrayList<>(), firestore);
  }

  /** Constructor for FirestoreDatabaseRunner with parent runners */
  public FirestoreDatabaseRunner(
      BaseAgent baseAgent,
      String appName,
      java.util.List<BasePlugin> plugins,
      Firestore firestore) {
    super(
        baseAgent,
        appName,
        new com.google.adk.artifacts.GcsArtifactService(
            getBucketNameFromEnv(), StorageOptions.getDefaultInstance().getService()),
        new FirestoreSessionService(firestore),
        new FirestoreMemoryService(firestore),
        plugins);
  }

  /** Gets the GCS bucket name from the environment variable ADK_GCS_BUCKET_NAME. */
  private static String getBucketNameFromEnv() {
    String bucketName = FirestoreProperties.getInstance().getGcsAdkBucketName();
    if (bucketName == null || bucketName.trim().isEmpty()) {
      throw new RuntimeException(
          "Required property 'gcs.adk.bucket.name' is not set. This"
              + " is needed for the GcsArtifactService.");
    }
    return bucketName;
  }
}
