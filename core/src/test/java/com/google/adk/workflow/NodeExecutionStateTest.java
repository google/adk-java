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

import static com.google.adk.testing.WorkflowTestNodes.workflow;
import static com.google.adk.workflow.WorkflowResumeTesting.START;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.testing.TestNode;
import io.reactivex.rxjava3.core.Completable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NodeExecutionStateTest {

  private static final NodeRunner.EventSink SINK = (event, nodeFailure) -> Completable.complete();

  @Test
  public void build_withoutANode_throwsIllegalStateException() {
    NodeExecutionState.Builder builder = NodeExecutionState.builder().eventSink(SINK).runId("1");

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_withoutAnEventSink_throwsIllegalStateException() {
    NodeExecutionState.Builder builder =
        NodeExecutionState.builder().node(TestNode.of("worker")).runId("1");

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_withoutARunId_throwsIllegalStateException() {
    NodeExecutionState.Builder builder =
        NodeExecutionState.builder().node(TestNode.of("worker")).eventSink(SINK);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void nextTopLevelRunId_inARetriedWorkflowRoot_restartsTheCount() {
    Workflow wf = workflow("wf", new Edge(START, TestNode.of("a")));
    NodeExecutionState first =
        NodeExecutionState.builder().node(wf).eventSink(SINK).runId("1").build();
    String unused =
        NodeExecutionState.nextTopLevelRunId(first.topLevelRunCounts(), null, "coordinator");
    NodeExecutionState retry =
        NodeExecutionState.builder()
            .node(wf)
            .eventSink(SINK)
            .runId("1")
            .previousAttempt(first)
            .build();

    // As its node numbering restarts, a retry's top-level hops get the same IDs again.
    assertThat(NodeExecutionState.nextTopLevelRunId(retry.topLevelRunCounts(), null, "coordinator"))
        .isEqualTo("1");
  }

  @Test
  public void nextTopLevelRunId_fromATopLevelPeer_continuesTheRootsCount() {
    NodeExecutionState root =
        NodeExecutionState.builder().node(TestNode.of("wf")).eventSink(SINK).runId("1").build();
    NodeExecutionState peer =
        NodeExecutionState.builder()
            .node(TestNode.of("parent"))
            .eventSink(SINK)
            .runId(NodeExecutionState.nextTopLevelRunId(root.topLevelRunCounts(), null, "parent"))
            .topLevelPeer(root)
            .build();

    assertThat(NodeExecutionState.nextTopLevelRunId(peer.topLevelRunCounts(), null, "wf"))
        .isEqualTo("2");
    assertThat(NodeExecutionState.nextTopLevelRunId(root.topLevelRunCounts(), null, "parent"))
        .isEqualTo("2");
  }

  @Test
  public void outputFor_useAsOutputWithoutAParent_containsOnlyItsOwnPath() {
    NodeExecutionState state =
        NodeExecutionState.builder()
            .node(TestNode.of("worker"))
            .eventSink(SINK)
            .runId("1")
            .useAsOutput(true)
            .build();

    assertThat(state.outputFor()).containsExactly("worker@1");
  }
}
