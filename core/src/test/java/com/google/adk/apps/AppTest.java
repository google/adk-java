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

package com.google.adk.apps;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.NodeViewAgent;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.plugins.Plugin;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.testing.TestNode;
import com.google.adk.testing.TestUtils;
import com.google.adk.workflow.Node;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AppTest {

  private static final Node NODE = TestNode.of("solo");

  @Test
  public void build_withARootNode_standsANodeViewAgentInForIt() {
    App app = App.builder().name("node_app").rootNode(NODE).build();

    assertThat(app.rootNode()).hasValue(NODE);
    assertThat(app.rootAgent()).isInstanceOf(NodeViewAgent.class);
    assertThat(((NodeViewAgent) app.rootAgent()).node()).isSameInstanceAs(NODE);
  }

  @Test
  public void build_withAnAgentAsTheRootNode_makesItTheRootAgent() {
    BaseAgent agent = TestUtils.createSubAgent("worker");

    App app = App.builder().name("agent_app").rootNode(agent).build();

    assertThat(app.rootAgent()).isSameInstanceAs(agent);
    assertThat(app.rootNode()).isEmpty();
  }

  @Test
  public void build_withARootAgent_hasNoRootNode() {
    App app = App.builder().name("agent_app").rootAgent(TestUtils.createSubAgent("worker")).build();

    assertThat(app.rootNode()).isEmpty();
  }

  @Test
  public void build_withARootAgentAndADifferentRootNode_throwsIllegalStateException() {
    App.Builder builder =
        App.builder().name("app").rootAgent(TestUtils.createSubAgent("worker")).rootNode(NODE);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_withTheSameAgentAsRootAgentAndRootNode_throwsIllegalStateException() {
    BaseAgent agent = TestUtils.createSubAgent("worker");
    App.Builder builder = App.builder().name("app").rootAgent(agent).rootNode(agent);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_withNeitherARootAgentNorARootNode_throwsIllegalStateException() {
    App.Builder builder = App.builder().name("app");

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_withANodeViewAgentAsTheRootAgent_throwsIllegalStateException() {
    App.Builder builder = App.builder().name("app").rootAgent(new NodeViewAgent(NODE));

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_withANodeViewAgentAsTheRootNode_throwsIllegalStateException() {
    App.Builder builder = App.builder().name("app").rootNode(new NodeViewAgent(NODE));

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void toBuilder_unchanged_buildsAnAppWithTheSameSettings() {
    BaseAgent agent = TestUtils.createSubAgent("worker");
    Plugin plugin = new BasePlugin("plugin") {};
    EventsCompactionConfig compaction = new EventsCompactionConfig(3, 1);
    ContextCacheConfig cache = new ContextCacheConfig();
    ResumabilityConfig resumability = ResumabilityConfig.builder().resumable(true).build();
    App app =
        App.builder()
            .name("agent_app")
            .rootAgent(agent)
            .plugins(plugin)
            .eventsCompactionConfig(compaction)
            .contextCacheConfig(cache)
            .resumabilityConfig(resumability)
            .build();

    App copy = app.toBuilder().build();

    assertThat(copy.name()).isEqualTo("agent_app");
    assertThat(copy.rootAgent()).isSameInstanceAs(agent);
    assertThat(copy.rootNode()).isEmpty();
    assertThat(copy.plugins()).containsExactly(plugin);
    assertThat(copy.eventsCompactionConfig()).isSameInstanceAs(compaction);
    assertThat(copy.contextCacheConfig()).isSameInstanceAs(cache);
    assertThat(copy.resumabilityConfig()).isSameInstanceAs(resumability);
  }

  @Test
  public void toBuilder_ofANodeApp_keepsTheRootNode() {
    App app = App.builder().name("node_app").rootNode(NODE).build();

    App copy = app.toBuilder().build();

    assertThat(copy.rootNode()).hasValue(NODE);
    assertThat(((NodeViewAgent) copy.rootAgent()).node()).isSameInstanceAs(NODE);
  }

  @Test
  public void toBuilder_changed_buildsAnAppWithTheChanges() {
    Plugin plugin = new BasePlugin("plugin") {};
    App app = App.builder().name("node_app").rootNode(NODE).build();

    App copy = app.toBuilder().name("renamed").plugins(plugin).build();

    assertThat(copy.name()).isEqualTo("renamed");
    assertThat(copy.plugins()).containsExactly(plugin);
    assertThat(copy.rootNode()).hasValue(NODE);
  }

  @Test
  public void toBuilder_ofANodeAppClearingTheNodeAndSettingAnAgent_runsTheAgent() {
    BaseAgent agent = TestUtils.createSubAgent("worker");
    App app = App.builder().name("node_app").rootNode(NODE).build();

    App copy = app.toBuilder().rootNode(null).rootAgent(agent).build();

    assertThat(copy.rootAgent()).isSameInstanceAs(agent);
    assertThat(copy.rootNode()).isEmpty();
  }

  @Test
  public void toBuilder_ofAnAgentAppClearingTheAgentAndSettingANode_runsTheNode() {
    App app = App.builder().name("agent_app").rootAgent(TestUtils.createSubAgent("worker")).build();

    App copy = app.toBuilder().rootAgent(null).rootNode(NODE).build();

    assertThat(copy.rootNode()).hasValue(NODE);
    assertThat(((NodeViewAgent) copy.rootAgent()).node()).isSameInstanceAs(NODE);
  }

  @Test
  public void toBuilder_ofANodeAppSettingAnotherNode_standsInForTheNewNode() {
    Node other = TestNode.of("other");
    App app = App.builder().name("node_app").rootNode(NODE).build();

    App copy = app.toBuilder().rootNode(other).build();

    assertThat(copy.rootNode()).hasValue(other);
    assertThat(((NodeViewAgent) copy.rootAgent()).node()).isSameInstanceAs(other);
  }

  @Test
  public void toBuilder_ofANodeAppClearingTheNode_throwsIllegalStateException() {
    App app = App.builder().name("node_app").rootNode(NODE).build();
    App.Builder builder = app.toBuilder().rootNode(null);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void toBuilder_ofANodeAppSettingAnAgent_throwsIllegalStateException() {
    App app = App.builder().name("node_app").rootNode(NODE).build();
    App.Builder builder = app.toBuilder().rootAgent(TestUtils.createSubAgent("worker"));

    assertThrows(IllegalStateException.class, builder::build);
  }
}
