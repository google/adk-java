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
package com.google.adk.a2a.converters;

import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;

/**
 * Converts one A2A part received from a caller or a remote agent into GenAI parts.
 *
 * <p>Returning an empty list drops the part. {@link PartConverter#defaultConverter(FileUriPolicy)}
 * returns the converter ADK uses, which a custom converter can delegate to.
 */
@FunctionalInterface
public interface A2APartConverter {
  ImmutableList<Part> convert(io.a2a.spec.Part<?> a2aPart);
}
