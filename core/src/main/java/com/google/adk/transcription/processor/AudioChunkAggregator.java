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

package com.google.adk.transcription.processor;

import com.google.adk.transcription.AudioFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates audio chunks for batch transcription processing.
 *
 * @author Sandeep Belgavi
 * @since 2026-01-24
 */
public class AudioChunkAggregator {
  private final AudioFormat audioFormat;
  private final Duration aggregationWindow;
  private final List<byte[]> chunks;
  private long lastTranscriptionTime;

  public AudioChunkAggregator(AudioFormat audioFormat, Duration aggregationWindow) {
    this.audioFormat = audioFormat;
    this.aggregationWindow = aggregationWindow;
    this.chunks = new ArrayList<>();
    this.lastTranscriptionTime = System.currentTimeMillis();
  }

  /**
   * Adds a chunk to the aggregator.
   *
   * @param chunk Audio chunk
   * @return List of chunks accumulated so far
   */
  public List<byte[]> addChunk(byte[] chunk) {
    chunks.add(chunk);
    return chunks;
  }

  /**
   * Checks if transcription should be performed based on aggregation window.
   *
   * @return true if should transcribe
   */
  public boolean shouldTranscribe() {
    long now = System.currentTimeMillis();
    return (now - lastTranscriptionTime) >= aggregationWindow.toMillis();
  }

  /**
   * Aggregates accumulated chunks into a single byte array.
   *
   * @param chunks List of chunks to aggregate
   * @return Aggregated audio data
   */
  public byte[] aggregate(List<byte[]> chunks) {
    int totalSize = chunks.stream().mapToInt(chunk -> chunk.length).sum();
    byte[] aggregated = new byte[totalSize];
    int offset = 0;

    for (byte[] chunk : chunks) {
      System.arraycopy(chunk, 0, aggregated, offset, chunk.length);
      offset += chunk.length;
    }

    chunks.clear();
    lastTranscriptionTime = System.currentTimeMillis();

    return aggregated;
  }
}
