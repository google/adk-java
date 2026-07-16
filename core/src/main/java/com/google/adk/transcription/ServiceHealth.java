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

package com.google.adk.transcription;

import java.util.Optional;

/**
 * Health status information for transcription service.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public final class ServiceHealth {
  private final boolean available;
  private final ServiceType serviceType;
  private final long timestamp;
  private final Optional<String> message;
  private final Optional<Long> responseTimeMs;

  private ServiceHealth(Builder builder) {
    this.available = builder.available;
    this.serviceType = builder.serviceType;
    this.timestamp = builder.timestamp;
    this.message = Optional.ofNullable(builder.message);
    this.responseTimeMs = Optional.ofNullable(builder.responseTimeMs);
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean isAvailable() {
    return available;
  }

  public ServiceType getServiceType() {
    return serviceType;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public Optional<String> getMessage() {
    return message;
  }

  public Optional<Long> getResponseTimeMs() {
    return responseTimeMs;
  }

  public static class Builder {
    private boolean available;
    private ServiceType serviceType;
    private long timestamp = System.currentTimeMillis();
    private String message;
    private Long responseTimeMs;

    public Builder available(boolean available) {
      this.available = available;
      return this;
    }

    public Builder serviceType(ServiceType serviceType) {
      this.serviceType = serviceType;
      return this;
    }

    public Builder timestamp(long timestamp) {
      this.timestamp = timestamp;
      return this;
    }

    public Builder message(String message) {
      this.message = message;
      return this;
    }

    public Builder responseTimeMs(long responseTimeMs) {
      this.responseTimeMs = responseTimeMs;
      return this;
    }

    public ServiceHealth build() {
      if (serviceType == null) {
        throw new IllegalArgumentException("Service type is required");
      }
      return new ServiceHealth(this);
    }
  }
}
