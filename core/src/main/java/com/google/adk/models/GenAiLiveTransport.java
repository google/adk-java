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

import com.google.genai.AsyncSession;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveSendToolResponseParameters;
import com.google.genai.types.LiveServerMessage;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** The default {@link GeminiLiveTransport}, delegating to a genai {@link AsyncSession}. */
final class GenAiLiveTransport implements GeminiLiveTransport {

  private final AsyncSession session;

  GenAiLiveTransport(AsyncSession session) {
    this.session = Objects.requireNonNull(session);
  }

  @Override
  public CompletableFuture<Void> sendClientContent(LiveSendClientContentParameters params) {
    return session.sendClientContent(params);
  }

  @Override
  public CompletableFuture<Void> sendRealtimeInput(LiveSendRealtimeInputParameters params) {
    return session.sendRealtimeInput(params);
  }

  @Override
  public CompletableFuture<Void> sendToolResponse(LiveSendToolResponseParameters params) {
    return session.sendToolResponse(params);
  }

  @Override
  public CompletableFuture<Void> receive(
      Consumer<LiveServerMessage> onMessage, Runnable onStreamEnd) {
    // The genai session has no end-of-stream signal, so onStreamEnd never fires here.
    return session.receive(onMessage);
  }

  @Override
  public CompletableFuture<Void> close() {
    return session.close();
  }
}
