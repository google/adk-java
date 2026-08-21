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

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.util.List;

/** The base class for a live model connection. */
public interface BaseLlmConnection {

  /**
   * Sends the conversation history to the model.
   *
   * <p>You call this method right after setting up the model connection. The model will respond if
   * the last content is from user, otherwise it will wait for new user input before responding.
   */
  Completable sendHistory(List<Content> history);

  /**
   * Sends a user content to the model.
   *
   * <p>The model will respond immediately upon receiving the content. If you send function
   * responses, all parts in the content should be function responses.
   */
  Completable sendContent(Content content);

  /**
   * Sends a chunk of audio or a frame of video to the model in realtime.
   *
   * <p>The model may not respond immediately upon receiving the blob. It will do voice activity
   * detection and decide when to respond.
   */
  Completable sendRealtime(Blob blob);

  /** Receives the model responses. */
  Flowable<LlmResponse> receive();

  /** Closes the connection. */
  void close();

  /** Closes the connection with an error. */
  void close(Throwable throwable);
}
