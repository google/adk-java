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

package com.google.adk.transcription.config;

import com.google.adk.transcription.AudioFormat;
import com.google.adk.transcription.TranscriptionConfig;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads transcription configuration from environment variables or system properties. */
public class TranscriptionConfigLoader {
  private static final Logger logger = LoggerFactory.getLogger(TranscriptionConfigLoader.class);

  // Variable names
  private static final String ENDPOINT_ENV = "ADK_TRANSCRIPTION_ENDPOINT";
  private static final String API_KEY_ENV = "ADK_TRANSCRIPTION_API_KEY";
  private static final String LANGUAGE_ENV = "ADK_TRANSCRIPTION_LANGUAGE";
  private static final String TIMEOUT_ENV = "ADK_TRANSCRIPTION_TIMEOUT_SECONDS";
  private static final String MAX_RETRIES_ENV = "ADK_TRANSCRIPTION_MAX_RETRIES";
  private static final String SERVICE_TYPE_ENV = "ADK_TRANSCRIPTION_SERVICE_TYPE";
  private static final String CHUNK_SIZE_ENV = "ADK_TRANSCRIPTION_CHUNK_SIZE_MS";

  private static String getValue(String key) {
    String val = System.getProperty(key);
    if (val == null || val.isEmpty()) {
      val = System.getenv(key);
    }
    return val;
  }

  public static Optional<TranscriptionConfig> loadFromEnvironment() {
    String endpoint = getValue(ENDPOINT_ENV);

    // For Sarvam, we can default the endpoint if service type is sarvam
    String serviceType = getValue(SERVICE_TYPE_ENV);
    if ("sarvam".equalsIgnoreCase(serviceType) && (endpoint == null || endpoint.isEmpty())) {
      endpoint = "https://api.sarvam.ai/speech-to-text";
    }

    if (endpoint == null || endpoint.isEmpty()) {
      logger.debug("Transcription not configured ({} not set)", ENDPOINT_ENV);
      return Optional.empty();
    }

    TranscriptionConfig.Builder builder = TranscriptionConfig.builder().endpoint(endpoint);

    String apiKey = getValue(API_KEY_ENV);
    if (apiKey == null || apiKey.isEmpty()) {
      apiKey = getValue("SARVAM_API_KEY");
    }

    if (apiKey != null && !apiKey.isEmpty()) {
      builder.apiKey(apiKey);
    }

    String language = getValue(LANGUAGE_ENV);
    if (language != null && !language.isEmpty()) {
      builder.language(language);
    } else if ("sarvam".equalsIgnoreCase(serviceType)) {
      builder.language("hi-IN"); // Default for Sarvam POC
    }

    String timeoutStr = getValue(TIMEOUT_ENV);
    if (timeoutStr != null) {
      try {
        int timeoutSeconds = Integer.parseInt(timeoutStr);
        if (timeoutSeconds > 0) {
          builder.timeout(Duration.ofSeconds(timeoutSeconds));
        }
      } catch (NumberFormatException e) {
        logger.warn("Invalid timeout value: {}, using default", timeoutStr);
      }
    }

    builder.audioFormat(AudioFormat.PCM_16KHZ_MONO);
    builder.enablePartialResults(true);

    TranscriptionConfig config = builder.build();
    logger.info(
        "Loaded transcription config: endpoint={}, service={}", config.getEndpoint(), serviceType);

    return Optional.of(config);
  }
}
