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

package com.google.adk.web.controller;

import com.google.adk.agents.RunConfig;
import com.google.adk.agents.RunConfig.StreamingMode;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.web.dto.AgentRunRequest;
import com.google.adk.web.service.RunnerService;
import com.google.common.collect.Lists;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Controller handling agent execution endpoints. */
@RestController
public class ExecutionController {

  private static final Logger log = LoggerFactory.getLogger(ExecutionController.class);

  private final RunnerService runnerService;
  private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

  @Autowired
  public ExecutionController(RunnerService runnerService) {
    this.runnerService = runnerService;
  }

  /**
   * Executes a non-streaming agent run for a given session and message.
   *
   * @param request The AgentRunRequest containing run details.
   * @return A list of events generated during the run.
   * @throws ResponseStatusException if the session is not found or the run fails.
   */
  @PostMapping("/run")
  public List<Event> agentRun(@RequestBody AgentRunRequest request) {
    if (request.appName == null || request.appName.trim().isEmpty()) {
      log.warn("appName cannot be null or empty in POST /run request.");
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appName cannot be null or empty");
    }
    if (request.sessionId == null || request.sessionId.trim().isEmpty()) {
      log.warn("sessionId cannot be null or empty in POST /run request.");
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sessionId cannot be null or empty");
    }
    log.info("Request received for POST /run for session: {}", request.sessionId);

    Runner runner = this.runnerService.getRunner(request.appName);
    try {

      RunConfig runConfig = RunConfig.builder().setStreamingMode(StreamingMode.NONE).build();
      Flowable<Event> eventStream =
          runner.runAsync(
              request.userId, request.sessionId, request.newMessage, runConfig, request.stateDelta);

      List<Event> events = Lists.newArrayList(eventStream.blockingIterable());
      log.info("Agent run for session {} generated {} events.", request.sessionId, events.size());
      return events;
    } catch (Exception e) {
      log.error("Error during agent run for session {}", request.sessionId, e);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent run failed", e);
    }
  }

  /**
   * Executes an agent run and streams the resulting events using Server-Sent Events (SSE).
   *
   * @param request The AgentRunRequest containing run details.
   * @return A Flux that will stream events to the client.
   */
  @PostMapping(value = "/run_sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter agentRunSse(@RequestBody AgentRunRequest request) {
    SseEmitter emitter = new SseEmitter(60 * 60 * 1000L); // 1 hour timeout

    if (request.appName == null || request.appName.trim().isEmpty()) {
      log.warn(
          "appName cannot be null or empty in SseEmitter request for appName: {}, session: {}",
          request.appName,
          request.sessionId);
      emitter.completeWithError(
          new ResponseStatusException(HttpStatus.BAD_REQUEST, "appName cannot be null or empty"));
      return emitter;
    }
    if (request.sessionId == null || request.sessionId.trim().isEmpty()) {
      log.warn(
          "sessionId cannot be null or empty in SseEmitter request for appName: {}, session: {}",
          request.appName,
          request.sessionId);
      emitter.completeWithError(
          new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId cannot be null or empty"));
      return emitter;
    }

    log.info(
        "SseEmitter Request received for POST /run_sse_emitter for session: {}", request.sessionId);

    final String sessionId = request.sessionId;
    sseExecutor.execute(
        () -> {
          Runner runner;
          try {
            runner = this.runnerService.getRunner(request.appName);
          } catch (ResponseStatusException e) {
            log.warn(
                "Setup failed for SseEmitter request for session {}: {}",
                sessionId,
                e.getMessage());
            try {
              emitter.completeWithError(e);
            } catch (Exception ex) {
              log.warn(
                  "Error completing emitter after setup failure for session {}: {}",
                  sessionId,
                  ex.getMessage());
            }
            return;
          }

          final RunConfig runConfig =
              RunConfig.builder()
                  .setStreamingMode(request.getStreaming() ? StreamingMode.SSE : StreamingMode.NONE)
                  .build();

          Flowable<Event> eventFlowable =
              runner.runAsync(
                  request.userId,
                  request.sessionId,
                  request.newMessage,
                  runConfig,
                  request.stateDelta);

          Disposable disposable =
              eventFlowable
                  .observeOn(Schedulers.io())
                  .subscribe(
                      event -> {
                        try {
                          log.debug(
                              "SseEmitter: Sending event {} for session {}", event.id(), sessionId);
                          emitter.send(SseEmitter.event().data(event.toJson()));
                        } catch (IOException e) {
                          log.error(
                              "SseEmitter: IOException sending event for session {}: {}",
                              sessionId,
                              e.getMessage());
                          throw new RuntimeException("Failed to send event", e);
                        } catch (Exception e) {
                          log.error(
                              "SseEmitter: Unexpected error sending event for session {}: {}",
                              sessionId,
                              e.getMessage(),
                              e);
                          throw new RuntimeException("Unexpected error sending event", e);
                        }
                      },
                      error -> {
                        log.error(
                            "SseEmitter: Stream error for session {}: {}",
                            sessionId,
                            error.getMessage(),
                            error);
                        try {
                          emitter.completeWithError(error);
                        } catch (Exception ex) {
                          log.warn(
                              "Error completing emitter after stream error for session {}: {}",
                              sessionId,
                              ex.getMessage());
                        }
                      },
                      () -> {
                        log.debug(
                            "SseEmitter: Stream completed normally for session: {}", sessionId);
                        try {
                          emitter.complete();
                        } catch (Exception ex) {
                          log.warn(
                              "Error completing emitter after normal completion for session {}:"
                                  + " {}",
                              sessionId,
                              ex.getMessage());
                        }
                      });
          emitter.onCompletion(
              () -> {
                log.debug(
                    "SseEmitter: onCompletion callback for session: {}. Disposing subscription.",
                    sessionId);
                if (!disposable.isDisposed()) {
                  disposable.dispose();
                }
              });
          emitter.onTimeout(
              () -> {
                log.debug(
                    "SseEmitter: onTimeout callback for session: {}. Disposing subscription and"
                        + " completing.",
                    sessionId);
                if (!disposable.isDisposed()) {
                  disposable.dispose();
                }
                emitter.complete();
              });
        });

    log.debug("SseEmitter: Returning emitter for session: {}", sessionId);
    return emitter;
  }
}
