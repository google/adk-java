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

/**
 * @author Sandeep Belgavi
 * @since 2025-10-21
 */
package com.google.adk.memory;

import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.Arrays;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Generates vector embeddings from text using the Redbus embedding service.
 *
 * <p>The URL for the embedding service can be configured via the `EMBEDDING_GENERATOR_URL`
 * environment variable.
 *
 * <p>Example: `export EMBEDDING_GENERATOR_URL="http://www.redbus.com/<serviceprovider>/embeddings"`
 */
public class RedbusEmbeddingService implements EmbeddingService {
  public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  static OkHttpClient client = new OkHttpClient();

  private final String username;
  private final String password;
  private final int api;
  private final String embeddingUrl;

  public RedbusEmbeddingService(String username, String password) {
    this(username, password, 1);
  }

  public RedbusEmbeddingService(String username, String password, int api) {
    this.username = username;
    this.password = password;
    this.api = api;
    this.embeddingUrl =
        System.getenv("EMBEDDING_GENERATOR_URL") != null
            ? System.getenv("EMBEDDING_GENERATOR_URL")
            : "<default embedding url>";
  }

  @Override
  public Single<double[]> generateEmbedding(String text) {
    return Single.fromCallable(
        () -> {
          JSONObject requestBody = new JSONObject();
          requestBody.put("username", this.username);
          JSONObject requestObject = new JSONObject();
          requestObject.put("input", text);
          requestObject.put("model", "text-embedding-3-small");
          requestBody.put("request", requestObject);
          requestBody.put("password", this.password);
          requestBody.put("api", this.api);

          RequestBody body = RequestBody.create(requestBody.toString(), JSON);
          Request request = new Request.Builder().url(this.embeddingUrl).post(body).build();
          try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
              throw new IOException("Unexpected code " + response);
            }
            String responseBody = response.body().string();
            JSONObject jsonResponse = new JSONObject(responseBody);
            JSONArray data =
                jsonResponse
                    .getJSONObject("response")
                    .getJSONObject("openAIResponse")
                    .getJSONArray("data");
            JSONObject firstDataItem = data.getJSONObject(0);
            JSONArray embedding = firstDataItem.getJSONArray("embedding");
            double[] embeddingArray = new double[embedding.length()];
            for (int i = 0; i < embedding.length(); i++) {
              embeddingArray[i] = embedding.getDouble(i);
            }
            System.out.println("Embedding dimension: " + embeddingArray.length);
            return embeddingArray;
          }
        });
  }

  public static void main(String[] args) {
    RedbusEmbeddingService service = new RedbusEmbeddingService("", "");
    service
        .generateEmbedding("testing")
        .subscribe(
            embedding -> System.out.println(Arrays.toString(embedding)),
            error -> System.err.println("Error generating embedding: " + error.getMessage()));
  }
}
