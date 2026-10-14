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

import static com.google.common.base.Preconditions.checkState;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.google.adk.JsonBaseModel;
import com.google.auto.value.AutoValue;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Context cache state carried on an LLM response, and on its event, from one request to the next.
 *
 * <p>Metadata for an active cache has a {@link #cacheName()}, {@link #expireTime()} and {@link
 * #invocationsUsed()}. Fingerprint-only metadata has none of them: it records the cacheable prefix
 * so that a later request can tell whether the prefix is still the same.
 */
@AutoValue
@JsonDeserialize(builder = CacheMetadata.Builder.class)
public abstract class CacheMetadata extends JsonBaseModel {

  CacheMetadata() {}

  /** Hash of the cacheable state: system instruction, tools and the cached leading contents. */
  @JsonProperty("fingerprint")
  public abstract String fingerprint();

  /** Number of leading contents in the cache, or in the fingerprint when no cache is active. */
  @JsonProperty("contentsCount")
  public abstract int contentsCount();

  /** Full resource name of the cached content; empty when no cache is active. */
  @JsonProperty("cacheName")
  public abstract Optional<String> cacheName();

  /** When the cache expires; empty when no cache is active. */
  @JsonProperty("expireTime")
  public abstract Optional<Instant> expireTime();

  /** Number of invocations that have used the cache; empty when no cache is active. */
  @JsonProperty("invocationsUsed")
  public abstract Optional<Integer> invocationsUsed();

  /** When the cache was created; empty when no cache is active. */
  @JsonProperty("createdAt")
  public abstract Optional<Instant> createdAt();

  public abstract Builder toBuilder();

  public static Builder builder() {
    return new AutoValue_CacheMetadata.Builder();
  }

  /** Returns a short description for logs, in the same shape as ADK Python's. */
  @Override
  public final String toString() {
    if (cacheName().isEmpty()) {
      String shortFingerprint = fingerprint().substring(0, Math.min(8, fingerprint().length()));
      return "Fingerprint-only: "
          + contentsCount()
          + " contents, fingerprint="
          + shortFingerprint
          + "...";
    }
    String cacheId = cacheName().get().substring(cacheName().get().lastIndexOf('/') + 1);
    double minutesToExpiry =
        Duration.between(Instant.now(), expireTime().get()).toMillis() / 60_000.0;
    return String.format(
        Locale.ROOT,
        "Cache %s: used %d invocations, cached %d contents, expires in %.1fmin",
        cacheId,
        invocationsUsed().get(),
        contentsCount(),
        minutesToExpiry);
  }

  /**
   * Builder for {@link CacheMetadata}. The snake_case aliases read metadata that ADK Python wrote.
   */
  @AutoValue.Builder
  @JsonPOJOBuilder(buildMethodName = "build", withPrefix = "")
  public abstract static class Builder {

    @JsonCreator
    static Builder jacksonBuilder() {
      return CacheMetadata.builder();
    }

    @JsonProperty("fingerprint")
    public abstract Builder fingerprint(String fingerprint);

    @JsonProperty("contentsCount")
    @JsonAlias("contents_count")
    public abstract Builder contentsCount(int contentsCount);

    @JsonProperty("cacheName")
    @JsonAlias("cache_name")
    public abstract Builder cacheName(@Nullable String cacheName);

    @JsonProperty("expireTime")
    @JsonAlias("expire_time")
    @JsonDeserialize(using = LenientEpochDeserializer.class)
    public abstract Builder expireTime(@Nullable Instant expireTime);

    @JsonProperty("invocationsUsed")
    @JsonAlias("invocations_used")
    public abstract Builder invocationsUsed(@Nullable Integer invocationsUsed);

    @JsonProperty("createdAt")
    @JsonAlias("created_at")
    @JsonDeserialize(using = LenientEpochDeserializer.class)
    public abstract Builder createdAt(@Nullable Instant createdAt);

    abstract CacheMetadata autoBuild();

    /**
     * Builds the metadata.
     *
     * @throws IllegalStateException if a count is negative, or if only some of {@code cacheName},
     *     {@code expireTime} and {@code invocationsUsed} are set
     */
    public CacheMetadata build() {
      CacheMetadata metadata = autoBuild();
      checkState(metadata.contentsCount() >= 0, "contentsCount must not be negative.");
      checkState(
          metadata.invocationsUsed().orElse(0) >= 0, "invocationsUsed must not be negative.");
      boolean active = metadata.cacheName().isPresent();
      checkState(
          metadata.expireTime().isPresent() == active
              && metadata.invocationsUsed().isPresent() == active,
          "cacheName, expireTime and invocationsUsed must be all set or all unset.");
      return metadata;
    }
  }

  /**
   * Reads a numeric timestamp as epoch seconds when its magnitude is below 1e11 and as epoch
   * milliseconds otherwise, since ADK Python writes seconds and ADK Kotlin writes milliseconds.
   * Also reads ISO-8601 text.
   */
  static final class LenientEpochDeserializer extends JsonDeserializer<Instant> {
    private static final BigDecimal SECONDS_MILLIS_BOUNDARY = new BigDecimal("1e11");
    // Years 1 to 9999, as ADK Kotlin accepts; checked before any arithmetic that could be costly.
    private static final BigDecimal MIN_SECONDS = BigDecimal.valueOf(-62_135_596_800L);
    private static final BigDecimal MAX_SECONDS = BigDecimal.valueOf(253_402_300_799L);

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      if (parser.currentToken() == JsonToken.VALUE_STRING) {
        try {
          return Instant.parse(parser.getText());
        } catch (DateTimeParseException e) {
          return (Instant)
              context.handleWeirdStringValue(
                  Instant.class, parser.getText(), "not an ISO-8601 time");
        }
      }
      if (!parser.currentToken().isNumeric()) {
        return (Instant) context.handleUnexpectedToken(Instant.class, parser);
      }
      BigDecimal value = parser.getDecimalValue();
      BigDecimal seconds =
          value.abs().compareTo(SECONDS_MILLIS_BOUNDARY) < 0 ? value : value.movePointLeft(3);
      if (seconds.compareTo(MIN_SECONDS) < 0 || seconds.compareTo(MAX_SECONDS) > 0) {
        return (Instant) context.handleWeirdNumberValue(Instant.class, value, "out of range");
      }
      return Instant.ofEpochSecond(
          seconds.longValue(), seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
    }
  }
}
