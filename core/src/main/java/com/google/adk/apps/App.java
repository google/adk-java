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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.NodeViewAgent;
import com.google.adk.agents.Role;
import com.google.adk.annotations.Experimental;
import com.google.adk.plugins.Plugin;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.workflow.Node;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Represents an LLM-backed agentic application.
 *
 * <p>An {@code App} is the top-level container for an agentic system powered by LLMs. It manages a
 * root agent ({@code rootAgent}), which serves as the root of an agent tree, enabling coordination
 * and communication across all agents in the hierarchy. The {@code plugins} are application-wide
 * components that provide shared capabilities and services to the entire system.
 */
public class App {
  private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

  private final String name;
  private final BaseAgent rootAgent;
  private final @Nullable Node rootNode;
  private final ImmutableList<? extends Plugin> plugins;
  private final @Nullable EventsCompactionConfig eventsCompactionConfig;
  private final @Nullable ContextCacheConfig contextCacheConfig;
  private final @Nullable ResumabilityConfig resumabilityConfig;

  private App(
      String name,
      BaseAgent rootAgent,
      @Nullable Node rootNode,
      List<? extends Plugin> plugins,
      @Nullable EventsCompactionConfig eventsCompactionConfig,
      @Nullable ContextCacheConfig contextCacheConfig,
      @Nullable ResumabilityConfig resumabilityConfig) {
    this.name = name;
    this.rootAgent = rootAgent;
    this.rootNode = rootNode;
    this.plugins = ImmutableList.copyOf(plugins);
    this.eventsCompactionConfig = eventsCompactionConfig;
    this.contextCacheConfig = contextCacheConfig;
    this.resumabilityConfig = resumabilityConfig;
  }

  public String name() {
    return name;
  }

  public BaseAgent rootAgent() {
    return rootAgent;
  }

  /**
   * Returns the workflow node the app runs, or empty when it runs an agent tree. When it runs a
   * node, {@link #rootAgent()} returns a {@link NodeViewAgent} that stands in for it.
   */
  @Experimental
  public Optional<Node> rootNode() {
    return Optional.ofNullable(rootNode);
  }

  public List<? extends Plugin> plugins() {
    return plugins;
  }

  @Nullable
  public EventsCompactionConfig eventsCompactionConfig() {
    return eventsCompactionConfig;
  }

  @Nullable
  public ContextCacheConfig contextCacheConfig() {
    return contextCacheConfig;
  }

  @Experimental
  public @Nullable ResumabilityConfig resumabilityConfig() {
    return resumabilityConfig;
  }

  /** Builder for {@link App}. */
  public static class Builder {
    private String name;
    private BaseAgent rootAgent;
    private @Nullable Node rootNode;
    private List<? extends Plugin> plugins = ImmutableList.of();
    @Nullable private EventsCompactionConfig eventsCompactionConfig;
    @Nullable private ContextCacheConfig contextCacheConfig;
    private @Nullable ResumabilityConfig resumabilityConfig;

    @CanIgnoreReturnValue
    public Builder name(String name) {
      this.name = name;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder rootAgent(BaseAgent rootAgent) {
      this.rootAgent = rootAgent;
      return this;
    }

    /**
     * Sets the workflow node the app runs in place of a root agent. If the given node is an agent,
     * it becomes the root agent.
     */
    @CanIgnoreReturnValue
    @Experimental
    public Builder rootNode(Node rootNode) {
      this.rootNode = checkNotNull(rootNode);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder plugins(List<? extends Plugin> plugins) {
      this.plugins = plugins;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder plugins(Plugin... plugins) {
      this.plugins = ImmutableList.copyOf(plugins);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder eventsCompactionConfig(EventsCompactionConfig eventsCompactionConfig) {
      this.eventsCompactionConfig = eventsCompactionConfig;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder contextCacheConfig(ContextCacheConfig contextCacheConfig) {
      this.contextCacheConfig = contextCacheConfig;
      return this;
    }

    @CanIgnoreReturnValue
    @Experimental
    public Builder resumabilityConfig(ResumabilityConfig resumabilityConfig) {
      this.resumabilityConfig = resumabilityConfig;
      return this;
    }

    public App build() {
      if (name == null) {
        throw new IllegalStateException("App name must be provided.");
      }
      BaseAgent buildRootAgent = resolveRootAgent();
      validateAppName(name);
      return new App(
          name,
          buildRootAgent,
          // An agent set as the root node is the root agent, so the app has no root node.
          rootNode instanceof BaseAgent ? null : rootNode,
          plugins,
          eventsCompactionConfig,
          contextCacheConfig,
          resumabilityConfig);
    }

    /** Returns the root agent, or the agent that stands in for the root node. */
    private BaseAgent resolveRootAgent() {
      if (rootAgent instanceof NodeViewAgent || rootNode instanceof NodeViewAgent) {
        throw new IllegalStateException(
            "A node view agent cannot be the root; set the node it stands in for as the root"
                + " node.");
      }
      if (rootNode == null) {
        if (rootAgent == null) {
          throw new IllegalStateException("Root agent or root node must be provided.");
        }
        return rootAgent;
      }
      if (rootAgent != null) {
        throw new IllegalStateException("Set a root agent or a root node, not both.");
      }
      return rootNode instanceof BaseAgent agent ? agent : new NodeViewAgent(rootNode);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  private static void validateAppName(String name) {
    if (!IDENTIFIER_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "Invalid app name '"
              + name
              + "': must be a valid identifier consisting of letters, digits, and underscores.");
    }
    if (name.equals(Role.USER)) {
      throw new IllegalArgumentException("App name cannot be 'user'; reserved for end-user input.");
    }
  }
}
