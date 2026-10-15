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

import static org.junit.Assert.assertThrows;

import com.google.adk.testing.TestNode;
import io.reactivex.rxjava3.core.Completable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class NodeExecutionStateTest {

  private static final NodeRunner.EventSink SINK = event -> Completable.complete();

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
}
