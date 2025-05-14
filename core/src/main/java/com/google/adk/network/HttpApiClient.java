package com.google.adk.network;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableMap;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.HttpOptions;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Base client for the HTTP APIs using OkHttp. */
public class HttpApiClient extends ApiClient {

  private static final MediaType JSON_MEDIA_TYPE =
      MediaType.parse("application/json; charset=utf-8");

  /** Constructs an ApiClient for Google AI APIs. */
  HttpApiClient(Optional<String> apiKey, Optional<HttpOptions> httpOptions) {
    super(apiKey, httpOptions);
  }

  /** Constructs an ApiClient for Vertex AI APIs. */
  HttpApiClient(
      Optional<String> project,
      Optional<String> location,
      Optional<GoogleCredentials> credentials,
      Optional<HttpOptions> httpOptions) {
    super(project, location, credentials, httpOptions);
  }

  /** Sends a Http request given the http method, path, and request json string. */
  @Override
  public ApiResponse request(String httpMethod, String path, String requestJson) {
    boolean queryBaseModel =
        httpMethod.equalsIgnoreCase("GET") && path.startsWith("publishers/google/models/");
    String effectivePath = path;
    if (this.vertexAI() && !effectivePath.startsWith("projects/") && !queryBaseModel) {
      effectivePath =
          String.format(
              "projects/%s/locations/%s/%s", this.project.get(), this.location.get(), path);
    }
    String requestUrl =
        String.format(
            "%s/%s/%s", httpOptions.baseUrl().get(), httpOptions.apiVersion().get(), effectivePath);

    Request.Builder requestBuilder = new Request.Builder().url(requestUrl);
    setHeaders(requestBuilder);

    if (httpMethod.equalsIgnoreCase("POST")) {
      RequestBody body = RequestBody.create(requestJson, JSON_MEDIA_TYPE);
      requestBuilder.post(body);
    } else if (httpMethod.equalsIgnoreCase("GET")) {
      requestBuilder.get();
    } else if (httpMethod.equalsIgnoreCase("DELETE")) {
      if (requestJson != null && !requestJson.isEmpty()) {
        RequestBody body = RequestBody.create(requestJson, JSON_MEDIA_TYPE);
        requestBuilder.delete(body);
      } else {
        requestBuilder.delete();
      }
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

    if (apiKey.isPresent()) {
      requestBuilder.header("x-goog-api-key", apiKey.get());
    } else {
      GoogleCredentials cred =
          credentials.orElseThrow(() -> new IllegalStateException("Credentials are required."));
      try {
        cred.refreshIfExpired();
      } catch (IOException e) {
        throw new GenAiIOException("Failed to refresh credentials.", e);
      }
      String accessToken;
      try {
        if (cred.getAccessToken() == null) {
          accessToken = "";
        } else {
          accessToken = cred.getAccessToken().getTokenValue();
          if (accessToken == null) {
            accessToken = "";
          }
        }
      } catch (NullPointerException e) {
        if (e.getMessage() != null
            && e.getMessage()
                .contains(
                    "because the return value of"
                        + " \"com.google.auth.oauth2.GoogleCredentials.getAccessToken()\" is"
                        + " null")) {
          accessToken = "";
        } else {
          // For other unexpected NullPointerExceptions, rethrow
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
      Response okHttpResponse =
          httpClient.newCall(request).execute(); // httpClient is from ApiClient
      return new HttpApiResponse(okHttpResponse);
    } catch (IOException e) {
      throw new GenAiIOException("Failed to execute HTTP request.", e);
    }
  }
}
