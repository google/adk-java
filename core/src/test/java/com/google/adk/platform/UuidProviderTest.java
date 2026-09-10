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

package com.google.adk.platform;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class UuidProviderTest {

  @Test
  public void system_returnsUniqueUuidStrings() {
    String first = UuidProvider.SYSTEM.newUuid();
    String second = UuidProvider.SYSTEM.newUuid();

    assertThat(first).isNotEmpty();
    assertThat(first).isNotEqualTo(second);
    assertThat(first).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  }

  @Test
  public void customProvider_returnsDeterministicSequence() {
    UuidProvider provider =
        new UuidProvider() {
          private final AtomicInteger counter = new AtomicInteger();

          @Override
          public String newUuid() {
            return String.format("uuid-%04d", counter.getAndIncrement());
          }
        };

    assertThat(provider.newUuid()).isEqualTo("uuid-0000");
    assertThat(provider.newUuid()).isEqualTo("uuid-0001");
    assertThat(provider.newUuid()).isEqualTo("uuid-0002");
  }
}
