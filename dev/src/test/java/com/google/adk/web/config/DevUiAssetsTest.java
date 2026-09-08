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

package com.google.adk.web.config;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.Test;

/** The {@code adk.web.ui.dir} normalization the resource handler resolves assets through. */
public class DevUiAssetsTest {

  @Test
  public void assetRoot_noDirConfigured_fallsBackToTheBundledCopy() {
    assertThat(DevUiAssets.assetRoot(null)).isEqualTo("classpath:/browser/");
    assertThat(DevUiAssets.assetRoot("")).isEqualTo("classpath:/browser/");
  }

  @Test
  public void assetRoot_configuredDir_becomesAFileUrl() {
    assertThat(DevUiAssets.assetRoot("/srv/ui")).isEqualTo("file:/srv/ui/");
    assertThat(DevUiAssets.assetRoot("/srv/ui/")).isEqualTo("file:/srv/ui/");
    assertThat(DevUiAssets.assetRoot("file:/srv/ui")).isEqualTo("file:/srv/ui/");
  }

  @Test
  public void assetRoot_windowsSeparators_areNormalized() {
    assertThat(DevUiAssets.assetRoot("C:\\srv\\ui")).isEqualTo("file:C:/srv/ui/");
  }

  @Test
  public void assetLocation_appendsToTheRoot() {
    assertThat(DevUiAssets.assetLocation(null, DevUiAssets.RUNTIME_CONFIG_PATH))
        .isEqualTo("classpath:/browser/assets/config/runtime-config.json");
  }
}
