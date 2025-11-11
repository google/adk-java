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
package com.google.adk.models.springai.properties;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Spring AI integration with ADK.
 *
 * <p>These properties provide validation and default values for Spring AI model configurations used
 * with the ADK SpringAI wrapper.
 *
 * <p>Example configuration:
 *
 * <pre>
 * adk.spring-ai.temperature=0.7
 * adk.spring-ai.max-tokens=2048
 * adk.spring-ai.top-p=0.9
 * adk.spring-ai.validation.enabled=true
 * </pre>
 */
@ConfigurationProperties(prefix = "adk.spring-ai")
@Validated
public class SpringAIProperties {

  @Nullable private String model;

  /** Default temperature for controlling randomness in responses. Must be between 0.0 and 2.0. */
  @DecimalMin(value = "0.0", message = "Temperature must be at least 0.0")
  @DecimalMax(value = "2.0", message = "Temperature must be at most 2.0")
  private Double temperature = 0.7;

  /** Default maximum number of tokens to generate. Must be a positive integer. */
  @Min(value = 1, message = "Max tokens must be at least 1")
  private Integer maxTokens = 2048;

  /** Default nucleus sampling parameter. Must be between 0.0 and 1.0. */
  @DecimalMin(value = "0.0", message = "Top-p must be at least 0.0")
  @DecimalMax(value = "1.0", message = "Top-p must be at most 1.0")
  private Double topP = 0.9;

  /** Default top-k sampling parameter. Must be a positive integer. */
  @Min(value = 1, message = "Top-k must be at least 1")
  private Integer topK;

  /** Configuration validation settings. */
  private Validation validation = new Validation();

  /** Observability settings. */
  private Observability observability = new Observability();

  public String getModel() {
    return model;
  }

  public void setModel(String model) {
    this.model = model;
  }

  public Double getTemperature() {
    return temperature;
  }

  public void setTemperature(Double temperature) {
    this.temperature = temperature;
  }

  public Integer getMaxTokens() {
    return maxTokens;
  }

  public void setMaxTokens(Integer maxTokens) {
    this.maxTokens = maxTokens;
  }

  public Double getTopP() {
    return topP;
  }

  public void setTopP(Double topP) {
    this.topP = topP;
  }

  public Integer getTopK() {
    return topK;
  }

  public void setTopK(Integer topK) {
    this.topK = topK;
  }

  public Validation getValidation() {
    return validation;
  }

  public void setValidation(Validation validation) {
    this.validation = validation;
  }

  public Observability getObservability() {
    return observability;
  }

  public void setObservability(Observability observability) {
    this.observability = observability;
  }

  /** Configuration validation settings. */
  public static class Validation {
    /** Whether to enable strict validation of configuration parameters. */
    private boolean enabled = true;

    /** Whether to fail fast on invalid configuration. */
    private boolean failFast = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isFailFast() {
      return failFast;
    }

    public void setFailFast(boolean failFast) {
      this.failFast = failFast;
    }
  }

  /** Observability configuration settings. */
  public static class Observability {
    /** Whether to enable observability features. */
    private boolean enabled = true;

    /** Whether to include request/response content in traces. */
    private boolean includeContent = false;

    /** Whether to collect metrics. */
    private boolean metricsEnabled = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public boolean isIncludeContent() {
      return includeContent;
    }

    public void setIncludeContent(boolean includeContent) {
      this.includeContent = includeContent;
    }

    public boolean isMetricsEnabled() {
      return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
      this.metricsEnabled = metricsEnabled;
    }
  }
}
