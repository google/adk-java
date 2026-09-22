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

package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the {@link ModelProvider} default methods. */
@RunWith(JUnit4.class)
public final class ModelProviderTest {

  /** Provider capturing the bare model name it receives. */
  private static final class CapturingProvider implements ModelProvider {
    private final String prefix;
    private final AtomicReference<String> seenBareModelName = new AtomicReference<>();

    CapturingProvider(String prefix) {
      this.prefix = prefix;
    }

    @Override
    public String prefix() {
      return prefix;
    }

    @Override
    public BaseLlm createFromBareModelName(String bareModelName) {
      seenBareModelName.set(bareModelName);
      return null;
    }
  }

  @Test
  public void modelPattern_derivedFromPrefix() {
    assertThat(new CapturingProvider("myprovider").modelPattern()).isEqualTo("myprovider/.*");
  }

  @Test
  public void modelPattern_blankPrefix_throws() {
    assertThrows(IllegalStateException.class, () -> new CapturingProvider(" ").modelPattern());
  }

  @Test
  public void modelPattern_nullPrefix_throws() {
    assertThrows(IllegalStateException.class, () -> new CapturingProvider(null).modelPattern());
  }

  @Test
  public void create_stripsProviderPrefix() {
    CapturingProvider provider = new CapturingProvider("myprovider");

    provider.create("myprovider/some-model");

    assertThat(provider.seenBareModelName.get()).isEqualTo("some-model");
  }

  @Test
  public void create_keepsNameWithoutPrefix() {
    CapturingProvider provider = new CapturingProvider("myprovider");

    provider.create("some-model");

    assertThat(provider.seenBareModelName.get()).isEqualTo("some-model");
  }

  @Test
  public void create_blankModelName_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> new CapturingProvider("myprovider").create(" "));
  }
}
