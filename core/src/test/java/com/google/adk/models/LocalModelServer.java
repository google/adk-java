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
package com.google.adk.models;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.net.InetAddresses;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** A local HTTP server that captures the headers a model sends with its client as built. */
final class LocalModelServer {

  static final String GENERATE_CONTENT_JSON =
      "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hi\"}]},"
          + "\"finishReason\":\"STOP\"}]}";

  static final String CHAT_COMPLETION_JSON =
      "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
          + "\"finish_reason\":\"stop\"}]}";

  /**
   * Sends one request through the model that {@code modelFor} returns for the server's URL, and
   * returns the headers the server received. Genai clients built meanwhile default to the server
   * too, for both the Gemini API and Vertex AI.
   */
  static Headers headersSentToLocalServer(Function<String, BaseLlm> modelFor, String responseJson)
      throws IOException {
    AtomicReference<Headers> received = new AtomicReference<>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          received.set(exchange.getRequestHeaders());
          byte[] body = responseJson.getBytes(UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    String url =
        "http://"
            + InetAddresses.toUriString(server.getAddress().getAddress())
            + ":"
            + server.getAddress().getPort();
    Client.setDefaultBaseUrls(Optional.of(url), Optional.of(url));
    try {
      LlmRequest request =
          LlmRequest.builder()
              .contents(
                  ImmutableList.of(
                      Content.builder().role("user").parts(Part.fromText("hello")).build()))
              .build();
      modelFor.apply(url).generateContent(request, /* stream= */ false).blockingSubscribe();
      return received.get();
    } finally {
      Client.setDefaultBaseUrls(Optional.empty(), Optional.empty());
      server.stop(0);
    }
  }

  private LocalModelServer() {}
}
