/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.sessions;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import org.mapdb.DB;
import org.mapdb.DBMaker;

/** Manages a singleton instance of a MapDB database. */
public final class MapDbManager {

  private static volatile DB dbInstance;
  private static final Object lock = new Object();

  private MapDbManager() {
    // Private constructor to prevent instantiation
  }

  /**
   * Returns the singleton instance of the MapDB database.
   *
   * @param filePath The path to the MapDB database file.
   * @return The singleton DB instance.
   * @throws IOException if the database file cannot be opened or created.
   */
  public static DB getDbInstance(String filePath) throws IOException {
    Objects.requireNonNull(filePath, "filePath cannot be null");
    if (dbInstance == null) {
      synchronized (lock) {
        if (dbInstance == null) {
          dbInstance =
              DBMaker.fileDB(new File(filePath))
                  .transactionEnable()
                  .executorEnable()
                  .closeOnJvmShutdown()
                  .make();
        }
      }
    }
    return dbInstance;
  }

  /** Closes the database connection. */
  public static void closeDb() {
    if (dbInstance != null) {
      synchronized (lock) {
        if (dbInstance != null && !dbInstance.isClosed()) {
          dbInstance.close();
          dbInstance = null;
        }
      }
    }
  }
}
