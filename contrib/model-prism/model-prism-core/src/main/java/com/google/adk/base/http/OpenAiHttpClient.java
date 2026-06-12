package com.google.adk.base.http;

import java.util.stream.Stream;

/**
 * Transport contract for the OpenAI chat-completions wire format.
 *
 * <p>Isolates HTTP mechanism from serialization so that either side can be swapped or tested
 * independently (e.g. inject a mock in unit tests).
 */
public interface OpenAiHttpClient {

  /**
   * Sends a blocking GET to the given URL and returns the full response body.
   *
   * @throws RuntimeException if the server returns a non-2xx status
   */
  String get(String url) throws Exception;

  /**
   * Sends a blocking POST with the given JSON body and returns the full response body.
   *
   * @throws RuntimeException if the server returns a non-2xx status
   */
  String post(String requestBody) throws Exception;

  /**
   * Sends a POST and returns the SSE response as a lazy line {@link Stream}.
   *
   * <p>Callers are responsible for closing the stream (use try-with-resources).
   *
   * @throws RuntimeException if the server returns a non-2xx status
   */
  Stream<String> postStream(String requestBody) throws Exception;
}
