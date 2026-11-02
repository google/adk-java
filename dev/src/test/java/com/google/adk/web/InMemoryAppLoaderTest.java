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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.apps.App;
import com.google.adk.web.testing.StepNode;
import org.junit.jupiter.api.Test;

class InMemoryAppLoaderTest {

  private static final App FLOW_APP =
      App.builder().name("flow").rootNode(new StepNode("a")).build();
  private static final App OTHER_APP =
      App.builder().name("another").rootNode(new StepNode("b")).build();

  @Test
  void listApps_returnsTheAppNamesSorted() {
    InMemoryAppLoader loader = new InMemoryAppLoader(FLOW_APP, OTHER_APP);

    assertThat(loader.listApps()).containsExactly("another", "flow").inOrder();
  }

  @Test
  void loadApp_servedName_returnsTheApp() {
    InMemoryAppLoader loader = new InMemoryAppLoader(FLOW_APP, OTHER_APP);

    assertThat(loader.loadApp("flow")).hasValue(FLOW_APP);
  }

  @Test
  void loadApp_unknownName_returnsEmpty() {
    InMemoryAppLoader loader = new InMemoryAppLoader(FLOW_APP);

    assertThat(loader.loadApp("missing")).isEmpty();
  }

  @Test
  void constructor_twoAppsWithOneName_throwsIllegalArgumentException() {
    App sameName = App.builder().name("flow").rootNode(new StepNode("c")).build();

    assertThrows(IllegalArgumentException.class, () -> new InMemoryAppLoader(FLOW_APP, sameName));
  }
}
