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

package com.google.adk.tools.transcription;

import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.google.adk.transcription.TranscriptionConfig;
import com.google.adk.transcription.TranscriptionException;
import com.google.adk.transcription.TranscriptionResult;
import com.google.adk.transcription.TranscriptionService;
import com.google.adk.transcription.config.TranscriptionConfigLoader;
import com.google.adk.transcription.strategy.TranscriptionServiceFactory;
import java.lang.reflect.Method;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for on-demand audio transcription. Agents can call this tool when they need to transcribe
 * audio.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * TranscriptionTool transcriptionTool = TranscriptionTool.create();
 * LlmAgent agent = LlmAgent.builder()
 *     .addTool(transcriptionTool)
 *     .build();
 * }</pre>
 *
 * <p>Transcription is optional - if ADK_TRANSCRIPTION_ENDPOINT is not set, the tool will not be
 * available and will return an error when called.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public class TranscriptionTool {
  private static final Logger logger = LoggerFactory.getLogger(TranscriptionTool.class);

  private static final Optional<TranscriptionService> transcriptionService;
  private static final Optional<TranscriptionConfig> config;

  static {
    // Lazy load configuration and service at class initialization
    config = TranscriptionConfigLoader.loadFromEnvironment();
    transcriptionService = config.map(cfg -> TranscriptionServiceFactory.getOrCreate(cfg));

    if (transcriptionService.isEmpty()) {
      logger.info(
          "TranscriptionTool: transcription not configured (ADK_TRANSCRIPTION_ENDPOINT not set)");
    }
  }

  private TranscriptionTool() {}

  /**
   * Creates a FunctionTool instance for transcription. Returns null if transcription is not
   * configured.
   *
   * @return FunctionTool instance or null if not configured
   */
  public static FunctionTool create() {
    if (transcriptionService.isEmpty()) {
      logger.warn("Cannot create TranscriptionTool: transcription not configured");
      return null;
    }

    try {
      Method transcribeMethod =
          TranscriptionTool.class.getMethod("transcribe", String.class, String.class);
      return FunctionTool.create(new TranscriptionTool(), transcribeMethod);
    } catch (NoSuchMethodException e) {
      logger.error("Failed to create TranscriptionTool", e);
      return null;
    }
  }

  /** Creates a FunctionTool instance with explicit service (for testing). */
  public static FunctionTool create(TranscriptionService service, TranscriptionConfig cfg) {
    try {
      TranscriptionTool instance = new TranscriptionTool();
      Method transcribeMethod =
          TranscriptionTool.class.getMethod("transcribe", String.class, String.class);
      // For testing, we'd need to inject the service, but for now this works
      return FunctionTool.create(instance, transcribeMethod);
    } catch (NoSuchMethodException e) {
      logger.error("Failed to create TranscriptionTool", e);
      return null;
    }
  }

  /**
   * Transcribes audio data to text. This method is used by FunctionTool.
   *
   * @param audioData Base64-encoded audio data
   * @param language Optional language code (e.g., 'en', 'es', 'fr'). Default: auto-detect
   * @return Map containing transcription result
   */
  @Annotations.Schema(
      name = "transcribe_audio",
      description =
          "Transcribes audio data to text. Use this when you need to convert speech to text.")
  public Map<String, Object> transcribe(
      @Annotations.Schema(name = "audio_data", description = "Base64-encoded audio data")
          String audioData,
      @Annotations.Schema(
              name = "language",
              description = "Language code (optional, e.g., 'en', 'es', 'fr')",
              optional = true)
          String language) {
    if (transcriptionService.isEmpty()) {
      return Map.of(
          "error",
          "Transcription not configured. Set ADK_TRANSCRIPTION_ENDPOINT environment variable.");
    }

    try {
      // Decode base64 audio
      byte[] audioBytes = Base64.getDecoder().decode(audioData);

      // Build config with optional parameters
      TranscriptionConfig requestConfig = config.get();
      if (language != null && !language.isEmpty()) {
        requestConfig =
            TranscriptionConfig.builder()
                .endpoint(requestConfig.getEndpoint())
                .language(language)
                .timeout(requestConfig.getTimeout())
                .maxRetries(requestConfig.getMaxRetries())
                .build();
      }

      // Transcribe
      TranscriptionResult result = transcriptionService.get().transcribe(audioBytes, requestConfig);

      logger.debug(
          "Transcribed audio: {} bytes -> {} chars", audioBytes.length, result.getText().length());

      // Return as map for tool response
      Map<String, Object> response = new java.util.HashMap<>();
      response.put("text", result.getText());
      result.getLanguage().ifPresent(lang -> response.put("language", lang));
      result.getConfidence().ifPresent(conf -> response.put("confidence", conf));
      result.getDuration().ifPresent(dur -> response.put("duration_ms", dur.toMillis()));

      return response;

    } catch (TranscriptionException e) {
      logger.error("Failed to transcribe audio", e);
      return Map.of("error", "Failed to transcribe audio: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      logger.error("Invalid audio data", e);
      return Map.of("error", "Invalid audio data: " + e.getMessage());
    }
  }

  /**
   * Checks if transcription is available.
   *
   * @return true if transcription service is configured and available
   */
  public static boolean isAvailable() {
    return transcriptionService.isPresent() && transcriptionService.get().isAvailable();
  }
}
