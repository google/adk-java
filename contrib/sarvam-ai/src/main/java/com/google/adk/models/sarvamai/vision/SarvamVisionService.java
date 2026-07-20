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

package com.google.adk.models.sarvamai.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.sarvamai.SarvamAiConfig;
import com.google.adk.models.sarvamai.SarvamAiException;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sarvam Vision Document Intelligence service.
 *
 * <p>Powered by the Sarvam Vision 3B VLM for extracting structured text from documents across 23
 * languages (22 Indian + English). Supports PDF, PNG, JPG, and ZIP inputs with HTML or Markdown
 * output.
 *
 * <p>The workflow follows Sarvam's async job pattern:
 *
 * <ol>
 *   <li>{@link #createJob} - Initialize a document processing job
 *   <li>{@link #uploadDocument} - Upload the document to the job's presigned URL
 *   <li>{@link #startJob} - Begin processing
 *   <li>{@link #getJobStatus} - Poll for completion
 *   <li>{@link #downloadResults} - Retrieve the processed output
 * </ol>
 *
 * @author Sandeep Belgavi
 */
public final class SarvamVisionService {

  private static final Logger logger = LoggerFactory.getLogger(SarvamVisionService.class);
  private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

  private final SarvamAiConfig config;
  private final OkHttpClient httpClient;
  private final ObjectMapper objectMapper;

  public SarvamVisionService(SarvamAiConfig config, OkHttpClient httpClient) {
    this.config = Objects.requireNonNull(config);
    this.httpClient = Objects.requireNonNull(httpClient);
    this.objectMapper = new ObjectMapper();
  }

  /** Result of a job creation request. */
  public record JobInfo(String jobId, String uploadUrl) {}

  /** Current status of a document intelligence job. */
  public record JobStatus(String jobId, String state, Optional<String> downloadUrl) {}

  /**
   * Creates a new document intelligence job.
   *
   * @param languageCode BCP-47 code (e.g., "hi-IN", "en-IN")
   * @param outputFormat "html" or "md"
   * @return job info with ID and upload URL
   */
  public JobInfo createJob(String languageCode, String outputFormat) {
    Objects.requireNonNull(languageCode);
    Objects.requireNonNull(outputFormat);

    try {
      String body =
          objectMapper.writeValueAsString(
              new java.util.HashMap<String, String>() {
                {
                  put("language", languageCode);
                  put("output_format", outputFormat);
                }
              });

      Request request =
          new Request.Builder()
              .url(config.visionEndpoint() + "/create")
              .addHeader("api-subscription-key", config.apiKey())
              .addHeader("Content-Type", "application/json")
              .post(RequestBody.create(body, JSON_MEDIA_TYPE))
              .build();

      logger.debug("Creating vision job: lang={}, format={}", languageCode, outputFormat);

      try (Response response = httpClient.newCall(request).execute()) {
        ensureSuccess(response, "Create job");
        JsonNode root = objectMapper.readTree(response.body().string());
        String jobId = root.path("job_id").asText();
        String uploadUrl = root.path("upload_url").asText();
        return new JobInfo(jobId, uploadUrl);
      }
    } catch (SarvamAiException e) {
      throw e;
    } catch (Exception e) {
      throw new SarvamAiException("Failed to create vision job", e);
    }
  }

  /**
   * Uploads a document to the presigned upload URL.
   *
   * @param uploadUrl the presigned URL from {@link #createJob}
   * @param filePath path to the document file
   */
  public void uploadDocument(String uploadUrl, Path filePath) {
    Objects.requireNonNull(uploadUrl);
    Objects.requireNonNull(filePath);

    try {
      byte[] fileBytes = Files.readAllBytes(filePath);
      String contentType = Files.probeContentType(filePath);
      if (contentType == null) {
        contentType = "application/octet-stream";
      }

      Request request =
          new Request.Builder()
              .url(uploadUrl)
              .put(RequestBody.create(fileBytes, MediaType.parse(contentType)))
              .build();

      logger.debug("Uploading document {} ({} bytes)", filePath, fileBytes.length);

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          throw new SarvamAiException(
              "Document upload failed: " + response.code(), response.code(), null, null);
        }
      }
    } catch (SarvamAiException e) {
      throw e;
    } catch (Exception e) {
      throw new SarvamAiException("Failed to upload document", e);
    }
  }

  /** Starts processing a previously created and uploaded job. */
  public void startJob(String jobId) {
    Objects.requireNonNull(jobId);

    try {
      String body = objectMapper.writeValueAsString(java.util.Map.of("job_id", jobId));
      Request request =
          new Request.Builder()
              .url(config.visionEndpoint() + "/start")
              .addHeader("api-subscription-key", config.apiKey())
              .addHeader("Content-Type", "application/json")
              .post(RequestBody.create(body, JSON_MEDIA_TYPE))
              .build();

      logger.debug("Starting vision job {}", jobId);

      try (Response response = httpClient.newCall(request).execute()) {
        ensureSuccess(response, "Start job");
      }
    } catch (SarvamAiException e) {
      throw e;
    } catch (Exception e) {
      throw new SarvamAiException("Failed to start vision job", e);
    }
  }

  /** Gets the current status of a document processing job. */
  public JobStatus getJobStatus(String jobId) {
    Objects.requireNonNull(jobId);

    try {
      Request request =
          new Request.Builder()
              .url(config.visionEndpoint() + "/status?job_id=" + jobId)
              .addHeader("api-subscription-key", config.apiKey())
              .get()
              .build();

      try (Response response = httpClient.newCall(request).execute()) {
        ensureSuccess(response, "Get job status");
        JsonNode root = objectMapper.readTree(response.body().string());
        String state = root.path("job_state").asText("unknown");
        String downloadUrl = root.path("download_url").asText(null);
        return new JobStatus(jobId, state, Optional.ofNullable(downloadUrl));
      }
    } catch (SarvamAiException e) {
      throw e;
    } catch (Exception e) {
      throw new SarvamAiException("Failed to get job status", e);
    }
  }

  /**
   * Downloads the processed results.
   *
   * @param downloadUrl the URL from {@link JobStatus#downloadUrl()}
   * @return the result bytes (typically a ZIP file containing HTML/Markdown)
   */
  public byte[] downloadResults(String downloadUrl) {
    Objects.requireNonNull(downloadUrl);

    try {
      Request request = new Request.Builder().url(downloadUrl).get().build();

      logger.debug("Downloading vision results from {}", downloadUrl);

      try (Response response = httpClient.newCall(request).execute()) {
        if (!response.isSuccessful()) {
          throw new SarvamAiException(
              "Download failed: " + response.code(), response.code(), null, null);
        }
        return response.body().bytes();
      }
    } catch (SarvamAiException e) {
      throw e;
    } catch (Exception e) {
      throw new SarvamAiException("Failed to download results", e);
    }
  }

  /**
   * Convenience method: runs the full pipeline (create -> upload -> start -> poll -> download)
   * asynchronously.
   */
  public Single<byte[]> processDocument(Path filePath, String languageCode, String outputFormat) {
    return Single.<byte[]>create(
            emitter -> {
              try {
                JobInfo job = createJob(languageCode, outputFormat);
                uploadDocument(job.uploadUrl(), filePath);
                startJob(job.jobId());

                // Poll with backoff
                int maxPolls = 60;
                long pollIntervalMs = 2000;
                for (int i = 0; i < maxPolls; i++) {
                  Thread.sleep(pollIntervalMs);
                  JobStatus status = getJobStatus(job.jobId());

                  if ("completed".equalsIgnoreCase(status.state())) {
                    if (status.downloadUrl().isPresent()) {
                      byte[] result = downloadResults(status.downloadUrl().get());
                      emitter.onSuccess(result);
                      return;
                    }
                    emitter.onError(
                        new SarvamAiException("Job completed but no download URL provided"));
                    return;
                  } else if ("failed".equalsIgnoreCase(status.state())) {
                    emitter.onError(new SarvamAiException("Vision job failed: " + job.jobId()));
                    return;
                  }

                  // Adaptive backoff
                  pollIntervalMs = Math.min(pollIntervalMs * 2, 10_000);
                }
                emitter.onError(new SarvamAiException("Vision job timed out: " + job.jobId()));
              } catch (Exception e) {
                emitter.onError(e);
              }
            })
        .subscribeOn(Schedulers.io());
  }

  public boolean isAvailable() {
    return config.apiKey() != null && !config.apiKey().isEmpty();
  }

  private void ensureSuccess(Response response, String operation) throws IOException {
    if (!response.isSuccessful()) {
      String errorBody = response.body() != null ? response.body().string() : "";
      throw new SarvamAiException(
          operation + " failed: " + response.code() + " " + errorBody, response.code(), null, null);
    }
  }
}
