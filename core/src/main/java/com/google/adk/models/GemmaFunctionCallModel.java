package com.google.adk.models;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** Flexible Pydantic model for parsing inline Gemma function call responses. */
public class GemmaFunctionCallModel {
  private final String name;
  private final Map<String, Object> parameters;

  @JsonCreator
  public GemmaFunctionCallModel(
      @JsonProperty("name") @JsonAlias("function") String name,
      @JsonProperty("parameters") @JsonAlias("args") Map<String, Object> parameters) {
    this.name = name;
    this.parameters = parameters;
  }

  public String getName() {
    return name;
  }

  public Map<String, Object> getParameters() {
    return parameters;
  }
}
