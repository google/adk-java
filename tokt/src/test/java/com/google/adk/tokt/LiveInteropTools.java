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

package com.google.adk.tokt;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.ReadonlyContext;
import com.google.adk.events.Event;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.BaseToolset;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real ADK Java tools, a toolset, and plugins - the kind of components an ADK Java user writes --
 * driven on the ADK Kotlin engine through the {@code tokt} forward-interop adapters.
 *
 * <p>Written in Java on purpose: the tools are {@link Schema}-annotated static methods, so {@code
 * FunctionTool.create} exercises the real reflection-based declaration path that a hand-written
 * {@code BaseTool} subclass would bypass.
 */
public final class LiveInteropTools {

  private LiveInteropTools() {}

  @Schema(description = "Returns the current weather for a city.")
  public static ImmutableMap<String, Object> getWeather(
      @Schema(name = "city", description = "City to look up, e.g. 'Warsaw'.") String city) {
    return ImmutableMap.of("city", city, "tempC", 21, "conditions", "sunny");
  }

  @Schema(description = "Adds two integers and returns their sum.")
  public static ImmutableMap<String, Object> add(
      @Schema(name = "a", description = "The first addend.") int a,
      @Schema(name = "b", description = "The second addend.") int b) {
    return ImmutableMap.of("sum", a + b);
  }

  @Schema(description = "Records the user's favorite color in session state.")
  public static ImmutableMap<String, Object> rememberColor(
      @Schema(name = "color", description = "The color to remember.") String color,
      @Schema(name = "toolContext") ToolContext toolContext) {
    toolContext.state().put("favorite_color", color);
    return ImmutableMap.of("status", "stored", "color", color);
  }

  @Schema(
      description =
          "Requests human approval to spend money. Returns a pending ticket; the real decision"
              + " arrives later, out of band (a long-running / human-in-the-loop tool).")
  public static ImmutableMap<String, Object> requestApproval(
      @Schema(name = "amount", description = "Amount to approve, in USD.") int amount) {
    return ImmutableMap.of("status", "PENDING", "ticketId", "TICKET-42", "amount", amount);
  }

  @Schema(description = "Permanently deletes a file. Requires user confirmation before it runs.")
  public static ImmutableMap<String, Object> deleteFile(
      @Schema(name = "path", description = "Absolute path of the file to delete.") String path) {
    return ImmutableMap.of("status", "DELETED", "path", path);
  }

  /**
   * A Java toolset that reads its {@link ReadonlyContext} when provisioning tools, recording the
   * agent name it saw so a test can assert the bridge handed it a non-null context.
   */
  public static final class MathToolset implements BaseToolset {
    private final List<String> provisionedForAgents = new CopyOnWriteArrayList<>();

    public List<String> provisionedForAgents() {
      return provisionedForAgents;
    }

    @Override
    public Flowable<BaseTool> getTools(ReadonlyContext readonlyContext) {
      if (readonlyContext != null) {
        provisionedForAgents.add(readonlyContext.agentName());
      }
      return Flowable.just(FunctionTool.create(LiveInteropTools.class, "add"));
    }

    @Override
    public void close() {}
  }

  /**
   * A Java plugin that mutates session state from its after-tool callback, including a
   * write-then-remove that exercises the Java/Kotlin {@code State.REMOVED} sentinel translation.
   */
  public static final class StateProbePlugin extends BasePlugin {
    public StateProbePlugin() {
      super("state_probe_plugin");
    }

    @Override
    public Maybe<Map<String, Object>> afterToolCallback(
        BaseTool tool,
        Map<String, Object> toolArgs,
        ToolContext toolContext,
        Map<String, Object> result) {
      toolContext.state().put("last_tool", tool.name());
      toolContext.state().put("probe_temp", "temp");
      Object removed = toolContext.state().remove("probe_temp");
      toolContext.state().put("probe_removed_value", String.valueOf(removed));
      return Maybe.empty();
    }
  }

  /**
   * A Java plugin that records the lifecycle callbacks it receives. For an agent adapted with
   * {@code asKtAgent} the Kotlin runner drives exactly these agent/run/event-level ones; the
   * model/tool-level ones do not fire, as that agent calls models and tools internally.
   */
  public static final class LifecyclePlugin extends BasePlugin {
    private final List<String> calls = new CopyOnWriteArrayList<>();

    public LifecyclePlugin() {
      super("lifecycle_plugin");
    }

    /** Callback names in the order they fired, e.g. {@code beforeRun}, {@code onEvent}. */
    public List<String> calls() {
      return calls;
    }

    @Override
    public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
      calls.add("beforeRun");
      return Maybe.empty();
    }

    @Override
    public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
      calls.add("beforeAgent:" + agent.name());
      return Maybe.empty();
    }

    @Override
    public Maybe<Event> onEventCallback(InvocationContext invocationContext, Event event) {
      calls.add("onEvent:" + event.author());
      return Maybe.empty();
    }

    @Override
    public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
      calls.add("afterAgent:" + agent.name());
      return Maybe.empty();
    }

    @Override
    public Completable afterRunCallback(InvocationContext invocationContext) {
      calls.add("afterRun");
      return Completable.complete();
    }
  }
}
