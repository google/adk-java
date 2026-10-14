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

/**
 * Seams for the system operations behind ADK-generated timestamps and ids.
 *
 * <p>An invocation reads the time from a {@link java.time.InstantSource} and mints identifiers from
 * a {@link com.google.adk.platform.UuidProvider}, both configured once on the runner and carried on
 * every {@code InvocationContext} it creates rather than held in static state, so concurrent
 * invocations may use independent providers. Either may be called from several RxJava worker
 * threads at once.
 */
package com.google.adk.platform;
