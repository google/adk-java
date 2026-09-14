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

package com.google.adk.tools;

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createRootAgent;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.agents.InvocationContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Schema;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TransferToAgentToolTest {

  @Test
  public void name_isTheNameTheFlowAndTheModelAgreeOn() {
    TransferToAgentTool tool = TransferToAgentTool.create(ImmutableList.of("billing_agent"));

    assertThat(tool.name()).isEqualTo("transfer_to_agent");
  }

  @Test
  public void declaration_offersOnlyTheGivenAgents() {
    TransferToAgentTool tool =
        TransferToAgentTool.create(ImmutableList.of("billing_agent", "support_agent"));

    assertThat(agentNameSchema(tool).enum_())
        .hasValue(ImmutableList.of("billing_agent", "support_agent"));
  }

  @Test
  public void declaration_withNoAgents_offersNothing() {
    TransferToAgentTool tool = TransferToAgentTool.create(ImmutableList.of());

    assertThat(agentNameSchema(tool).enum_()).hasValue(ImmutableList.of());
  }

  @Test
  public void declaration_leavesTheRestOfTheSchemaAlone() {
    TransferToAgentTool tool = TransferToAgentTool.create(ImmutableList.of("billing_agent"));

    assertThat(tool.declaration().get().parameters().get().required())
        .hasValue(ImmutableList.of("agent_name"));
    assertThat(tool.declaration().get().description()).isPresent();
  }

  @Test
  public void runAsync_recordsTheTransferOnTheContext() {
    TransferToAgentTool tool =
        TransferToAgentTool.create(ImmutableList.of("billing_agent", "support_agent"));
    InvocationContext invocationContext = createInvocationContext(createRootAgent());
    ToolContext toolContext = ToolContext.builder(invocationContext).build();

    Map<String, Object> unused =
        tool.runAsync(ImmutableMap.of("agent_name", "billing_agent"), toolContext).blockingGet();

    assertThat(toolContext.actions().transferToAgent()).hasValue("billing_agent");
  }

  private static Schema agentNameSchema(TransferToAgentTool tool) {
    return tool.declaration().get().parameters().get().properties().get().get("agent_name");
  }
}
