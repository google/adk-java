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

import com.google.adk.JsonBaseModel;
import com.google.common.collect.ImmutableList;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class CacheMetadataTest {

  private static final CacheMetadata ACTIVE =
      CacheMetadata.builder()
          .fingerprint("abc123")
          .contentsCount(4)
          .cacheName("projects/1/locations/us-central1/cachedContents/42")
          .expireTime(Instant.ofEpochSecond(2_000_000_000L, 250_000_000))
          .invocationsUsed(3)
          .createdAt(Instant.ofEpochSecond(1_999_998_200L))
          .build();

  @Test
  public void build_fingerprintOnly_hasNoCacheFields() {
    CacheMetadata metadata = CacheMetadata.builder().fingerprint("abc123").contentsCount(0).build();

    assertThat(metadata.fingerprint()).isEqualTo("abc123");
    assertThat(metadata.contentsCount()).isEqualTo(0);
    assertThat(metadata.cacheName()).isEmpty();
    assertThat(metadata.expireTime()).isEmpty();
    assertThat(metadata.invocationsUsed()).isEmpty();
    assertThat(metadata.createdAt()).isEmpty();
  }

  @Test
  public void build_negativeContentsCount_throws() {
    CacheMetadata.Builder builder = CacheMetadata.builder().fingerprint("abc123").contentsCount(-1);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_negativeInvocationsUsed_throws() {
    CacheMetadata.Builder builder = ACTIVE.toBuilder().invocationsUsed(-1);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_cacheNameWithoutExpireTime_throws() {
    CacheMetadata.Builder builder = ACTIVE.toBuilder().expireTime(null);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_cacheNameWithoutInvocationsUsed_throws() {
    CacheMetadata.Builder builder = ACTIVE.toBuilder().invocationsUsed(null);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_expireTimeWithoutCacheName_throws() {
    CacheMetadata.Builder builder = ACTIVE.toBuilder().cacheName(null).invocationsUsed(null);

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void build_withoutContentsCount_throws() {
    CacheMetadata.Builder builder = CacheMetadata.builder().fingerprint("abc123");

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void toJson_activeCache_roundTrips() {
    String json = ACTIVE.toJson();

    assertThat(json).contains("\"contentsCount\":4");
    assertThat(JsonBaseModel.fromJsonString(json, CacheMetadata.class)).isEqualTo(ACTIVE);
  }

  @Test
  public void toJson_fingerprintOnly_omitsCacheFields() throws Exception {
    CacheMetadata metadata = CacheMetadata.builder().fingerprint("abc123").contentsCount(2).build();

    String json = metadata.toJson();

    assertThat(ImmutableList.copyOf(JsonBaseModel.getMapper().readTree(json).fieldNames()))
        .containsExactly("fingerprint", "contentsCount");
    assertThat(JsonBaseModel.fromJsonString(json, CacheMetadata.class)).isEqualTo(metadata);
  }

  @Test
  public void fromJson_writtenByAdkPython_readsSnakeCaseAndEpochSeconds() {
    String json =
        "{\"cache_name\": \"projects/1/locations/us-central1/cachedContents/42\","
            + " \"expire_time\": 2000000000.25, \"fingerprint\": \"abc123\","
            + " \"invocations_used\": 3, \"contents_count\": 4, \"created_at\": 1999998200.0}";

    assertThat(JsonBaseModel.fromJsonString(json, CacheMetadata.class)).isEqualTo(ACTIVE);
  }

  @Test
  public void fromJson_writtenByAdkKotlin_readsEpochMilliseconds() {
    String json =
        "{\"cacheName\": \"projects/1/locations/us-central1/cachedContents/42\","
            + " \"expireTime\": 2000000000250, \"fingerprint\": \"abc123\","
            + " \"invocationsUsed\": 3, \"contentsCount\": 4, \"createdAt\": 1999998200000}";

    assertThat(JsonBaseModel.fromJsonString(json, CacheMetadata.class)).isEqualTo(ACTIVE);
  }

  @Test
  public void fromJson_isoTimestamps_reads() {
    String json =
        "{\"cacheName\": \"projects/1/locations/us-central1/cachedContents/42\","
            + " \"expireTime\": \"2033-05-18T03:33:20.250Z\", \"fingerprint\": \"abc123\","
            + " \"invocationsUsed\": 3, \"contentsCount\": 4,"
            + " \"createdAt\": \"2033-05-18T03:03:20Z\"}";

    assertThat(JsonBaseModel.fromJsonString(json, CacheMetadata.class)).isEqualTo(ACTIVE);
  }

  @Test
  public void fromJson_epochAtSecondsMillisBoundary_switchesUnit() {
    CacheMetadata below = fingerprintOnlyWithCreatedAt("99999999999");
    CacheMetadata atBoundary = fingerprintOnlyWithCreatedAt("100000000000");

    assertThat(below.createdAt()).hasValue(Instant.ofEpochSecond(99_999_999_999L));
    assertThat(atBoundary.createdAt()).hasValue(Instant.ofEpochSecond(100_000_000L));
  }

  @Test
  public void fromJson_negativeFractionalSeconds_reads() {
    assertThat(fingerprintOnlyWithCreatedAt("-1.5").createdAt())
        .hasValue(Instant.ofEpochSecond(-2, 500_000_000));
  }

  @Test
  public void fromJson_hugeExponent_throwsWithoutExpandingIt() {
    assertThrows(IllegalStateException.class, () -> fingerprintOnlyWithCreatedAt("1e20000000"));
  }

  @Test
  public void fromJson_negativeEpochMilliseconds_readsByMagnitude() {
    assertThat(fingerprintOnlyWithCreatedAt("-1e12").createdAt())
        .hasValue(Instant.ofEpochSecond(-1_000_000_000));
  }

  @Test
  public void fromJson_beforeYearOne_throws() {
    // -7e13 ms is -7e10 s, before year one.
    assertThrows(IllegalStateException.class, () -> fingerprintOnlyWithCreatedAt("-7e13"));
  }

  @Test
  public void fromJson_malformedTimestampText_throws() {
    assertThrows(IllegalStateException.class, () -> fingerprintOnlyWithCreatedAt("\"not-a-time\""));
  }

  @Test
  public void fromJson_booleanTimestamp_throws() {
    String json =
        "{\"cacheName\": \"cachedContents/42\", \"expireTime\": true,"
            + " \"fingerprint\": \"abc123\", \"invocationsUsed\": 3, \"contentsCount\": 4}";

    assertThrows(
        IllegalStateException.class, () -> JsonBaseModel.fromJsonString(json, CacheMetadata.class));
  }

  private static CacheMetadata fingerprintOnlyWithCreatedAt(String createdAtJson) {
    return JsonBaseModel.fromJsonString(
        "{\"fingerprint\": \"abc123\", \"contentsCount\": 0, \"createdAt\": " + createdAtJson + "}",
        CacheMetadata.class);
  }

  @Test
  public void toString_fingerprintOnly_showsShortFingerprint() {
    CacheMetadata metadata =
        CacheMetadata.builder().fingerprint("abc123456789abcd").contentsCount(2).build();

    assertThat(metadata.toString())
        .isEqualTo("Fingerprint-only: 2 contents, fingerprint=abc12345...");
  }

  @Test
  public void toString_activeCache_showsCacheIdAndUse() {
    CacheMetadata metadata =
        ACTIVE.toBuilder().expireTime(Instant.now().plus(Duration.ofMinutes(10))).build();

    assertThat(metadata.toString())
        .matches("Cache 42: used 3 invocations, cached 4 contents, expires in (9\\.9|10\\.0)min");
  }
}
