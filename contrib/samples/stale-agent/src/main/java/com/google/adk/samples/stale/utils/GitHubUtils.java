package com.google.adk.samples.stale.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.adk.samples.stale.config.StaleBotSettings;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitHubUtils {

  private static final Logger logger = LoggerFactory.getLogger(GitHubUtils.class);
  private static final ObjectMapper mapper = new ObjectMapper();

  private static final AtomicInteger apiCallCount = new AtomicInteger(0);

  public static int getApiCallCount() {
    return apiCallCount.get();
  }

  public static void resetApiCallCount() {
    apiCallCount.set(0);
  }

  private static void incrementApiCallCount() {
    apiCallCount.incrementAndGet();
  }

  private static final CloseableHttpClient httpClient;

  static {
    DefaultHttpRequestRetryStrategy retryStrategy =
        new DefaultHttpRequestRetryStrategy(6, TimeValue.ofSeconds(2L));

    RequestConfig requestConfig =
        RequestConfig.custom()
            .setResponseTimeout(Timeout.of(60, TimeUnit.SECONDS))
            .setConnectTimeout(Timeout.of(60, TimeUnit.SECONDS))
            .build();

    httpClient =
        HttpClients.custom()
            .setRetryStrategy(retryStrategy)
            .setDefaultRequestConfig(requestConfig)
            .build();
  }

  public static JsonNode getRequest(String url, Map<String, Object> params) {
    incrementApiCallCount();
    try {
      String fullUrl = buildUrlWithParams(url, params);
      HttpGet request = new HttpGet(fullUrl);
      addCommonHeaders(request);

      try (CloseableHttpResponse response = httpClient.execute(request)) {
        checkStatus(response, url, "GET");
        return mapper.readTree(response.getEntity().getContent());
      }
    } catch (IOException e) {
      logger.error("GET request failed for {}: {}", url, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  public static JsonNode postRequest(String url, Object payload) {
    incrementApiCallCount();
    try {
      HttpPost request = new HttpPost(url);
      addCommonHeaders(request);
      request.setEntity(
          new StringEntity(mapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

      try (CloseableHttpResponse response = httpClient.execute(request)) {
        checkStatus(response, url, "POST");
        return mapper.readTree(response.getEntity().getContent());
      }
    } catch (IOException e) {
      logger.error("POST request failed for {}: {}", url, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  public static JsonNode patchRequest(String url, Object payload) {
    incrementApiCallCount();
    try {
      HttpPatch request = new HttpPatch(url);
      addCommonHeaders(request);
      request.setEntity(
          new StringEntity(mapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

      try (CloseableHttpResponse response = httpClient.execute(request)) {
        checkStatus(response, url, "PATCH");
        return mapper.readTree(response.getEntity().getContent());
      }
    } catch (IOException e) {
      logger.error("PATCH request failed for {}: {}", url, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  public static JsonNode deleteRequest(String url) {
    incrementApiCallCount();
    try {
      HttpDelete request = new HttpDelete(url);
      addCommonHeaders(request);

      try (CloseableHttpResponse response = httpClient.execute(request)) {
        if (response.getCode() == 204) {
          ObjectNode success = mapper.createObjectNode();
          success.put("status", "success");
          success.put("message", "Deletion successful.");
          return success;
        }
        checkStatus(response, url, "DELETE");
        return mapper.readTree(response.getEntity().getContent());
      }
    } catch (IOException e) {
      logger.error("DELETE request failed for {}: {}", url, e.getMessage());
      throw new RuntimeException(e);
    }
  }

  public static Map<String, Object> errorResponse(String errorMessage) {
    return Map.of("status", "error", "message", errorMessage);
  }

  public static List<Integer> getOldOpenIssueNumbers(String owner, String repo, Double daysOld) {
    if (daysOld == null) {
      daysOld = StaleBotSettings.STALE_HOURS_THRESHOLD / 24.0;
    }

    Instant nowUtc = Instant.now();
    Instant cutoffDt = nowUtc.minus((long) (daysOld * 24 * 60), ChronoUnit.MINUTES);

    DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneId.of("UTC"));
    String cutoffStr = formatter.format(cutoffDt);

    String query =
        String.format("repo:%s/%s is:issue state:open created:<%s", owner, repo, cutoffStr);

    logger.info("Searching for issues in '{}/{}' created before {}...", owner, repo, cutoffStr);

    List<Integer> issueNumbers = new ArrayList<>();
    int page = 1;
    String searchUrl = "https://api.github.com/search/issues";

    while (true) {
      try {
        Map<String, Object> params = new HashMap<>();
        params.put("q", query);
        params.put("per_page", 100);
        params.put("page", page);

        JsonNode data = getRequest(searchUrl, params);
        JsonNode items = data.get("items");

        if (items == null || items.isEmpty()) {
          break;
        }

        if (items.isArray()) {
          for (JsonNode item : items) {
            if (!item.has("pull_request")) {
              issueNumbers.add(item.get("number").asInt());
            }
          }
        }

        if (items.size() < 100) {
          break;
        }

        page++;

      } catch (Exception e) {
        logger.error("GitHub search failed on page {}: {}", page, e.getMessage());
        break;
      }
    }

    logger.info("Found {} stale issues.", issueNumbers.size());
    return issueNumbers;
  }

  private static void addCommonHeaders(HttpUriRequestBase request) {
    request.addHeader("Authorization", "token " + StaleBotSettings.GITHUB_TOKEN);
    request.addHeader("Accept", "application/vnd.github.v3+json");
  }

  private static void checkStatus(CloseableHttpResponse response, String url, String method)
      throws IOException {
    int code = response.getCode();
    if (code >= 400) {
      throw new IOException(
          String.format("%s request to %s failed with status %d", method, url, code));
    }
  }

  private static String buildUrlWithParams(String url, Map<String, Object> params) {
    if (params == null || params.isEmpty()) {
      return url;
    }
    StringBuilder sb = new StringBuilder(url);
    sb.append("?");
    for (Map.Entry<String, Object> entry : params.entrySet()) {
      if (sb.charAt(sb.length() - 1) != '?') {
        sb.append("&");
      }
      sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
      sb.append("=");
      sb.append(URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8));
    }
    return sb.toString();
  }
}
