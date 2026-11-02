/*
 * Copyright 2026 Google LLC
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

package com.google.adk.web;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.adk.apps.App;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** An {@link AppLoader} serving a fixed set of apps, each under its {@link App#name()}. */
public final class InMemoryAppLoader implements AppLoader {

  private final ImmutableSortedMap<String, App> apps;

  /**
   * Serves {@code apps}.
   *
   * @throws IllegalArgumentException if two apps share a name
   */
  public InMemoryAppLoader(App... apps) {
    Map<String, App> byName = new HashMap<>();
    for (App app : apps) {
      checkArgument(byName.putIfAbsent(app.name(), app) == null, "App names must be unique.");
    }
    this.apps = ImmutableSortedMap.copyOf(byName);
  }

  @Override
  public ImmutableList<String> listApps() {
    return apps.keySet().asList();
  }

  @Override
  public Optional<App> loadApp(String appName) {
    return Optional.ofNullable(apps.get(appName));
  }
}
