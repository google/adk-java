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

package com.google.adk.tools.applicationintegrationtoolset;

import com.google.auth.Credentials;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * This interface provides a method to convert a service account JSON string to a Google Credentials
 * object.
 *
 * <p>Additionally, contains helper methods that aid with transfering the credentials' data to the
 * HttpRequest.Builder object
 */
public interface CredentialsHelper {

  /**
   * Converts a service account JSON string to a Google Credentials object.
   *
   * @param serviceAccountJson The service account JSON string.
   * @return A Google Credentials object.
   * @throws IOException when an error occurs during the conversion.
   */
  Credentials getGoogleCredentials(@Nullable String serviceAccountJson) throws IOException;

  /**
   * Populates the headers (such as Authorization or x-goog-project) in the HttpRequest.Builder with
   * the metadata from the credentials.
   *
   * @param builder HttpRequest.Builder object to populate the headers
   * @param credentials Credentials object containing the metadata
   * @return HttpRequest.Builder object with the headers populated
   * @throws IOException if an error occurs when getting the metadata from the credentials
   */
  public static HttpRequest.Builder populateHeaders(
      HttpRequest.Builder builder, Credentials credentials) throws IOException {
    for (Map.Entry<String, List<String>> entry : credentials.getRequestMetadata().entrySet()) {
      for (String value : entry.getValue()) {
        builder = builder.header(entry.getKey(), value);
      }
    }
    return builder;
  }
}
