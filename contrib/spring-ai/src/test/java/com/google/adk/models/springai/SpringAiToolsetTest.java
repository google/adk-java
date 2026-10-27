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
package com.google.adk.models.springai;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.tools.BaseTool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

class SpringAiToolsetTest {

  private static ToolCallback callback(String name, String result) {
    return FunctionToolCallback.builder(name, (Map<String, Object> args) -> result)
        .description(name + " tool")
        .inputType(Map.class)
        .build();
  }

  @Test
  void getTools_providerChangesBetweenCalls_returnsCurrentTools() {
    AtomicReference<ToolCallback[]> callbacks =
        new AtomicReference<>(new ToolCallback[] {callback("get_weather", "{}")});
    SpringAiToolset toolset = new SpringAiToolset(callbacks::get);

    List<BaseTool> before = toolset.getTools(null).toList().blockingGet();
    callbacks.set(new ToolCallback[] {callback("get_weather", "{}"), callback("get_time", "{}")});
    List<BaseTool> after = toolset.getTools(null).toList().blockingGet();

    assertThat(before).extracting(BaseTool::name).containsExactly("get_weather");
    assertThat(after).extracting(BaseTool::name).containsExactly("get_weather", "get_time");
  }

  @Test
  void getTools_convertedTool_runsSpringAiCallback() {
    SpringAiToolset toolset =
        new SpringAiToolset(
            () -> new ToolCallback[] {callback("get_time", "{\"time\":\"12:00\"}")});

    BaseTool tool = toolset.getTools(null).blockingFirst();
    Map<String, Object> result = tool.runAsync(Map.of(), null).blockingGet();

    assertThat(result).containsEntry("time", "12:00");
  }

  @Test
  void getTools_emptyProvider_returnsNoTools() throws Exception {
    SpringAiToolset toolset = new SpringAiToolset(() -> new ToolCallback[0]);

    List<BaseTool> tools = toolset.getTools(null).toList().blockingGet();
    toolset.close();

    assertThat(tools).isEmpty();
  }
}
