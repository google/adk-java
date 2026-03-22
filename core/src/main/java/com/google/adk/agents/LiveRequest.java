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

package com.google.adk.agents;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import java.util.Optional;
import javax.annotation.Nullable;

/** Represents a request to be sent to a live connection to the LLM model. */
@AutoValue
@JsonDeserialize(builder = LiveRequest.Builder.class)
public abstract class LiveRequest extends JsonBaseModel {

  LiveRequest() {}

  /**
   * Returns the content of the request.
   *
   * <p>If set, send the content to the model in turn-by-turn mode.
   *
   * @return An optional {@link Content} object containing the content of the request.
   */
  @JsonProperty("content")
  public abstract Optional<Content> content();

  /**
   * Returns the blob of the request.
   *
   * <p>If set, send the blob to the model in realtime mode.
   *
   * @return An optional {@link Blob} object containing the blob of the request.
   */
  @JsonProperty("blob")
  public abstract Optional<Blob> blob();

  /**
   * Returns whether the connection should be closed.
   *
   * <p>If set to true, the connection will be closed after the request is sent.
   *
   * @return A boolean indicating whether the connection should be closed.
   */
  @JsonProperty("close")
  public abstract Optional<Boolean> close();

  /** Extracts boolean value from the close field or returns false if unset. */
  public boolean shouldClose() {
    return close().orElse(false);
  }

  /** Builder for constructing {@link LiveRequest} instances. */
  @AutoValue.Builder
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  public abstract static class Builder {
    @JsonProperty("content")
    public abstract Builder content(@Nullable Content content);

    @JsonProperty("blob")
    public abstract Builder blob(@Nullable Blob blob);

    @JsonProperty("close")
    public abstract Builder close(@Nullable Boolean close);

    abstract LiveRequest autoBuild();

    public final LiveRequest build() {
      LiveRequest request = autoBuild();
      Preconditions.checkState(
          request.content().isPresent()
              || request.blob().isPresent()
              || request.close().isPresent(),
          "One of content, blob, or close must be set");
      return request;
    }
  }

  public static Builder builder() {
    return new AutoValue_LiveRequest.Builder().close(false);
  }

  public abstract Builder toBuilder();

  /** Deserializes a Json string to a {@link LiveRequest} object. */
  public static LiveRequest fromJsonString(String json) {
    return JsonBaseModel.fromJsonString(json, LiveRequest.class);
  }

  @JsonCreator
  static LiveRequest.Builder jacksonBuilder() {
    return LiveRequest.builder();
  }
}
