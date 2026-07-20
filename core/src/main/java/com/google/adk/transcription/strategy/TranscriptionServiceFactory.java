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

package com.google.adk.transcription.strategy;

import com.google.adk.transcription.ServiceType;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionService;
import com.google.adk.transcription.client.WhisperApiClient;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating transcription services with lazy loading. Services are cached and only
 * created when needed.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public class TranscriptionServiceFactory {
  private static final Logger logger = LoggerFactory.getLogger(TranscriptionServiceFactory.class);

  // Cache for service instances (lazy loading)
  private static final ConcurrentHashMap<String, TranscriptionService> serviceCache =
      new ConcurrentHashMap<>();

  private static final ReentrantLock lock = new ReentrantLock();

  /**
   * Creates or retrieves a transcription service based on configuration. Uses lazy loading -
   * service is only created when first needed.
   *
   * @param config Transcription configuration
   * @return TranscriptionService instance (cached)
   */
  public static TranscriptionService getOrCreate(TranscriptionConfig config) {
    String cacheKey = generateCacheKey(config);

    // Double-check locking for thread safety
    TranscriptionService service = serviceCache.get(cacheKey);
    if (service != null) {
      return service;
    }

    lock.lock();
    try {
      // Check again after acquiring lock
      service = serviceCache.get(cacheKey);
      if (service != null) {
        return service;
      }

      // Create new service
      service = createService(config);
      serviceCache.put(cacheKey, service);
      logger.info("Created transcription service: {}", cacheKey);
      return service;
    } finally {
      lock.unlock();
    }
  }

  /** Creates a new transcription service (without caching). Use getOrCreate() for normal usage. */
  public static TranscriptionService create(TranscriptionConfig config) {
    return createService(config);
  }

  private static TranscriptionService createService(TranscriptionConfig config) {
    ServiceType serviceType = determineServiceType(config);

    switch (serviceType) {
      case SARVAM:
        throw new UnsupportedOperationException(
            "Sarvam STT has moved to the contrib/sarvam-ai module. "
                + "Use SarvamSttService from com.google.adk.models.sarvamai.stt instead.");

      case WHISPER:
        return createWhisperService(config);

      case GEMINI:
        throw new UnsupportedOperationException("Gemini transcription not yet implemented");

      default:
        throw new IllegalArgumentException("Unsupported service type: " + serviceType);
    }
  }

  private static ServiceType determineServiceType(TranscriptionConfig config) {
    // Check environment variable first
    String serviceTypeEnv = System.getenv("ADK_TRANSCRIPTION_SERVICE_TYPE");
    if (serviceTypeEnv != null && !serviceTypeEnv.isEmpty()) {
      return ServiceType.fromString(serviceTypeEnv);
    }

    // Infer from endpoint if not specified
    String endpoint = config.getEndpoint();
    if (endpoint != null) {
      String lowerEndpoint = endpoint.toLowerCase();
      if (lowerEndpoint.contains("whisper") || lowerEndpoint.contains("transcribe")) {
        return ServiceType.WHISPER;
      }
    }

    // Default to Whisper
    return ServiceType.WHISPER;
  }

  private static TranscriptionService createWhisperService(TranscriptionConfig config) {
    String endpoint = config.getEndpoint();
    if (endpoint == null || endpoint.isEmpty()) {
      throw new IllegalArgumentException("Whisper endpoint is required");
    }

    WhisperApiClient client = new WhisperApiClient(endpoint, config.getMaxRetries());

    return new WhisperTranscriptionService(client, config);
  }

  private static String generateCacheKey(TranscriptionConfig config) {
    return String.format(
        "%s:%s:%s", determineServiceType(config), config.getEndpoint(), config.getLanguage());
  }

  /** Clears the service cache (useful for testing). */
  public static void clearCache() {
    lock.lock();
    try {
      serviceCache.clear();
    } finally {
      lock.unlock();
    }
  }
}
