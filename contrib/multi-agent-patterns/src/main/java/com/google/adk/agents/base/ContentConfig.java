package com.google.adk.agents.base;

import static com.google.api.client.util.Preconditions.checkArgument;
import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Optional.absent;
import static com.google.common.base.Optional.fromNullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Optional;

@JsonDeserialize(builder = ContentConfig.Builder.class)
public final class ContentConfig {

  private final Optional<Float> temperature;
  private final Optional<Integer> maxOutputTokens;

  private ContentConfig(Builder builder) {
    this.temperature = builder.temperature;
    this.maxOutputTokens = builder.maxOutputTokens;
  }

  public static class Builder {
    private Optional<Float> temperature = absent();
    private Optional<Integer> maxOutputTokens = absent();

    public final Builder withTemperature(Optional<Float> temperature) {
      this.temperature = temperature;
      return this;
    }

    public final Builder withMaxOutputTokens(Optional<Integer> maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
      return this;
    }

    public Optional<Float> getTemperature() {
      return temperature;
    }

    public Optional<Integer> getMaxOutputTokens() {
      return maxOutputTokens;
    }

    @JsonProperty
    public void setOutputKey(float temperature) {
      setTemperature(fromNullable(temperature));
    }

    public void setTemperature(Optional<Float> temperature) {
      this.temperature = temperature;
    }

    @JsonProperty
    public void setIncludeContents(int maxOutputTokens) {
      setMaxOutputTokens(fromNullable(maxOutputTokens));
    }

    public void setMaxOutputTokens(Optional<Integer> maxOutputTokens) {
      this.maxOutputTokens = maxOutputTokens;
    }

    public ContentConfig build() {
      return validate(new ContentConfig(this));
    }

    private static ContentConfig validate(ContentConfig instance) {
      if (instance.temperature.isPresent()) {
        checkArgument(instance.temperature.get() > 0.0, "positive temperature expected");
      }
      if (instance.maxOutputTokens.isPresent()) {
        checkArgument(
            instance.maxOutputTokens.get() > 0, "maxOutput tokens can't be zero or negative");
      }
      return instance;
    }
  }

  public Optional<Float> getTemperature() {
    return temperature;
  }

  public Optional<Integer> getMaxOutputTokens() {
    return maxOutputTokens;
  }

  @Override
  public String toString() {
    ToStringHelper helper = toStringHelper(this);
    helper.add("temperature", temperature);
    helper.add("maxOutputTokens", maxOutputTokens);
    return helper.toString();
  }
}
