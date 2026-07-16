/*
 * Copyright 2024 Google LLC
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
package com.google.adk.memory;

import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores the memory in a remote API.
 *
 * <p>Example of usage:
 *
 * <pre>{@code
 * APIMemoryService.setApiUrl("http://localhost:8080/memory");
 * MemoryService memory = new APIMemoryService();
 * memory.put("key", "value");
 * String value = memory.get("key");
 * }</pre>
 */
public class APIMemoryService extends MemoryService {
  private static final Logger logger = LoggerFactory.getLogger(APIMemoryService.class);
  private static String apiUrl = "http://localhost:8080/memory"; // Default URL
  private final HttpClient client = HttpClient.newHttpClient();

  public static void setApiUrl(String url) {
    apiUrl = url;
  }

  @Override
  public void put(String key, String value) {
    JSONObject json = new JSONObject(ImmutableMap.of("key", key, "value", value));
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
            .build();
    try {
      client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException | InterruptedException e) {
      logger.error("Error while putting data in memory", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public String get(String key) {
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(apiUrl + "/" + key)).GET().build();
    try {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        JSONObject json = new JSONObject(response.body());
        return json.getString("value");
      }
    } catch (IOException | InterruptedException e) {
      logger.error("Error while getting data from memory", e);
      throw new RuntimeException(e);
    }
    return null;
  }

  @Override
  public void remove(String key) {
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(apiUrl + "/" + key)).DELETE().build();
    try {
      client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException | InterruptedException e) {
      logger.error("Error while removing data from memory", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void add(String key, String value) {
    JSONObject json = new JSONObject(ImmutableMap.of("key", key, "value", value));
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/add"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
            .build();
    try {
      client.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException | InterruptedException e) {
      logger.error("Error while adding data in memory", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public void newSession(Session session) {
    // Not implemented for API based memory
  }

  @Override
  public Map<String, String> getAll() {
    // Not implemented for API based memory
    return null;
  }

  @Override
  public List<String> getList(String key) {
    // Not implemented for API based memory
    return null;
  }
}
