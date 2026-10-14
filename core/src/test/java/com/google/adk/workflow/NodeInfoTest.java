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
}
