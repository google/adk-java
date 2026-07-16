/*
 * Copyright 2025 Google LLC
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.store.RedisHelper;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.jspecify.annotations.Nullable;
import reactor.adapter.rxjava.RxJava3Adapter;

/**
 * A Redis-backed implementation of the {@link BaseArtifactService}.
 *
 * @author Sandeep Belgavi
 * @since 2025-10-02
 */
public final class RedisArtifactService implements BaseArtifactService {

  private final RedisReactiveCommands<String, String> commands;
  private final ObjectMapper objectMapper;

  public RedisArtifactService() {
    StatefulRedisConnection<String, String> connection = RedisHelper.getConnection();
    this.commands = connection.reactive();
    this.objectMapper = RedisHelper.getObjectMapper();
  }

  private String artifactKey(String appName, String userId, String sessionId, String filename) {
    return String.format("artifact:%s:%s:%s:%s", appName, userId, sessionId, filename);
  }

  private String artifactIndexKey(String appName, String userId, String sessionId) {
    return String.format("artifact_index:%s:%s:%s", appName, userId, sessionId);
  }

  @Override
  public Single<Integer> saveArtifact(
      String appName, String userId, String sessionId, String filename, Part artifact) {
    String key = artifactKey(appName, userId, sessionId, filename);
    return RxJava3Adapter.monoToSingle(commands.llen(key))
        .flatMap(
            version ->
                RxJava3Adapter.monoToSingle(
                        commands.rpush(key, objectMapper.writeValueAsString(artifact)))
                    .flatMap(
                        v ->
                            RxJava3Adapter.monoToSingle(
                                    commands.sadd(
                                        artifactIndexKey(appName, userId, sessionId), filename))
                                .map(v2 -> version.intValue())));
  }

  @Override
  public Maybe<Part> loadArtifact(
      String appName, String userId, String sessionId, String filename, @Nullable Integer version) {
    String key = artifactKey(appName, userId, sessionId, filename);
    Single<String> data;
    if (version != null) {
      data = RxJava3Adapter.monoToSingle(commands.lindex(key, version));
    } else {
      data = RxJava3Adapter.monoToSingle(commands.lindex(key, -1));
    }
    return data.toMaybe()
        .flatMap(
            json -> {
              try {
                return Maybe.just(objectMapper.readValue(json, Part.class));
              } catch (Exception e) {
                return Maybe.error(e);
              }
            });
  }

  @Override
  public Single<ListArtifactsResponse> listArtifactKeys(
      String appName, String userId, String sessionId) {
    return RxJava3Adapter.fluxToFlowable(
            commands.smembers(artifactIndexKey(appName, userId, sessionId)))
        .toList()
        .map(
            filenames ->
                ListArtifactsResponse.builder().filenames(ImmutableList.copyOf(filenames)).build());
  }

  @Override
  public Completable deleteArtifact(
      String appName, String userId, String sessionId, String filename) {
    return RxJava3Adapter.monoToCompletable(
            commands.del(artifactKey(appName, userId, sessionId, filename)))
        .andThen(
            RxJava3Adapter.monoToCompletable(
                commands.srem(artifactIndexKey(appName, userId, sessionId), filename)));
  }

  @Override
  public Single<ImmutableList<Integer>> listVersions(
      String appName, String userId, String sessionId, String filename) {
    return RxJava3Adapter.monoToSingle(
            commands.llen(artifactKey(appName, userId, sessionId, filename)))
        .map(
            size -> {
              ImmutableList.Builder<Integer> builder = ImmutableList.builder();
              for (int i = 0; i < size; i++) {
                builder.add(i);
              }
              return builder.build();
            });
  }
}
