/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.sessions;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.HttpOptions;
import java.io.IOException;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;

/** Base client for the HTTP APIs. */
public class HttpApiClient extends ApiClient {
  public static final MediaType MEDIA_TYPE_APPLICATION_JSON =
      MediaType.parse("application/json; charset=utf-8");

  /** Constructs an ApiClient for Google AI APIs. */
  HttpApiClient(@Nullable String apiKey, @Nullable HttpOptions httpOptions) {
    super(apiKey, httpOptions);
  }

  /** Constructs an ApiClient for Vertex AI APIs. */
  HttpApiClient(
      @Nullable String project,
      @Nullable String location,
      @Nullable GoogleCredentials credentials,
      @Nullable HttpOptions httpOptions) {
    super(project, location, credentials, httpOptions);
  }

  /** Sends a Http request given the http method, path, and request json string. */
  @Override
  public ApiResponse request(String httpMethod, String path, String requestJson) {
    boolean queryBaseModel =
        Ascii.equalsIgnoreCase(httpMethod, "GET") && path.startsWith("publishers/google/models/");
    if (this.vertexAI() && !path.startsWith("projects/") && !queryBaseModel) {
      path = String.format("projects/%s/locations/%s/", this.project, this.location) + path;
    }
    String requestUrl =
        String.format(
            "%s/%s/%s", httpOptions.baseUrl().get(), httpOptions.apiVersion().get(), path);

    Request.Builder requestBuilder = new Request.Builder().url(requestUrl);
    setHeaders(requestBuilder);

    if (Ascii.equalsIgnoreCase(httpMethod, "POST")) {
      requestBuilder.post(RequestBody.create(MEDIA_TYPE_APPLICATION_JSON, requestJson));

    } else if (Ascii.equalsIgnoreCase(httpMethod, "GET")) {
      requestBuilder.get();
    } else if (Ascii.equalsIgnoreCase(httpMethod, "DELETE")) {
      requestBuilder.delete();
    } else {
      throw new IllegalArgumentException("Unsupported HTTP method: " + httpMethod);
    }
    return executeRequest(requestBuilder.build());
  }

  /** Sets the required headers (including auth) on the request object. */
  private void setHeaders(Request.Builder requestBuilder) {
    for (Map.Entry<String, String> header :
        httpOptions.headers().orElse(ImmutableMap.of()).entrySet()) {
      requestBuilder.header(header.getKey(), header.getValue());
    }

    if (apiKey != null) {
      requestBuilder.header("x-goog-api-key", apiKey);
    } else {
      Preconditions.checkState(credentials != null, "credentials is required");
      GoogleCredentials cred = credentials;
      try {
        cred.refreshIfExpired();
      } catch (IOException e) {
        throw new GenAiIOException("Failed to refresh credentials.", e);
      }
      String accessToken;
      try {
        accessToken = cred.getAccessToken().getTokenValue();
      } catch (NullPointerException e) {
        // For test cases where the access token is not available.
        if (e.getMessage()
            .contains(
                "because the return value of"
                    + " \"com.google.auth.oauth2.GoogleCredentials.getAccessToken()\" is null")) {
          accessToken = "";
        } else {
          throw e;
        }
      }
      requestBuilder.header("Authorization", "Bearer " + accessToken);

      if (cred.getQuotaProjectId() != null) {
        requestBuilder.header("x-goog-user-project", cred.getQuotaProjectId());
      }
    }
  }

  /** Executes the given HTTP request. */
  private ApiResponse executeRequest(Request request) {
    try {
      Response response = httpClient.newCall(request).execute();
      return new HttpApiResponse(response);
    } catch (IOException e) {
      throw new GenAiIOException("Failed to execute HTTP request.", e);
    }
  }
}
