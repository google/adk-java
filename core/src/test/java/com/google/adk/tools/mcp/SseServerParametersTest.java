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

package com.google.adk.tools.mcp;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SseServerParameters}. */
@RunWith(JUnit4.class)
public final class SseServerParametersTest {

  @Test
  public void builder_withHeadersProvider_returnsSupplier() {
    Supplier<Map<String, Object>> provider = () -> ImmutableMap.of("Authorization", "Bearer token");

    SseServerParameters params =
        SseServerParameters.builder()
            .url("http://localhost:8080")
            .headersProvider(provider)
            .build();

    assertThat(params.headersProvider()).isSameInstanceAs(provider);
    assertThat(params.headersProvider().get()).containsEntry("Authorization", "Bearer token");
  }

  @Test
  public void builder_withHeadersProvider_null_returnsNull() {
    SseServerParameters params = SseServerParameters.builder().url("http://localhost:8080").build();

    assertThat(params.headersProvider()).isNull();
  }
}
