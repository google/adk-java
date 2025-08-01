package com.google.adk.tools;

import com.google.adk.agents.ReadonlyContext;
import java.util.List;
import java.util.Optional;

public class NamedToolPredicate implements ToolPredicate {

  private final List<String> toolNames;

  public NamedToolPredicate(List<String> toolNames) {
    this.toolNames = List.copyOf(toolNames);
  }

  public NamedToolPredicate(String... toolNames) {
    this.toolNames = List.of(toolNames);
  }

  @Override
  public boolean test(BaseTool tool, Optional<ReadonlyContext> readonlyContext) {
    return toolNames.contains(tool.name());
  }
}
