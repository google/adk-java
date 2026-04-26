package com.google.adk.agents.base;

import static com.google.api.client.util.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.copyOf;
import static java.util.Arrays.asList;

import com.google.adk.agents.BaseAgent;
import com.google.adk.tools.BaseTool;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

public final class AgentContext {

  private final AgentConfig config;
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
    this.tools = copyOf(builder.tools);
    this.subAgents = copyOf(builder.subAgents);
  }

  public static class Builder {

    private AgentConfig config;
    private List<? extends BaseTool> tools = new ArrayList<>();
    private List<? extends BaseAgent> subAgents = new ArrayList<>();

    public final Builder withConfig(AgentConfig config) {
      this.config = config;
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
      checkNotNull(instance.getConfig());
      return instance;
    }
  }

  public AgentConfig getConfig() {
    return config;
  }

  public ImmutableList<? extends BaseTool> getTools() {
    return tools;
  }

  public ImmutableList<? extends BaseAgent> getSubAgents() {
    return subAgents;
  }
}
