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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.agents.ContextCacheConfig;
import com.google.adk.agents.Role;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.google.genai.JsonSerializable;
import com.google.genai.errors.ApiException;
import com.google.genai.types.CachedContent;
import com.google.genai.types.Content;
import com.google.genai.types.CreateCachedContentConfig;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Tool;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, reuses and deletes the Gemini context cache of a request that carries a {@link
 * ContextCacheConfig}. A cache covers the system instruction, tools and leading contents, and a
 * fingerprint of them decides whether a later request can reuse it.
 */
final class GeminiContextCacheManager {

  private static final Logger logger = LoggerFactory.getLogger(GeminiContextCacheManager.class);

  // Documented explicit-cache minimums; for tuned-model and endpoint IDs the server decides.
  private static final int GEMINI_2_5_MIN_CACHE_TOKENS = 2048;
  private static final int GEMINI_3_MIN_CACHE_TOKENS = 4096;

  private static final int FINGERPRINT_LENGTH = 16;
  private static final int CHARS_PER_TOKEN = 4;
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final Client apiClient;
  private final String model;
  private final InstantSource clock;

  /** Creates a manager for caches of {@code model}, the model the requests are sent to. */
  GeminiContextCacheManager(Client apiClient, String model) {
    this(apiClient, model, InstantSource.system());
  }

  /** Creates a manager that reads the current time from {@code clock}, for tests. */
  @VisibleForTesting
  GeminiContextCacheManager(Client apiClient, String model, InstantSource clock) {
    this.apiClient = apiClient;
    this.model = model;
    this.clock = clock;
  }

  /**
   * The request to send, which uses a cache when one applies, and the metadata for its response.
   */
  record CacheResult(LlmRequest request, CacheMetadata metadata) {}

  /**
   * Reuses the request's cache while it is valid, and otherwise deletes it and creates a new one
   * once the request's cacheable prefix is known to be stable. The returned single fails with
   * {@link IllegalArgumentException} if the request has no cache config.
   */
  Single<CacheResult> handleContextCaching(LlmRequest request) {
    return Single.defer(
        () -> {
          ContextCacheConfig config =
              request
                  .cacheConfig()
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Context caching requires a cache configuration."));
          Optional<CacheMetadata> previous = request.cacheMetadata();
          if (previous.isEmpty()) {
            // A cache is never created without an earlier fingerprint to compare with.
            return Single.just(new CacheResult(request, fingerprintOnly(request)));
          }
          CacheMetadata metadata = previous.get();
          if (isCacheValid(request, metadata, config)) {
            logger.debug("Reusing cache {}.", metadata.cacheName().get());
            return Single.just(
                new CacheResult(
                    applyCache(request, metadata.cacheName().get(), metadata.contentsCount()),
                    metadata));
          }
          return metadata
              .cacheName()
              .map(this::deleteCache)
              .orElseGet(Completable::complete)
              .andThen(Single.defer(() -> refreshCache(request, metadata, config)));
        });
  }

  /**
   * Creates a cache when the previously fingerprinted prefix is unchanged, growing it to the
   * current cacheable prefix; otherwise starts a new fingerprint chain.
   */
  private Single<CacheResult> refreshCache(
      LlmRequest request, CacheMetadata previous, ContextCacheConfig config) {
    if (!fingerprint(request, previous.contentsCount()).equals(previous.fingerprint())) {
      logger.debug("Cacheable prefix changed; returning fingerprint-only metadata.");
      return Single.just(new CacheResult(request, fingerprintOnly(request)));
    }
    int contentsCount =
        Math.max(previous.contentsCount(), countContentsToCache(request.contents()));
    // Keeping the grown prefix when creation fails lets the next request try again.
    CacheMetadata grownPrefix = fingerprintOnly(request, contentsCount);
    return createCache(request, grownPrefix, config)
        .map(
            created ->
                new CacheResult(
                    applyCache(request, created.cacheName().get(), contentsCount), created))
        .defaultIfEmpty(new CacheResult(request, grownPrefix));
  }

