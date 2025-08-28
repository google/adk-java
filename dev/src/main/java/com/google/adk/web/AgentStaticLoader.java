/*
 * Copyright 2025 Google LLC
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

package com.google.adk.web;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Arrays.stream;
import static java.util.function.Function.identity;

import com.google.adk.agents.BaseAgent;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Static Agent Loader for programmatically provided agents.
 *
 * <p>This loader takes a static list of pre-created agent instances and makes them available
 * through the AgentLoader interface. Perfect for cases where you already have agent instances and
 * just need a convenient way to wrap them in an AgentLoader.
 *
 * <p>Example usage:
 *
 * <pre>
 * List&lt;BaseAgent&gt; agents = Arrays.asList(new MyAgent(), new CodeAssistant());
 * AgentLoader loader = new AgentStaticLoader(agents.toArray(new BaseAgent[0]));
 * app.beanFactory().setAgentLoader(loader);
 * </pre>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>To enable this loader: {@code adk.agents.loader=static}
 * </ul>
 */
@Service("staticAgentLoader")
@ConditionalOnProperty(name = "adk.agents.loader", havingValue = "static", matchIfMissing = false)
public class AgentStaticLoader implements AgentLoader {

  private final ImmutableMap<String, BaseAgent> agents;

  public AgentStaticLoader(BaseAgent... agents) {
    this.agents = stream(agents).collect(toImmutableMap(BaseAgent::name, identity()));
  }

  @Override
  @Nonnull
  public ImmutableList<String> listAgents() {
    return agents.keySet().stream().collect(toImmutableList());
  }

  @Override
  public BaseAgent loadAgent(String name) {
    if (name == null || name.trim().isEmpty()) {
      throw new IllegalArgumentException("Agent name cannot be null or empty");
    }

    BaseAgent agent = agents.get(name);
    if (agent == null) {
      throw new NoSuchElementException("Agent not found: " + name);
    }

    return agent;
  }
}
