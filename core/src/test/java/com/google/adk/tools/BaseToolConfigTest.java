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

package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class BaseToolConfigTest {

  /** Stands in for the config of a tool that takes one argument. */
  public static final class WeatherToolConfig extends BaseToolConfig {
    private String city;

    // Bound by name through this setter, which is how the deserializer discovers the property
    // without a binding annotation.
    public void setCity(String city) {
      this.city = city;
    }

    public String city() {
      return city;
    }
  }

  @Test
  public void declaredArg_isRead() {
    WeatherToolConfig config =
        JsonBaseModel.getMapper()
            .convertValue(ImmutableMap.of("city", "Zurich"), WeatherToolConfig.class);

    assertThat(config.city()).isEqualTo("Zurich");
  }

  @Test
  public void undeclaredArg_isRefused() {
    // The shared mapper drops unknown keys rather than failing on them, so without the base class
    // refusing them this misspelling would silently read back as no city at all.
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                JsonBaseModel.getMapper()
                    .convertValue(ImmutableMap.of("citty", "Zurich"), WeatherToolConfig.class));

    assertThat(exception).hasMessageThat().contains("citty");
  }

  @Test
  public void undeclaredArgAlongsideDeclaredOne_isRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            JsonBaseModel.getMapper()
                .convertValue(
                    ImmutableMap.of("city", "Zurich", "units", "celsius"),
                    WeatherToolConfig.class));
  }
}