  private CacheMetadata fingerprintOnly(LlmRequest request) {
    return fingerprintOnly(request, countContentsToCache(request.contents()));
  }

  private CacheMetadata fingerprintOnly(LlmRequest request, int contentsCount) {
    return CacheMetadata.builder()
        .fingerprint(fingerprint(request, contentsCount))
        .contentsCount(contentsCount)
        .build();
  }

  /** Returns the number of contents before the last run of user contents. */
  private static int countContentsToCache(List<Content> contents) {
    int lastUserRunStart = contents.size();
    for (int i = contents.size() - 1; i >= 0; i--) {
      if (!Ascii.equalsIgnoreCase(contents.get(i).role().orElse(""), Role.USER)) {
        break;
      }
      lastUserRunStart = i;
    }
    return lastUserRunStart;
  }

  private boolean isCacheValid(
      LlmRequest request, CacheMetadata metadata, ContextCacheConfig config) {
    if (metadata.cacheName().isEmpty()) {
      return false;
    }
    String cacheName = metadata.cacheName().get();
    if (!clock.instant().isBefore(metadata.expireTime().get())) {
      logger.info("Cache {} expired.", cacheName);
      return false;
    }
    if (metadata.invocationsUsed().get() > config.maxInvocations()) {
      logger.info(
          "Cache {} was used by {} invocations, more than {}.",
          cacheName,
          metadata.invocationsUsed().get(),
          config.maxInvocations());
      return false;
    }
    if (!fingerprint(request, metadata.contentsCount()).equals(metadata.fingerprint())) {
      logger.debug("Cache {} no longer matches the request.", cacheName);
      return false;
    }
    return true;
  }

  /**
   * Returns a hash of everything a cache of the first {@code contentsCount} contents would hold,
   * plus the model and backend that own the cache. Keys are sorted and tools are put in a fixed
   * order, so equivalent requests always hash the same.
   */
  private String fingerprint(LlmRequest request, int contentsCount) {
    ObjectNode data = NODES.objectNode();
    data.put("model", model);
    data.set("cache_scope", cacheScope());
    request
        .config()
        .ifPresent(
            config -> {
              config
                  .systemInstruction()
                  .ifPresent(instruction -> data.set("system_instruction", tree(instruction)));
              config
                  .tools()
                  .filter(tools -> !tools.isEmpty())
                  .ifPresent(tools -> data.set("tools", canonicalTools(tools)));
              config
                  .toolConfig()
                  .ifPresent(toolConfig -> data.set("tool_config", tree(toolConfig)));
            });
    List<Content> cachedContents = leading(request.contents(), contentsCount);
    if (!cachedContents.isEmpty()) {
      ArrayNode contents = data.putArray("cached_contents");
      cachedContents.forEach(content -> contents.add(tree(content)));
    }
    return sha256Hex(data.toString()).substring(0, FINGERPRINT_LENGTH);
  }

  /** The backend namespace that owns the cache, so a cache is never reused across backends. */
  private ObjectNode cacheScope() {
    ObjectNode scope = NODES.objectNode();
    scope.put("backend", apiClient.vertexAI() ? "vertex" : "gemini");
    if (apiClient.vertexAI()) {
      scope.put("project", apiClient.project());
      scope.put("location", apiClient.location());
    }
    return scope;
  }

  private static ArrayNode canonicalTools(List<Tool> tools) {
    List<JsonNode> canonical = new ArrayList<>();
    for (Tool tool : tools) {
      ObjectNode node = (ObjectNode) tree(tool);
      JsonNode declarations = node.get("functionDeclarations");
      if (declarations != null && declarations.isArray()) {
        List<JsonNode> sorted = new ArrayList<>();
        declarations.forEach(sorted::add);
        sorted.sort(Comparator.comparing(declaration -> declaration.path("name").asText("")));
        node.set("functionDeclarations", NODES.arrayNode().addAll(sorted));
      }
      canonical.add(node);
    }
    canonical.sort(Comparator.comparing(JsonNode::toString));
    return NODES.arrayNode().addAll(canonical);
  }

