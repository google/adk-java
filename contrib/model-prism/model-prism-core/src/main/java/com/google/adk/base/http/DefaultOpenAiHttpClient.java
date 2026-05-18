package com.google.adk.base.http;

import static com.google.api.client.util.Preconditions.checkNotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** {@link OpenAiHttpClient} implementation backed by {@code java.net.http.HttpClient}. */
public class DefaultOpenAiHttpClient implements OpenAiHttpClient {

  public DefaultOpenAiHttpClient(String apiUrl, Optional<String> apiKey) {
    this.http = HttpClient.newHttpClient();
    this.apiUrl = checkNotNull(apiUrl, "apiUrl");
    this.apiKey = checkNotNull(apiKey, "apiKey");
  }

  private final HttpClient http;
  private final String apiUrl;
  private final Optional<String> apiKey;

  @Override
  public String get(String url) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder() //
            .uri(URI.create(url)) //
            .header("Accept", "application/json") //
            .GET() //
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (!success(response)) {
      String msg = "HTTP [%s] -> %s";
      throw new RuntimeException(msg.formatted(response.statusCode(), response.body()));
    }
    return response.body();
  }

  @Override
  public String post(String requestBody) throws Exception {
    HttpRequest request = request(requestBody);
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (!success(response)) {
      String msg = "HTTP [%s] -> %s";
      throw new RuntimeException(msg.formatted(response.statusCode(), response.body()));
    }
    return response.body();
  }

  @Override
  public Stream<String> postStream(String requestBody) throws Exception {
    HttpRequest request = request(requestBody);
    HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
    if (!success(response)) {
      String msg = "HTTP [%s] -> %s";
      String error = response.body().collect(Collectors.joining());
      throw new RuntimeException(msg.formatted(response.statusCode(), error));
    }
    return response.body();
  }

  private HttpRequest request(String body) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder() //
            .uri(URI.create(apiUrl)) //
            .header("Content-Type", "application/json") //
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (apiKey.isPresent()) {
      builder.header("Authorization", "Bearer " + apiKey.get());
    }
    return builder.build();
  }

  private boolean success(HttpResponse<?> response) {
    return response.statusCode() >= 200 && response.statusCode() < 300;
  }
}
