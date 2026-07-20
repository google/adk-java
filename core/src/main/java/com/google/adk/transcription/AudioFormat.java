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

/**
 * Audio format specifications for transcription.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public enum AudioFormat {
  /** PCM 16kHz Mono (recommended for speech). */
  PCM_16KHZ_MONO("audio/pcm", 16000, 1, 16),

  /** PCM 44.1kHz Mono. */
  PCM_44KHZ_MONO("audio/pcm", 44100, 1, 16),

  /** PCM 48kHz Mono. */
  PCM_48KHZ_MONO("audio/pcm", 48000, 1, 16),

  /** WAV format. */
  WAV("audio/wav", 16000, 1, 16),

  /** MP3 format. */
  MP3("audio/mpeg", 16000, 1, 16);

  private final String mimeType;
  private final int sampleRate;
  private final int channels;
  private final int bitsPerSample;

  AudioFormat(String mimeType, int sampleRate, int channels, int bitsPerSample) {
    this.mimeType = mimeType;
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.bitsPerSample = bitsPerSample;
  }

  public String getMimeType() {
    return mimeType;
  }

  public int getSampleRate() {
    return sampleRate;
  }

  public int getChannels() {
    return channels;
  }

  public int getBitsPerSample() {
    return bitsPerSample;
  }

  /**
   * Calculates expected audio data size for given duration.
   *
   * @param durationMs Duration in milliseconds
   * @return Expected size in bytes
   */
  public int calculateSizeForDuration(int durationMs) {
    return (sampleRate * channels * bitsPerSample / 8) * durationMs / 1000;
  }
}
