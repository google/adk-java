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

  @Test
  public void pathOf_noPathToTake_isEmpty() {
    assertThat(DevUiAssets.pathOf(null)).isEmpty();
    assertThat(DevUiAssets.pathOf("")).isEmpty();
    assertThat(DevUiAssets.pathOf("   ")).isEmpty();
    assertThat(DevUiAssets.pathOf("https://gw.example.com")).isEmpty();
    assertThat(DevUiAssets.pathOf("https://gw.example.com/")).isEmpty();
    // Opaque, so there is no path component at all.
    assertThat(DevUiAssets.pathOf("mailto:someone@example.com")).isEmpty();
    // Unparseable, so nothing can be taken from it.
    assertThat(DevUiAssets.pathOf("https://gw.example.com/my app")).isEmpty();
  }

  @Test
  public void pathOf_takesThePathWithoutTrailingSlashes() {
    assertThat(DevUiAssets.pathOf("https://gw.example.com/my-app")).isEqualTo("/my-app");
    assertThat(DevUiAssets.pathOf("https://gw.example.com/my-app/")).isEqualTo("/my-app");
    assertThat(DevUiAssets.pathOf("https://gw.example.com/my-app///")).isEqualTo("/my-app");
    assertThat(DevUiAssets.pathOf("  https://gw.example.com/my-app  ")).isEqualTo("/my-app");
    assertThat(DevUiAssets.pathOf("https://gw.example.com/a/b?q=1#f")).isEqualTo("/a/b");
  }

  @Test
  public void pathOf_staysPercentEncoded() {
    // The value goes into a Location header, so decoding here would re-encode wrongly, and %2F
    // would turn into a path separator.
    assertThat(DevUiAssets.pathOf("https://gw.example.com/my%20app")).isEqualTo("/my%20app");
    assertThat(DevUiAssets.pathOf("https://gw.example.com/a%2Fb")).isEqualTo("/a%2Fb");
  }

  @Test
  public void pathOf_doubledSlash_doesNotBecomeAHost() {
    // "//my-app/dev-ui/" is protocol-relative: a browser would resolve it to the host "my-app".
    assertThat(DevUiAssets.pathOf("https://gw.example.com//my-app")).isEqualTo("/my-app");
  }
}
