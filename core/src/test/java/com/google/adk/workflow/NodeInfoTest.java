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

package com.google.adk.workflow;

import static com.google.common.truth.Truth.assertThat;

import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableList;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NodeInfoTest {

  private static final NodeInfo NODE_INFO =
      NodeInfo.builder()
          .path("wf@1/a@1")
          .outputFor(ImmutableList.of("wf@1/a@1", "wf@1"))
          .messageAsOutput(true)
          .build();

  @Test
  public void builder_defaultsToEmptyPathAndNoOutput() {
    NodeInfo nodeInfo = NodeInfo.builder().build();

    assertThat(nodeInfo.path()).isEmpty();
    assertThat(nodeInfo.outputFor()).isEmpty();
    assertThat(nodeInfo.messageAsOutput()).isFalse();
  }

  @Test
  public void toBuilder_copiesEveryProperty() {
    assertThat(NODE_INFO.toBuilder().build()).isEqualTo(NODE_INFO);
  }

  @Test
  public void json_roundTrips() throws Exception {
    String json = JsonBaseModel.toJsonString(NODE_INFO);

    assertThat(JsonBaseModel.getMapper().readValue(json, NodeInfo.class)).isEqualTo(NODE_INFO);
  }

  @Test
  public void json_omitsUnsetOutputForAndFalseMessageAsOutput() {
    String json = JsonBaseModel.toJsonString(NodeInfo.builder().path("a@1").build());

    assertThat(json).contains("\"path\":\"a@1\"");
    assertThat(json).doesNotContain("outputFor");
    assertThat(json).doesNotContain("messageAsOutput");
  }

  @Test
  public void json_omitsPathHelpers() {
    Map<?, ?> json = JsonBaseModel.getMapper().convertValue(NODE_INFO, Map.class);

    assertThat(json.keySet()).containsExactly("path", "outputFor", "messageAsOutput");
  }

  @Test
  public void name_isTheLastSegmentWithoutItsRunId() {
    assertThat(nodeInfo("wf@1/a@2/b@3").name()).hasValue("b");
    assertThat(nodeInfo("wf@1/b").name()).hasValue("b");
    assertThat(nodeInfo("wf@1").name()).hasValue("wf");
  }

  @Test
  public void runId_isTheLastSegmentsRunId() {
    assertThat(nodeInfo("wf@1/a@2/b@3").runId()).hasValue("3");
  }

  @Test
  public void runId_emptyWithoutARunId() {
    assertThat(nodeInfo("wf@1/b").runId()).isEmpty();
  }

  @Test
  public void parentRunId_isTheSecondToLastSegmentsRunId() {
    assertThat(nodeInfo("wf@1/a@2/b@3").parentRunId()).hasValue("2");
    assertThat(nodeInfo("wf@1/b").parentRunId()).hasValue("1");
  }

  @Test
  public void parentRunId_emptyWithoutAParentRunId() {
    assertThat(nodeInfo("wf@1").parentRunId()).isEmpty();
    assertThat(nodeInfo("wf/b@1").parentRunId()).isEmpty();
  }

  @Test
  public void pathHelpers_splitEachSegmentAtItsLastAt() {
    NodeInfo nodeInfo = nodeInfo("w@f@1/a@b@2");

    assertThat(nodeInfo.name()).hasValue("a@b");
    assertThat(nodeInfo.runId()).hasValue("2");
    assertThat(nodeInfo.parentRunId()).hasValue("1");
  }

  @Test
  public void pathHelpers_emptyForAnEmptyPath() {
    NodeInfo nodeInfo = NodeInfo.builder().build();

    assertThat(nodeInfo.name()).isEmpty();
    assertThat(nodeInfo.runId()).isEmpty();
    assertThat(nodeInfo.parentRunId()).isEmpty();
  }

  private static NodeInfo nodeInfo(String path) {
    return NodeInfo.builder().path(path).build();
  }
}
