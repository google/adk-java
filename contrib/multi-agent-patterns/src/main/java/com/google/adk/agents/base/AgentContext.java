package com.google.adk.agents.base;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.copyOf;
import static java.util.Arrays.asList;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

import com.google.adk.agents.BaseAgent;
import com.google.adk.plugins.PluginManager;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AgentContext {

  private final AgentConfig config;
  private final GenerateContentConfig contentConfig;
  private final Optional<PluginManager> plugins;
  private final ImmutableList<? extends BaseTool> tools;
  private final ImmutableList<? extends BaseAgent> subAgents;

  public static AgentContext build(AgentConfig config) {
    return builder().withConfig(config).build();
  }

  public static AgentContext build(AgentConfig config, BaseTool base) {
    return builder().withConfig(config).withTools(base).build();
  }

  public static final Builder builder() {
    return new Builder();
  }

  private AgentContext(Builder builder) {
    this.config = builder.config;
    this.contentConfig = builder.contentConfig;
    this.plugins = builder.plugins;
    this.tools = copyOf(builder.tools);
    this.subAgents = copyOf(builder.subAgents);
  }

  public static class Builder {

    private AgentConfig config;
    private GenerateContentConfig contentConfig;
    private Optional<PluginManager> plugins = empty();
    private List<? extends BaseTool> tools = new ArrayList<>();
    private List<? extends BaseAgent> subAgents = new ArrayList<>();

    public final Builder withConfig(AgentConfig config) {
      this.config = config;
      return this;
    }

    public final Builder withContentConfig(GenerateContentConfig contentConfig) {
      this.contentConfig = contentConfig;
      return this;
    }

    public final Builder withPlugins(PluginManager plugins) {
      return withPlugins(ofNullable(plugins));
    }

    public final Builder withPlugins(Optional<PluginManager> plugins) {
      this.plugins = plugins;
      return this;
    }

    public final Builder withTools(BaseTool... tools) {
      return withTools(asList(tools));
    }

    public final Builder withTools(List<? extends BaseTool> tools) {
      this.tools = tools;
      return this;
    }

    public final Builder withSubAgents(List<? extends BaseAgent> subAgents) {
      this.subAgents = subAgents;
      return this;
    }

    public AgentContext build() {
      return validate(new AgentContext(this));
    }

    private static AgentContext validate(AgentContext instance) {
      checkNotNull(instance.config);
      checkNotNull(instance.contentConfig);
      return instance;
    }
  }

  public AgentConfig getConfig() {
    return config;
  }

  public GenerateContentConfig getContentConfig() {
    return contentConfig;
  }

  public Optional<PluginManager> getPlugins() {
    return plugins;
  }

  public ImmutableList<? extends BaseTool> getTools() {
    return tools;
  }

  public ImmutableList<? extends BaseAgent> getSubAgents() {
    return subAgents;
  }
}
