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
 * Seams for overriding system operations such as reading the current time and generating unique
 * IDs.
 *
 * <p>By default ADK uses the wall clock and random UUIDs. Integrations that need customized
 * timestamps and identifiers can install custom {@link com.google.adk.platform.TimeProvider} and
 * {@link com.google.adk.platform.UuidProvider} implementations. Providers are carried on an
 * invocation rather than in static state, which keeps them isolated to a single run and safe for
 * concurrent invocations with independent providers.
 */
package com.google.adk.platform;
