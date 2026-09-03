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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class UiWidgetTest {

  @Test
  public void builder_omittingPayload_leavesItEmpty() {
    UiWidget widget = UiWidget.builder().id("w1").provider("mcp").build();

    assertThat(widget.payload()).isEmpty();
  }

  @Test
  public void build_withoutProvider_throws() {
    UiWidget.Builder builder = UiWidget.builder().id("w1");

    IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
    assertThat(e).hasMessageThat().contains("provider");
  }

  @Test
  public void toBuilder_createsBuilderWithSameValues() {
    UiWidget widget =
        UiWidget.builder()
            .id("w1")
            .provider("mcp")
            .payload(ImmutableMap.of("resource_uri", "ui://chart"))
            .build();

    assertThat(widget.toBuilder().build()).isEqualTo(widget);
  }

  @Test
  public void jsonRoundTrip_keepsProviderPayloadKeysVerbatim() throws Exception {
    UiWidget widget =
        UiWidget.builder()
            .id("w1")
            .provider("mcp")
            .payload(ImmutableMap.of("resource_uri", "ui://chart", "tool_args", "{}"))
            .build();

    String json = JsonBaseModel.getMapper().writeValueAsString(widget);
    UiWidget deserialized = JsonBaseModel.getMapper().readValue(json, UiWidget.class);

    assertThat(deserialized).isEqualTo(widget);
    assertThat(deserialized.payload().keySet()).containsExactly("resource_uri", "tool_args");
  }
}
