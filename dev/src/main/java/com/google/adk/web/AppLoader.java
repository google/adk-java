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

import com.google.adk.apps.App;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Loads what the ADK Web Server serves: apps, agents and workflows, each as an {@link App} keyed by
 * its app name. An agent is served as an app whose root agent it is, and a workflow as an app whose
 * root node it is.
 *
 * <p>When the server has an {@code AppLoader}, it serves only that loader's apps and ignores any
 * {@link CompiledAgentLoader}, though the default one is still created and scans {@code
 * adk.agents.source-dir} unless {@code adk.agents.loader} names another loader; any other {@link
 * AgentLoader} fails startup.
 */
@ThreadSafe
public interface AppLoader {

  /** Returns the names of the available apps, each a valid app name; empty if there are none. */
  ImmutableList<String> listApps();

  /**
   * Returns the app served as {@code appName}, or empty if there is none.
   *
   * @throws IllegalStateException if the app exists but fails to load
   */
  Optional<App> loadApp(String appName);
}
