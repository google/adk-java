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

package com.google.adk.tools.mcp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.modelcontextprotocol.client.transport.ServerParameters;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Parameters for establishing a MCP stdio connection. */
@AutoValue
@JsonDeserialize(builder = StdioServerParameters.Builder.class)
public abstract class StdioServerParameters {

  /** The command to execute for the stdio server. */
  public abstract String command();

  /** Optional arguments for the command. */
  @Nullable
  public abstract ImmutableList<String> args();

  /** Optional environment variables. */
  @Nullable
  public abstract ImmutableMap<String, String> env();

  /** Creates a new builder for {@link StdioServerParameters}. */
  public static Builder builder() {
    return new AutoValue_StdioServerParameters.Builder();
  }

  /** Converts this to a {@link ServerParameters} instance. */
  public ServerParameters toServerParameters() {
    var builder = ServerParameters.builder(command());
    if (args() != null) {
      builder.args(args());
    }
    if (env() != null) {
      builder.env(env());
    }
    return builder.build();
  }

  /** Builder for {@link StdioServerParameters}. */
  @AutoValue.Builder
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  public abstract static class Builder {
    @JsonCreator
    static StdioServerParameters.Builder jacksonBuilder() {
      return StdioServerParameters.builder();
    }

    /** Sets the command to execute for the stdio server. */
    public abstract Builder command(String command);

    /** Sets the arguments for the command. */
    public abstract Builder args(@Nullable List<String> args);

    /** Sets the environment variables. */
    public abstract Builder env(@Nullable Map<String, String> env);

    /** Builds a new {@link StdioServerParameters} instance. */
    public abstract StdioServerParameters build();
  }
}
