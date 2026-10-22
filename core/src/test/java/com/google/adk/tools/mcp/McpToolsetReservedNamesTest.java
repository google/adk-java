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

package com.google.adk.tools.mcp;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.JsonBaseModel;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.NamedToolPredicate;
import com.google.adk.tools.SetModelResponseTool;
import com.google.adk.tools.ToolPredicate;
import com.google.genai.types.Schema;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import reactor.core.publisher.Mono;

/** Runs the same reserved-name contract against both MCP toolset implementations. */
@RunWith(Parameterized.class)
public class McpToolsetReservedNamesTest {
  private static final List<String> RESERVED_NAMES =
      List.of(
          "set_model_response",
          "transfer_to_agent",
          "adk_request_confirmation",
          "adk_request_credential",
          "adk_request_input");

  @Parameters(name = "async={0}")
  public static List<Boolean> parameters() {
    return List.of(false, true);
  }

  private final boolean async;
  private final ReadonlyContext context = mock(ReadonlyContext.class);
  private final McpSyncClient syncClient = mock(McpSyncClient.class);
  private final McpAsyncClient asyncClient = mock(McpAsyncClient.class);

  public McpToolsetReservedNamesTest(boolean async) {
    this.async = async;
  }

  @Test
  public void getTools_skipsReservedNamesAndPreservesOtherToolsInOrder() {
    List<String> names = new ArrayList<>(RESERVED_NAMES);
    names.add(0, "get_weather");
    names.add("google_search");
    names.add("set_model_response_custom");

    assertThat(toolNames(createToolset(names, null)))
        .containsExactly("get_weather", "google_search", "set_model_response_custom")
        .inOrder();
  }

  @Test
  public void getTools_allReservedNames_returnsEmptyAndClosesSession() throws Exception {
    BaseToolset toolset = createToolset(RESERVED_NAMES, null);

    assertThat(toolNames(toolset)).isEmpty();
    toolset.close();

    if (async) {
      verify(asyncClient, atLeastOnce()).closeGracefully();
    } else {
      verify(syncClient).close();
    }
  }

  @Test
  public void getTools_emptyServerResponse_returnsEmpty() {
    assertThat(toolNames(createToolset(List.of(), null))).isEmpty();
  }

  @Test
  public void getTools_nameFilterCannotReenableReservedTools() {
    List<String> names = new ArrayList<>(RESERVED_NAMES);
    names.add("get_weather");
    names.add("get_time");
    List<String> selectedNames = new ArrayList<>(RESERVED_NAMES);
    selectedNames.add("get_weather");

    assertThat(toolNames(createToolset(names, new NamedToolPredicate(selectedNames))))
        .containsExactly("get_weather");
  }

  @Test
  public void getTools_predicateOnlySeesNonReservedToolsAndReceivesContext() {
    List<String> names = new ArrayList<>(RESERVED_NAMES);
    names.add("get_weather");
    names.add("get_time");
    List<String> inspectedNames = new ArrayList<>();
    ToolPredicate predicate =
        (tool, readonlyContext) -> {
          assertThat(readonlyContext.orElseThrow()).isSameInstanceAs(context);
          inspectedNames.add(tool.name());
          return !tool.name().equals("get_time");
        };

    assertThat(toolNames(createToolset(names, predicate))).containsExactly("get_weather");
    assertThat(inspectedNames).containsExactly("get_weather", "get_time").inOrder();
  }

  @Test
  public void getTools_canBeCombinedWithFrameworkOutputSchemaTool() {
    BaseToolset toolset = createToolset(List.of("set_model_response", "get_weather"), null);
    SetModelResponseTool frameworkTool =
        new SetModelResponseTool(Schema.builder().type("OBJECT").build());

    LlmRequest request =
        LlmRequest.builder()
            .appendTools(toolset.getTools(context).toList().blockingGet())
            .appendTools(List.of(frameworkTool))
            .build();

    assertThat(request.tools()).containsKey("get_weather");
    assertThat(request.tools()).containsEntry("set_model_response", frameworkTool);
    assertThat(request.tools()).hasSize(2);
  }

  private List<String> toolNames(BaseToolset toolset) {
    return toolset.getTools(context).map(BaseTool::name).toList().blockingGet();
  }

  private BaseToolset createToolset(List<String> names, @Nullable ToolPredicate toolFilter) {
    List<Tool> tools =
        names.stream()
            .map(name -> Tool.builder(name, McpJsonDefaults.getMapper(), "{}").build())
            .toList();
    ListToolsResult response = ListToolsResult.builder(tools).build();
    McpSessionManager manager = mock(McpSessionManager.class);
    if (async) {
      when(manager.createAsyncSession()).thenReturn(asyncClient);
      when(asyncClient.initialize()).thenReturn(Mono.empty());
      when(asyncClient.listTools()).thenReturn(Mono.just(response));
      when(asyncClient.closeGracefully()).thenReturn(Mono.empty());
      return new McpAsyncToolset(manager, JsonBaseModel.getMapper(), toolFilter);
    }
    when(manager.createSession()).thenReturn(syncClient);
    when(syncClient.listTools()).thenReturn(response);
    return toolFilter == null
        ? new McpToolset(manager, JsonBaseModel.getMapper())
        : new McpToolset(manager, JsonBaseModel.getMapper(), toolFilter);
  }
}
