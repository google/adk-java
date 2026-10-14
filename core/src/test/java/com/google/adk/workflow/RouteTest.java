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

package com.google.adk.workflow;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.adk.JsonBaseModel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RouteTest {

  @Test
  public void toJson_writesBareScalars() {
    assertThat(JsonBaseModel.toJsonString(new Route.Tag("approve"))).isEqualTo("\"approve\"");
    assertThat(JsonBaseModel.toJsonString(new Route.Num(2))).isEqualTo("2");
    assertThat(JsonBaseModel.toJsonString(new Route.Flag(true))).isEqualTo("true");
    assertThat(JsonBaseModel.toJsonString(Route.Default.INSTANCE)).isEqualTo("\"__DEFAULT__\"");
  }

  @Test
  public void fromJson_readsBareScalars() throws Exception {
    assertThat(read("\"approve\"")).isEqualTo(new Route.Tag("approve"));
    assertThat(read("\"__DEFAULT__\"")).isEqualTo(Route.Default.INSTANCE);
    assertThat(read("7")).isEqualTo(new Route.Num(7));
    assertThat(read("3000000000")).isEqualTo(new Route.Num(3_000_000_000L));
    assertThat(read("false")).isEqualTo(new Route.Flag(false));
  }

  @Test
  public void tag_rejectsTheDefaultSentinel() {
    assertThrows(IllegalArgumentException.class, () -> new Route.Tag(Route.DEFAULT_ROUTE_SENTINEL));
  }

  @Test
  public void fromJson_rejectsOtherValues() {
    assertThrows(JsonMappingException.class, () -> read("1.5"));
    assertThrows(JsonMappingException.class, () -> read("{}"));
  }

  private static Route read(String json) throws Exception {
    return JsonBaseModel.getMapper().readValue(json, Route.class);
  }
}