  /** Returns the wire JSON of a GenAI type, with object keys sorted at every level. */
  private static JsonNode tree(Object genaiObject) {
    return sortedKeys(JsonSerializable.toJsonNode(genaiObject));
  }

  private static JsonNode sortedKeys(JsonNode node) {
    if (node.isObject()) {
      ObjectNode sorted = NODES.objectNode();
      ImmutableList.sortedCopyOf(ImmutableList.copyOf(node.fieldNames()))
          .forEach(name -> sorted.set(name, sortedKeys(node.get(name))));
      return sorted;
    }
    if (node.isArray()) {
      ArrayNode sorted = NODES.arrayNode();
      node.forEach(element -> sorted.add(sortedKeys(element)));
      return sorted;
    }
    return node;
  }

  private static String sha256Hex(String text) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /**
   * Creates a cache of the system instruction, tools and the contents {@code prefix} counts, or
   * completes empty when the request is too small to cache or creation fails.
   */
  private Maybe<CacheMetadata> createCache(
      LlmRequest request, CacheMetadata prefix, ContextCacheConfig config) {
    int contentsCount = prefix.contentsCount();
    Optional<Integer> previousTokenCount = request.cacheableContentsTokenCount();
    if (previousTokenCount.isEmpty()) {
      logger.info("No previous token count available; not creating a cache.");
      return Maybe.empty();
    }
    if (previousTokenCount.get() < config.minTokens()) {
      logger.info(
          "Previous request too small for caching ({} < {} tokens).",
          previousTokenCount.get(),
          config.minTokens());
      return Maybe.empty();
    }
    OptionalInt minimumTokens = minimumCacheTokens(model);
    if (minimumTokens.isPresent()) {
      int prefixTokens =
          estimateCacheablePrefixTokens(request, contentsCount, previousTokenCount.get());
      if (prefixTokens < minimumTokens.getAsInt()) {
        logger.info(
            "Cacheable prefix below the model's minimum cache size ({} < {} tokens).",
            prefixTokens,
            minimumTokens.getAsInt());
        return Maybe.empty();
      }
    }

    CreateCachedContentConfig.Builder cacheConfig =
        CreateCachedContentConfig.builder()
            .ttl(config.ttl())
            .displayName(
                "adk-cache-" + clock.instant().getEpochSecond() + "-" + contentsCount + "contents");
    List<Content> cachedContents = leading(request.contents(), contentsCount);
    if (!cachedContents.isEmpty()) {
      cacheConfig.contents(cachedContents);
    }
    request
        .config()
        .ifPresent(
            generateConfig -> {
              generateConfig.systemInstruction().ifPresent(cacheConfig::systemInstruction);
              generateConfig
                  .tools()
                  .filter(tools -> !tools.isEmpty())
                  .ifPresent(cacheConfig::tools);
              generateConfig.toolConfig().ifPresent(cacheConfig::toolConfig);
            });

    // Blocking, as Gemini does on generateContent, keeps the model call on the caller's thread.
    return Maybe.defer(
            () -> Maybe.fromFuture(apiClient.async.caches.create(model, cacheConfig.build())))
        .map(cachedContent -> toMetadata(cachedContent, prefix, config))
        .doOnSuccess(metadata -> logger.info("Created cache {}.", metadata.cacheName().get()))
        .onErrorResumeNext(
            error -> {
              if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return Maybe.error(error);
              }
              logger.warn("Failed to create cache: {}", describe(error));
              return Maybe.empty();
            });
  }

  private CacheMetadata toMetadata(
      CachedContent cachedContent, CacheMetadata prefix, ContextCacheConfig config) {
    Instant createdAt = clock.instant();
    String cacheName =
        cachedContent
            .name()
            .filter(name -> !name.isEmpty())
            .orElseThrow(
                () -> new IllegalStateException("The cache service returned no cache name."));
    return prefix.toBuilder()
        .cacheName(cacheName)
        .expireTime(cachedContent.expireTime().orElse(createdAt.plus(config.ttl())))
        .invocationsUsed(1)
        .createdAt(createdAt)
        .build();
  }

  private static OptionalInt minimumCacheTokens(String model) {
    String name = model.substring(model.lastIndexOf('/') + 1);
    if (name.startsWith("gemini-2.5-")) {
      return OptionalInt.of(GEMINI_2_5_MIN_CACHE_TOKENS);
    }
    if (name.startsWith("gemini-3")) {
      return OptionalInt.of(GEMINI_3_MIN_CACHE_TOKENS);
    }
    return OptionalInt.empty();
  }

  /**
   * Scales the previous prompt's token count by the share of characters the cached prefix has in
   * the request, since only the whole prompt has an exact count.
   */
  private static int estimateCacheablePrefixTokens(
      LlmRequest request, int contentsCount, int fullTokens) {
    long fullEstimate = estimateTokens(request, request.contents().size());
    if (fullEstimate <= 0) {
      // Nothing to estimate from, such as binary-only parts: trust the exact count.
      return fullTokens;
    }
    long prefixEstimate = estimateTokens(request, contentsCount);
    double ratio = Math.min(1.0, (double) prefixEstimate / fullEstimate);
    return (int) (fullTokens * ratio);
  }

  /** Roughly estimates the tokens of the system instruction, tools and leading contents. */
  private static long estimateTokens(LlmRequest request, int contentsCount) {
    long chars = 0;
    Optional<GenerateContentConfig> config = request.config();
    if (config.isPresent()) {
      chars +=
          config.get().systemInstruction().map(GeminiContextCacheManager::textLength).orElse(0L);
      for (Tool tool : config.get().tools().orElse(ImmutableList.of())) {
        chars += tool.toJson().length();
      }
    }
    for (Content content : leading(request.contents(), contentsCount)) {
      chars += textLength(content);
    }
    return chars / CHARS_PER_TOKEN;
  }

  private static long textLength(Content content) {
    return content.parts().orElse(ImmutableList.of()).stream()
        .mapToLong(part -> part.text().map(String::length).orElse(0))
        .sum();
  }

  private Completable deleteCache(String cacheName) {
    return Completable.defer(
            () ->
                Completable.fromFuture(
                    apiClient.async.caches.delete(cacheName, /* config= */ null)))
        .doOnComplete(() -> logger.info("Deleted cache {}.", cacheName))
        .onErrorComplete(
            error -> {
              if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return false;
              }
              logger.warn("Failed to delete cache {}: {}", cacheName, describe(error));
              return true;
            });
  }

  /**
   * Rewrites the request to reference the cache instead of sending what it holds. The final content
   * is always sent, because the API rejects a request with no contents.
   */
  private static LlmRequest applyCache(LlmRequest request, String cacheName, int contentsCount) {
    GenerateContentConfig config =
        request.config().orElseGet(() -> GenerateContentConfig.builder().build()).toBuilder()
            .clearSystemInstruction()
            .clearTools()
            .clearToolConfig()
            .cachedContent(cacheName)
            .build();
    List<Content> contents = request.contents();
    int removable = Math.min(contentsCount, Math.max(contents.size() - 1, 0));
    return request.toBuilder()
        .config(config)
        .contents(ImmutableList.copyOf(contents.subList(removable, contents.size())))
        .build();
  }

  private static List<Content> leading(List<Content> contents, int count) {
    return contents.subList(0, Math.min(count, contents.size()));
  }

  /** Describes a failure without its message, which can echo request content. */
  private static String describe(Throwable error) {
    Throwable cause =
        error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
    if (cause instanceof ApiException apiException) {
      return cause.getClass().getSimpleName()
          + " (code "
          + apiException.code()
          + ", status "
          + apiException.status()
          + ")";
    }
    return cause.getClass().getSimpleName();
  }
}
