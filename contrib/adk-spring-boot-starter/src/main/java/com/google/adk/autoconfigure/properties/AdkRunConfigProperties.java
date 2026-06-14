/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.adk.autoconfigure.properties;

import com.google.adk.agents.RunConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for ADK {@link RunConfig} auto-configuration. Prefix {@code
 * adk.run-config}.
 *
 * <p>Exposes the operational subset of {@link RunConfig} called out in PR review feedback. The
 * remaining {@link RunConfig} fields ({@code speechConfig}, {@code responseModalities}, audio
 * transcription configs, {@code avatarConfig}) are not exposed here — they require richer binding
 * logic and are application-specific; users who need them can declare their own {@link RunConfig}
 * bean which will take precedence via {@code @ConditionalOnMissingBean}.
 */
@ConfigurationProperties(prefix = "adk.run-config")
public class AdkRunConfigProperties {

  /** Streaming mode for the agent loop. Defaults to {@link RunConfig.StreamingMode#NONE}. */
  private RunConfig.StreamingMode streamingMode = RunConfig.StreamingMode.NONE;

  /** Maximum number of LLM calls per invocation before the agent loop is interrupted. */
  private int maxLlmCalls = 500;

  /** Tool execution policy. Defaults to {@link RunConfig.ToolExecutionMode#NONE} (parallel-ish). */
  private RunConfig.ToolExecutionMode toolExecutionMode = RunConfig.ToolExecutionMode.NONE;

  /** Persist user-supplied input blobs as artifacts on session creation. */
  private boolean saveInputBlobsAsArtifacts = false;

  /** Auto-create the session if a runner invocation references an unknown session id. */
  private boolean autoCreateSession = false;

  public RunConfig.StreamingMode getStreamingMode() {
    return streamingMode;
  }

  public void setStreamingMode(RunConfig.StreamingMode streamingMode) {
    this.streamingMode = streamingMode;
  }

  public int getMaxLlmCalls() {
    return maxLlmCalls;
  }

  public void setMaxLlmCalls(int maxLlmCalls) {
    this.maxLlmCalls = maxLlmCalls;
  }

  public RunConfig.ToolExecutionMode getToolExecutionMode() {
    return toolExecutionMode;
  }

  public void setToolExecutionMode(RunConfig.ToolExecutionMode toolExecutionMode) {
    this.toolExecutionMode = toolExecutionMode;
  }

  public boolean isSaveInputBlobsAsArtifacts() {
    return saveInputBlobsAsArtifacts;
  }

  public void setSaveInputBlobsAsArtifacts(boolean saveInputBlobsAsArtifacts) {
    this.saveInputBlobsAsArtifacts = saveInputBlobsAsArtifacts;
  }

  public boolean isAutoCreateSession() {
    return autoCreateSession;
  }

  public void setAutoCreateSession(boolean autoCreateSession) {
    this.autoCreateSession = autoCreateSession;
  }
}
