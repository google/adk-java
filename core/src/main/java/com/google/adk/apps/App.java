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

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.plugins.Plugin;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

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
  private final ImmutableList<? extends Plugin> plugins;
  @Nullable private final EventsCompactionConfig eventsCompactionConfig;
  @Nullable private final ResumabilityConfig resumabilityConfig;
  @Nullable private final ContextCacheConfig contextCacheConfig;

  private App(
      String name,
      BaseAgent rootAgent,
      List<? extends Plugin> plugins,
      @Nullable EventsCompactionConfig eventsCompactionConfig,
      @Nullable ResumabilityConfig resumabilityConfig,
      @Nullable ContextCacheConfig contextCacheConfig) {
    this.name = name;
    this.rootAgent = rootAgent;
    this.plugins = ImmutableList.copyOf(plugins);
    this.eventsCompactionConfig = eventsCompactionConfig;
    this.resumabilityConfig = resumabilityConfig;
    this.contextCacheConfig = contextCacheConfig;
  }

  public String name() {
    return name;
  }

  public BaseAgent rootAgent() {
    return rootAgent;
  }

  public ImmutableList<? extends Plugin> plugins() {
    return plugins;
  }

  @Nullable
  public EventsCompactionConfig eventsCompactionConfig() {
    return eventsCompactionConfig;
  }

  @Nullable
  public ResumabilityConfig resumabilityConfig() {
    return resumabilityConfig;
  }

  @Nullable
  public ContextCacheConfig contextCacheConfig() {
    return contextCacheConfig;
  }

  /** Builder for {@link App}. */
  public static class Builder {
    private String name;
    private BaseAgent rootAgent;
    private List<? extends Plugin> plugins = ImmutableList.of();
    @Nullable private EventsCompactionConfig eventsCompactionConfig;
    @Nullable private ResumabilityConfig resumabilityConfig;
    @Nullable private ContextCacheConfig contextCacheConfig;

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

    @CanIgnoreReturnValue
    public Builder plugins(List<? extends Plugin> plugins) {
      this.plugins = plugins;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder eventsCompactionConfig(EventsCompactionConfig eventsCompactionConfig) {
      this.eventsCompactionConfig = eventsCompactionConfig;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder resumabilityConfig(ResumabilityConfig resumabilityConfig) {
      this.resumabilityConfig = resumabilityConfig;
      return this;
    }

    @CanIgnoreReturnValue
    public Builder contextCacheConfig(ContextCacheConfig contextCacheConfig) {
      this.contextCacheConfig = contextCacheConfig;
      return this;
    }

    public App build() {
      if (name == null) {
        throw new IllegalStateException("App name must be provided.");
      }
      if (rootAgent == null) {
        throw new IllegalStateException("Root agent must be provided.");
      }
      validateAppName(name);
      return new App(
          name, rootAgent, plugins, eventsCompactionConfig, resumabilityConfig, contextCacheConfig);
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
    if (name.equals("user")) {
      throw new IllegalArgumentException("App name cannot be 'user'; reserved for end-user input.");
    }
  }
}
