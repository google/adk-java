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

package com.google.adk.tokt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The dispatcher every crossing in this module hops to.
 *
 * ADK Java's SPI is RxJava, which is synchronous unless the implementation says otherwise, so a
 * user-authored Java tool, plugin or service may block. Running it on the engine's dispatcher would
 * stall the coroutine driving the agent loop, so each adapter moves the call here.
 */
internal val InteropDispatcher: CoroutineDispatcher = Dispatchers.IO
