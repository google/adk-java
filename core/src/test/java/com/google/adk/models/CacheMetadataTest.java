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

import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CacheMetadataTest {

  private static final String CACHE_NAME = "projects/123/locations/us-central1/cachedContents/456";

  private static CacheMetadata.Builder fingerprintOnlyBuilder() {
    return CacheMetadata.builder().fingerprint("abc123").contentsCount(4);
  }

  private static CacheMetadata.Builder activeBuilder(Instant expireTime) {
    return fingerprintOnlyBuilder().cacheName(CACHE_NAME).expireTime(expireTime).invocationsUsed(2);
  }

  @Test
  public void build_fingerprintOnly_leavesTheActiveFieldsEmpty() {
    CacheMetadata metadata = fingerprintOnlyBuilder().build();

    assertThat(metadata.fingerprint()).isEqualTo("abc123");
    assertThat(metadata.contentsCount()).isEqualTo(4);
    assertThat(metadata.cacheName()).isEmpty();
    assertThat(metadata.expireTime()).isEmpty();
    assertThat(metadata.invocationsUsed()).isEmpty();
    assertThat(metadata.createdAt()).isEmpty();
  }

  @Test
  public void build_active_setsTheActiveFields() {
    Instant expireTime = Instant.ofEpochSecond(1_700_000_000L);

    CacheMetadata metadata = activeBuilder(expireTime).build();

    assertThat(metadata.cacheName()).hasValue(CACHE_NAME);
    assertThat(metadata.expireTime()).hasValue(expireTime);
    assertThat(metadata.invocationsUsed()).hasValue(2);
  }

  @Test
  public void build_withCacheNameButNoExpireTime_throws() {
    CacheMetadata.Builder builder =
        fingerprintOnlyBuilder().cacheName(CACHE_NAME).invocationsUsed(2);

    IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
    assertThat(e).hasMessageThat().contains("fingerprint-only state");
  }

  @Test
  public void build_withExpireTimeButNoCacheName_throws() {
    CacheMetadata.Builder builder =
        fingerprintOnlyBuilder().expireTime(Instant.ofEpochSecond(1L)).invocationsUsed(2);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_withNegativeContentsCount_throws() {
    CacheMetadata.Builder builder = CacheMetadata.builder().fingerprint("abc123").contentsCount(-1);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(e).hasMessageThat().contains("contentsCount must not be negative");
  }

  @Test
  public void build_withNegativeInvocationsUsed_throws() {
    CacheMetadata.Builder builder =
        fingerprintOnlyBuilder()
            .cacheName(CACHE_NAME)
            .expireTime(Instant.ofEpochSecond(1L))
            .invocationsUsed(-1);

    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(e).hasMessageThat().contains("invocationsUsed must not be negative");
  }

  @Test
  public void expireSoon_isTrueInsideTheRefreshBuffer() {
    CacheMetadata metadata = activeBuilder(Instant.now().plus(Duration.ofMinutes(1))).build();

    assertThat(metadata.expireSoon()).isTrue();
  }

  @Test
  public void expireSoon_isFalseOutsideTheRefreshBuffer() {
    CacheMetadata metadata = activeBuilder(Instant.now().plus(Duration.ofMinutes(10))).build();

    assertThat(metadata.expireSoon()).isFalse();
  }

  @Test
  public void expireSoon_isFalseInTheFingerprintOnlyState() {
    CacheMetadata metadata = fingerprintOnlyBuilder().build();

    assertThat(metadata.expireSoon()).isFalse();
  }

  @Test
  public void jsonRoundTrip_preservesBothStates() {
    CacheMetadata active =
        activeBuilder(Instant.ofEpochSecond(1_700_000_000L))
            .createdAt(Instant.ofEpochSecond(1_699_999_000L))
            .build();
    CacheMetadata fingerprintOnly = fingerprintOnlyBuilder().build();

    assertThat(CacheMetadata.fromJsonString(active.toJson(), CacheMetadata.class))
        .isEqualTo(active);
    assertThat(CacheMetadata.fromJsonString(fingerprintOnly.toJson(), CacheMetadata.class))
        .isEqualTo(fingerprintOnly);
  }
}
