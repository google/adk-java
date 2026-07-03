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

import com.google.common.collect.Iterables;

/** Frequently used code snippets for collections. */
public final class CollectionUtils {

  /**
   * Checks if the given iterable is null or empty.
   *
   * @param iterable the iterable to check
   * @return true if the iterable is null or empty, false otherwise
   */
  public static <T> boolean isNullOrEmpty(Iterable<T> iterable) {
    return iterable == null || Iterables.isEmpty(iterable);
  }

  private CollectionUtils() {}
}
