package com.google.adk.agents.base;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects.ToStringHelper;
import java.util.Optional;

@JsonDeserialize(builder = AgentConfig.Builder.class)
public final class AgentConfig {

  private final String name;
  private final String model;
  private final String description;
  private final String instruction;
  private final Optional<String> outputKey;
  private final Optional<String> includeContents;
  private final Optional<String> transferToAgent;
  private final Optional<ContentConfig> contentConfig;

  private AgentConfig(Builder builder) {
    this.name = builder.name;
    this.model = builder.model;
    this.description = builder.description;
    this.instruction = builder.instruction;
    this.outputKey = builder.outputKey;
    this.includeContents = builder.includeContents;
    this.transferToAgent = builder.transferToAgent;
    this.contentConfig = builder.contentConfig;
  }

  public static class Builder {
    private String name;
    private String model;
    private String description;
    private String instruction;
    private Optional<String> outputKey = empty();
    private Optional<String> includeContents = empty();
    private Optional<String> transferToAgent = empty();
    private Optional<ContentConfig> contentConfig = empty();

    public final Builder withName(String name) {
      this.name = name;
      return this;
    }

    public final Builder withModel(String model) {
      this.model = model;
      return this;
    }

    public final Builder withDescription(String description) {
      this.description = description;
      return this;
    }

    public final Builder withInstruction(String instruction) {
      this.instruction = instruction;
      return this;
    }

    public final Builder withOutputKey(Optional<String> outputKey) {
      this.outputKey = outputKey;
      return this;
    }

    public final Builder withIncludeContents(Optional<String> includeContents) {
      this.includeContents = includeContents;
      return this;
    }

    public final Builder withTransferToAgent(Optional<String> transferToAgent) {
      this.transferToAgent = transferToAgent;
      return this;
    }

    public final Builder withContextConfig(Optional<ContentConfig> contentConfig) {
      this.contentConfig = contentConfig;
      return this;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public String getInstruction() {
      return instruction;
    }

    public void setInstruction(String instruction) {
      this.instruction = instruction;
    }

    public Optional<String> getOutputKey() {
      return outputKey;
    }

    @JsonProperty
    public void setOutputKey(String outputKey) {
      setOutputKey(ofNullable(outputKey));
    }

    public void setOutputKey(Optional<String> outputKey) {
      this.outputKey = outputKey;
    }

    public Optional<String> getIncludeContents() {
      return includeContents;
    }

    @JsonProperty
    public void setIncludeContents(String includeContents) {
      setIncludeContents(ofNullable(includeContents));
    }

    public void setIncludeContents(Optional<String> includeContents) {
      this.includeContents = includeContents;
    }

    public Optional<String> getTransferToAgent() {
      return transferToAgent;
    }

    @JsonProperty
    public void setTransferToAgent(String transferToAgent) {
      setTransferToAgent(ofNullable(transferToAgent));
    }

    public void setTransferToAgent(Optional<String> transferToAgent) {
      this.transferToAgent = transferToAgent;
    }

    public Optional<ContentConfig> getContentConfig() {
      return contentConfig;
    }

    @JsonProperty
    public void setContentConfig(ContentConfig contentConfig) {
      setContentConfig(ofNullable(contentConfig));
    }

    public void setContentConfig(Optional<ContentConfig> contentConfig) {
      this.contentConfig = contentConfig;
    }

    public AgentConfig build() {
      return validate(new AgentConfig(this));
    }

    private static AgentConfig validate(AgentConfig instance) {
      checkNotNull(instance.name);
      checkNotNull(instance.model);
      checkNotNull(instance.description);
      checkNotNull(instance.instruction);
      return instance;
    }
  }

  public String getName() {
    return name;
  }

  public String getModel() {
    return model;
  }

  public String getDescription() {
    return description;
  }

  public String getInstruction() {
    return instruction;
  }

  public Optional<String> getOutputKey() {
    return outputKey;
  }

  public Optional<String> getIncludeContents() {
    return includeContents;
  }

  public Optional<String> getTransferToAgent() {
    return transferToAgent;
  }

  public Optional<ContentConfig> getContentConfig() {
    return contentConfig;
  }

  @Override
  public String toString() {
    ToStringHelper helper = toStringHelper(this);
    helper.add("name", name);
    helper.add("model", model);
    helper.add("description", description);
    helper.add("instruction", instruction);
    helper.add("outputKey", outputKey);
    helper.add("includeContents", includeContents);
    helper.add("transferToAgent", transferToAgent);
    helper.add("contentConfig", contentConfig);
    return helper.toString();
  }
}
