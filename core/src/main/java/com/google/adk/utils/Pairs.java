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

package com.google.adk.utils;

import com.google.common.collect.ImmutableMap;
import java.util.concurrent.ConcurrentHashMap;

/** Utility class for creating ConcurrentHashMaps. */
public final class Pairs {

  private Pairs() {}

  /**
   * Returns a new, empty {@code ConcurrentHashMap}.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @return an empty {@code ConcurrentHashMap}
   */
  public static <K, V> ConcurrentHashMap<K, V> of() {
    return new ConcurrentHashMap<>();
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing a single mapping.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the mapping's key
   * @param v1 the mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mapping
   * @throws NullPointerException if the key or the value is {@code null}
   */
  public static <K, V> ConcurrentHashMap<K, V> of(K k1, V v1) {
    return new ConcurrentHashMap<>(ImmutableMap.of(k1, v1));
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing two mappings. This method leverages {@code
   * java.util.Map.of} for initial validation.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the first mapping's key
   * @param v1 the first mapping's value
   * @param k2 the second mapping's key
   * @param v2 the second mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mappings
   */
  public static <K, V> ConcurrentHashMap<K, V> of(K k1, V v1, K k2, V v2) {
    return new ConcurrentHashMap<>(ImmutableMap.of(k1, v1, k2, v2));
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing three mappings. This method leverages {@code
   * java.util.Map.of} for initial validation.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the first mapping's key
   * @param v1 the first mapping's value
   * @param k2 the second mapping's key
   * @param v2 the second mapping's value
   * @param k3 the third mapping's key
   * @param v3 the third mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mappings
   */
  public static <K, V> ConcurrentHashMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3) {
    return new ConcurrentHashMap<>(ImmutableMap.of(k1, v1, k2, v2, k3, v3));
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing four mappings. This method leverages {@code
   * java.util.Map.of} for initial validation.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the first mapping's key
   * @param v1 the first mapping's value
   * @param k2 the second mapping's key
   * @param v2 the second mapping's value
   * @param k3 the third mapping's key
   * @param v3 the third mapping's value
   * @param k4 the fourth mapping's key
   * @param v4 the fourth mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mappings
   */
  public static <K, V> ConcurrentHashMap<K, V> of(K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4) {
    return new ConcurrentHashMap<>(ImmutableMap.of(k1, v1, k2, v2, k3, v3, k4, v4));
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing five mappings. This method leverages {@code
   * java.util.Map.of} for initial validation.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the first mapping's key
   * @param v1 the first mapping's value
   * @param k2 the second mapping's key
   * @param v2 the second mapping's value
   * @param k3 the third mapping's key
   * @param v3 the third mapping's value
   * @param k4 the fourth mapping's key
   * @param v4 the fourth mapping's value
   * @param k5 the fifth mapping's key
   * @param v5 the fifth mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mappings
   */
  public static <K, V> ConcurrentHashMap<K, V> of(
      K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5) {
    return new ConcurrentHashMap<>(ImmutableMap.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5));
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing six mappings. This method leverages {@code
   * java.util.Map.of} for initial validation.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the first mapping's key
   * @param v1 the first mapping's value
   * @param k2 the second mapping's key
   * @param v2 the second mapping's value
   * @param k3 the third mapping's key
   * @param v3 the third mapping's value
   * @param k4 the fourth mapping's key
   * @param v4 the fourth mapping's value
   * @param k5 the fifth mapping's key
   * @param v5 the fifth mapping's value
   * @param k6 the sixth mapping's key
   * @param v6 the sixth mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mappings
   * @throws IllegalArgumentException if there are any duplicate keys (behavior inherited from
   *     Map.of)
   * @throws NullPointerException if any key or value is {@code null} (behavior inherited from
   *     Map.of)
   */
  public static <K, V> ConcurrentHashMap<K, V> of(
      K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6) {
    return new ConcurrentHashMap<>(ImmutableMap.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6));
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing seven mappings. This method leverages {@code
   * java.util.Map.of} for initial validation.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the first mapping's key
   * @param v1 the first mapping's value
   * @param k2 the second mapping's key
   * @param v2 the second mapping's value
   * @param k3 the third mapping's key
   * @param v3 the third mapping's value
   * @param k4 the fourth mapping's key
   * @param v4 the fourth mapping's value
   * @param k5 the fifth mapping's key
   * @param v5 the fifth mapping's value
   * @param k6 the sixth mapping's key
   * @param v6 the sixth mapping's value
   * @param k7 the seventh mapping's key
   * @param v7 the seventh mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mappings
   */
  public static <K, V> ConcurrentHashMap<K, V> of(
      K k1, V v1, K k2, V v2, K k3, V v3, K k4, V v4, K k5, V v5, K k6, V v6, K k7, V v7) {
    return new ConcurrentHashMap<>(
        ImmutableMap.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7));
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing eight mappings. This method leverages {@code
   * java.util.Map.of} for initial validation.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the first mapping's key
   * @param v1 the first mapping's value
   * @param k2 the second mapping's key
   * @param v2 the second mapping's value
   * @param k3 the third mapping's key
   * @param v3 the third mapping's value
   * @param k4 the fourth mapping's key
   * @param v4 the fourth mapping's value
   * @param k5 the fifth mapping's key
   * @param v5 the fifth mapping's value
   * @param k6 the sixth mapping's key
   * @param v6 the sixth mapping's value
   * @param k7 the seventh mapping's key
   * @param v7 the seventh mapping's value
   * @param k8 the eighth mapping's key
   * @param v8 the eighth mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mappings
   */
  public static <K, V> ConcurrentHashMap<K, V> of(
      K k1,
      V v1,
      K k2,
      V v2,
      K k3,
      V v3,
      K k4,
      V v4,
      K k5,
      V v5,
      K k6,
      V v6,
      K k7,
      V v7,
      K k8,
      V v8) {
    return new ConcurrentHashMap<>(
        ImmutableMap.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8));
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing nine mappings. This method leverages {@code
   * java.util.Map.of} for initial validation.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the first mapping's key
   * @param v1 the first mapping's value
   * @param k2 the second mapping's key
   * @param v2 the second mapping's value
   * @param k3 the third mapping's key
   * @param v3 the third mapping's value
   * @param k4 the fourth mapping's key
   * @param v4 the fourth mapping's value
   * @param k5 the fifth mapping's key
   * @param v5 the fifth mapping's value
   * @param k6 the sixth mapping's key
   * @param v6 the sixth mapping's value
   * @param k7 the seventh mapping's key
   * @param v7 the seventh mapping's value
   * @param k8 the eighth mapping's key
   * @param v8 the eighth mapping's value
   * @param k9 the ninth mapping's key
   * @param v9 the ninth mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mappings
   */
  public static <K, V> ConcurrentHashMap<K, V> of(
      K k1,
      V v1,
      K k2,
      V v2,
      K k3,
      V v3,
      K k4,
      V v4,
      K k5,
      V v5,
      K k6,
      V v6,
      K k7,
      V v7,
      K k8,
      V v8,
      K k9,
      V v9) {
    return new ConcurrentHashMap<>(
        ImmutableMap.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9));
  }

  /**
   * Returns a new {@code ConcurrentHashMap} containing ten mappings. This method leverages {@code
   * java.util.Map.of} for initial validation.
   *
   * @param <K> the {@code ConcurrentHashMap}'s key type
   * @param <V> the {@code ConcurrentHashMap}'s value type
   * @param k1 the first mapping's key
   * @param v1 the first mapping's value
   * @param k2 the second mapping's key
   * @param v2 the second mapping's value
   * @param k3 the third mapping's key
   * @param v3 the third mapping's value
   * @param k4 the fourth mapping's key
   * @param v4 the fourth mapping's value
   * @param k5 the fifth mapping's key
   * @param v5 the fifth mapping's value
   * @param k6 the sixth mapping's key
   * @param v6 the sixth mapping's value
   * @param k7 the seventh mapping's key
   * @param v7 the seventh mapping's value
   * @param k8 the eighth mapping's key
   * @param v8 the eighth mapping's value
   * @param k9 the ninth mapping's key
   * @param v9 the ninth mapping's value
   * @param k10 the tenth mapping's key
   * @param v10 the tenth mapping's value
   * @return a {@code ConcurrentHashMap} containing the specified mappings
   */
  public static <K, V> ConcurrentHashMap<K, V> of(
      K k1,
      V v1,
      K k2,
      V v2,
      K k3,
      V v3,
      K k4,
      V v4,
      K k5,
      V v5,
      K k6,
      V v6,
      K k7,
      V v7,
      K k8,
      V v8,
      K k9,
      V v9,
      K k10,
      V v10) {
    return new ConcurrentHashMap<>(
        ImmutableMap.of(
            k1, v1, k2, v2, k3, v3, k4, v4, k5, v5, k6, v6, k7, v7, k8, v8, k9, v9, k10, v10));
  }
}
