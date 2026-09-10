/*
 * Copyright 2026 Google LLC
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

package com.google.adk.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * Rendering metadata for a UI widget associated with an event.
 *
 * <p>An agent emits one of these to ask the UI to draw something richer than text. The UI picks a
 * renderer from the named provider and hands it the payload.
 */
@AutoValue
@JsonDeserialize(builder = UiWidget.Builder.class)
public abstract class UiWidget extends JsonBaseModel {

  UiWidget() {}

  /** Returns the unique identifier of the UI widget. */
  @JsonProperty("id")
  public abstract String id();

  /**
   * Returns the widget provider identifier, which determines the rendering strategy the UI uses.
   *
   * <p>Known values:
   *
   * <ul>
   *   <li>{@code mcp}: MCP App iframe, rendered with the MCP Apps AppBridge.
   * </ul>
   */
  @JsonProperty("provider")
  public abstract String provider();

  /**
   * Returns the provider-specific data required for rendering, empty when the provider needs none.
   *
   * <p>If the provider is {@code mcp}, the payload holds {@code resource_uri}, {@code tool} and
   * {@code tool_args}. Future providers bring their own fields, so the keys are the provider's
   * rather than this SDK's.
   */
  @JsonProperty("payload")
  public abstract ImmutableMap<String, Object> payload();

  /** Returns a new builder for creating a {@link UiWidget}. */
  public static Builder builder() {
    return new AutoValue_UiWidget.Builder().payload(ImmutableMap.of());
  }

  /** Returns a new builder with a copy of this widget's values. */
  public abstract Builder toBuilder();

  /** Builder for {@link UiWidget}. */
  @AutoValue.Builder
  public abstract static class Builder {

    @JsonCreator
    static Builder create() {
      return UiWidget.builder();
    }

    /**
     * Sets the unique identifier of the UI widget.
     *
     * <p>This is a required field.
     */
    @JsonProperty("id")
    public abstract Builder id(String id);

    /**
     * Sets the widget provider identifier.
     *
     * <p>This is a required field.
     */
    @JsonProperty("provider")
    public abstract Builder provider(String provider);

    /** Sets the provider-specific data required for rendering, copying the given map. */
    @JsonProperty("payload")
    public abstract Builder payload(Map<String, Object> payload);

    /** Builds the immutable {@link UiWidget} object. */
    public abstract UiWidget build();
  }
}
