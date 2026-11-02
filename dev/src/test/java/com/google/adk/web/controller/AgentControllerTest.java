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

package com.google.adk.web.controller;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.agents.LlmAgent;
import com.google.adk.web.AgentLoader;
import com.google.adk.web.AgentStaticLoader;
import org.junit.jupiter.api.Test;

/** Tests the deprecated {@link AgentLoader} constructor, which Spring does not use. */
@SuppressWarnings("deprecation")
class AgentControllerTest {

  @Test
  void listApps_agentLoaderConstructor_returnsTheLoadersAgentsSorted() {
    AgentController controller =
        new AgentController(new AgentStaticLoader(agent("zeta"), agent("alpha")));

    assertThat(controller.listApps()).containsExactly("alpha", "zeta").inOrder();
  }

  @Test
  void agentLoaderConstructor_nullLoader_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> new AgentController((AgentLoader) null));
  }

  private static LlmAgent agent(String name) {
    return LlmAgent.builder().name(name).model("gemini-2.5-flash").build();
  }
}
