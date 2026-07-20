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

package com.google.adk.models.sarvamai;

import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live bidirectional connection to Sarvam AI, implementing multi-turn streaming conversations.
 *
 * <p>Maintains conversation history and streams responses token-by-token using SSE. Accumulates the
 * full model response into history after each turn to support multi-turn context.
 *
 * @author Sandeep Belgavi
 */
final class SarvamAiLlmConnection implements BaseLlmConnection {

  private static final Logger logger = LoggerFactory.getLogger(SarvamAiLlmConnection.class);

  private final SarvamAi sarvamAi;
  private final LlmRequest initialRequest;
  private final List<Content> history;
  private final PublishSubject<LlmResponse> responseSubject = PublishSubject.create();

  SarvamAiLlmConnection(SarvamAi sarvamAi, LlmRequest llmRequest) {
    this.sarvamAi = sarvamAi;
    this.initialRequest = llmRequest;
    this.history = new ArrayList<>(llmRequest.contents());
  }

  @Override
  public Completable sendHistory(List<Content> newHistory) {
    return Completable.fromAction(
            () -> {
              synchronized (history) {
                history.clear();
                history.addAll(newHistory);
              }
              generateAndStream();
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Completable sendContent(Content content) {
    return Completable.fromAction(
            () -> {
              synchronized (history) {
                history.add(content);
              }
              generateAndStream();
            })
        .subscribeOn(Schedulers.io());
  }

  @Override
  public Completable sendRealtime(Blob blob) {
    return Completable.error(
        new UnsupportedOperationException(
            "Realtime audio/video blobs are not supported on the chat connection. "
                + "Use SarvamSttService for STT and SarvamTtsService for TTS."));
  }

  @Override
  public Flowable<LlmResponse> receive() {
    return responseSubject.toFlowable(BackpressureStrategy.BUFFER);
  }

  @Override
  public void close() {
    responseSubject.onComplete();
  }

  @Override
  public void close(Throwable throwable) {
    responseSubject.onError(throwable);
  }

  private void generateAndStream() {
    List<Content> snapshot;
    synchronized (history) {
      snapshot = new ArrayList<>(history);
    }

    LlmRequest.Builder turnBuilder =
        LlmRequest.builder()
            .contents(snapshot)
            .appendTools(new ArrayList<>(initialRequest.tools().values()));

    initialRequest.config().ifPresent(turnBuilder::config);
    turnBuilder.appendInstructions(initialRequest.getSystemInstructions());

    LlmRequest turnRequest = turnBuilder.build();

    StringBuilder fullText = new StringBuilder();

    sarvamAi
        .generateContent(turnRequest, true)
        .subscribe(
            response -> {
              responseSubject.onNext(response);
              response
                  .content()
                  .flatMap(Content::parts)
                  .ifPresent(
                      parts -> {
                        for (Part part : parts) {
                          part.text().ifPresent(fullText::append);
                        }
                      });
            },
            error -> {
              logger.error("Error during Sarvam streaming turn", error);
              responseSubject.onError(error);
            },
            () -> {
              if (fullText.length() > 0) {
                Content responseContent =
                    Content.builder()
                        .role("model")
                        .parts(Part.fromText(fullText.toString()))
                        .build();
                synchronized (history) {
                  history.add(responseContent);
                }
              }
            });
  }
}
