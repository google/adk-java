/*
 * Copyright 2024 Google LLC
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
package com.google.adk.memory;

import com.google.adk.sessions.Session;
import java.util.List;
import java.util.Map;

/** Placeholder for MemoryService. */
public abstract class MemoryService {
  public abstract void put(String key, String value);

  public abstract String get(String key);

  public abstract void remove(String key);

  public abstract void add(String key, String value);

  public abstract void newSession(Session session);

  public abstract Map<String, String> getAll();

  public abstract List<String> getList(String key);
}
