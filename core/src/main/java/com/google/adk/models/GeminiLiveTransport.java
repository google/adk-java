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

package com.google.adk.models;

import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerMessage;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The bidirectional live transport that {@link GeminiLlmConnection} drives.
 *
 * <p>{@link GeminiLlmConnection} holds the translation logic; this is the transport beneath it, so
 * the connection can drive any implementation. The default one delegates to a genai live session.
 */
public interface GeminiLiveTransport {

  /** Sends a client-content turn to the transport. */
  CompletableFuture<Void> sendClientContent(LiveSendClientContentParameters params);

  /** Sends realtime input (audio, video, or text) to the transport. */
  CompletableFuture<Void> sendRealtimeInput(LiveSendRealtimeInputParameters params);

  /** Sends a tool response to the transport. */
  CompletableFuture<Void> sendToolResponse(LiveSendToolResponseParameters params);

  /**
   * Registers the callback for messages the transport yields and a callback for when its receive
   * stream ends. An implementation whose stream ends only when the client closes never invokes
   * {@code onStreamEnd}; one backed by a finite script invokes it when the script is exhausted, so
   * the run can end.
   */
  CompletableFuture<Void> receive(Consumer<LiveServerMessage> onMessage, Runnable onStreamEnd);

  /** Closes the transport. */
  CompletableFuture<Void> close();
}
