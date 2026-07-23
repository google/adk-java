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

package com.example;

import com.google.adk.utils.ComponentRegistry;

/**
 * Custom ComponentDieRegistry for the user-defined config agent demo.
 *
 * <p>This registry is used to add custom tools and agents to the ADK Web Server.
 */
public class CustomDieRegistry extends ComponentRegistry {

  /** Singleton instance for easy access */
  public static final CustomDieRegistry INSTANCE = new CustomDieRegistry();

  /** Private constructor to initialize custom components */
  public CustomDieRegistry() {
    super();
    register("tools.roll_die", CustomDieTool.ROLL_DIE_INSTANCE);
    register("tools.check_prime", CustomDieTool.CHECK_PRIME_INSTANCE);
  }
}
