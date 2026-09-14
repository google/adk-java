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

package com.google.adk.artifacts;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ArtifactVersionTest {

  private static final Instant CREATE_TIME = Instant.ofEpochSecond(1_700_000_000L);

  private static ArtifactVersion.Builder minimalBuilder() {
    return ArtifactVersion.builder()
        .version(3)
        .canonicalUri("gs://bucket/report.pdf#3")
        .createTime(CREATE_TIME);
  }

  @Test
  public void builder_omittingOptionalFields_leavesThemEmpty() {
    ArtifactVersion artifactVersion = minimalBuilder().build();

    assertThat(artifactVersion.customMetadata()).isEmpty();
    assertThat(artifactVersion.mimeType()).isEmpty();
  }

  @Test
  public void builder_setsValues() {
    ArtifactVersion artifactVersion =
        minimalBuilder()
            .mimeType("application/pdf")
            .customMetadata(ImmutableMap.of("source", "tool"))
            .build();

    assertThat(artifactVersion.version()).isEqualTo(3);
    assertThat(artifactVersion.canonicalUri()).isEqualTo("gs://bucket/report.pdf#3");
    assertThat(artifactVersion.createTime()).isEqualTo(CREATE_TIME);
    assertThat(artifactVersion.mimeType()).hasValue("application/pdf");
    assertThat(artifactVersion.customMetadata()).containsExactly("source", "tool");
  }

  @Test
  public void customMetadata_isCopiedFromTheCallersMap() {
    Map<String, Object> callerMap = new HashMap<>();
    callerMap.put("source", "tool");

    ArtifactVersion artifactVersion = minimalBuilder().customMetadata(callerMap).build();
    callerMap.put("source", "mutated");

    assertThat(artifactVersion.customMetadata()).containsExactly("source", "tool");
  }

  @Test
  public void build_withoutCanonicalUri_throws() {
    ArtifactVersion.Builder builder = ArtifactVersion.builder().version(1).createTime(CREATE_TIME);

    IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
    assertThat(e).hasMessageThat().contains("canonicalUri");
  }
}
