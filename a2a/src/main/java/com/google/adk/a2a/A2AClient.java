package com.google.adk.a2a;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.base.Preconditions;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;
import io.a2a.client.http.JdkA2AHttpClient;
import io.a2a.spec.AgentCard;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A thin HTTP client for interacting with an A2A-compliant agent endpoint. */
public final class A2AClient {

  private static final Logger logger = LoggerFactory.getLogger(A2AClient.class);
  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String DEFAULT_SEND_PATH = "/v1/message:send";

  private final AgentCard agentCard;
  private final A2AHttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Map<String, String> defaultHeaders;

  public A2AClient(AgentCard agentCard) {
    this(agentCard, new JdkA2AHttpClient(), Map.of());
  }

  public A2AClient(
      AgentCard agentCard, A2AHttpClient httpClient, Map<String, String> defaultHeaders) {
    this.agentCard = Preconditions.checkNotNull(agentCard, "agentCard");
    this.httpClient = Preconditions.checkNotNull(httpClient, "httpClient");
    this.objectMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .build();
    this.defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
  }

  public AgentCard getAgentCard() {
    return agentCard;
  }

  public String getUrl() {
    return agentCard.url();
  }

  /**
   * Sends a JSON-RPC message to the remote A2A agent and converts the response into the canonical
   * {@link SendMessageResponse} model.
   */
  public Flowable<SendMessageResponse> sendMessage(SendMessageRequest request) {
    return Flowable.fromCallable(() -> executeSendMessage(request));
  }

  private SendMessageResponse executeSendMessage(SendMessageRequest request) throws IOException {
    Preconditions.checkNotNull(request, "request");
    String payload = serializeRequest(request);
    String endpoint = resolveSendMessageEndpoint(agentCard.url());

    A2AHttpClient.PostBuilder builder =
        httpClient.createPost().url(endpoint).addHeader("Content-Type", JSON_CONTENT_TYPE);
    defaultHeaders.forEach(builder::addHeader);
    builder.body(payload);

    A2AHttpResponse response;
    try {
      response = builder.post();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while sending A2A sendMessage request", e);
    }

    if (!response.success()) {
      String responseBody = response.body();
      logger.warn(
          "A2A sendMessage request failed with status {} and body {}",
          response.status(),
          responseBody);
      throw new IOException("A2A sendMessage request failed with HTTP status " + response.status());
    }

    return deserializeResponse(response.body());
  }

  private String serializeRequest(SendMessageRequest request) throws JsonProcessingException {
    return objectMapper.writeValueAsString(request);
  }

  private SendMessageResponse deserializeResponse(String body) throws JsonProcessingException {
    return objectMapper.readValue(body, SendMessageResponse.class);
  }

  private static String resolveSendMessageEndpoint(String baseUrl) {
    if (baseUrl == null || baseUrl.isEmpty()) {
      throw new IllegalArgumentException("Agent card URL cannot be null or empty");
    }
    if (baseUrl.endsWith("/")) {
      return baseUrl.substring(0, baseUrl.length() - 1) + DEFAULT_SEND_PATH;
    }
    return baseUrl + DEFAULT_SEND_PATH;
  }

  public static @Nullable String extractHostAndPort(String urlString) {
    try {
      URL url = URI.create(urlString).toURL();
      String host = url.getHost();
      int port = url.getPort();
      if (port != -1) {
        return host + ":" + port;
      }
      return host;
    } catch (MalformedURLException | IllegalArgumentException e) {
      logger.warn("Invalid URL when extracting host and port", e);
      return null;
    }
  }
}
