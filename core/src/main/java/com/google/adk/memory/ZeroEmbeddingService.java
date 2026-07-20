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

package com.google.adk.memory;

import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * A placeholder implementation of the EmbeddingService that returns a vector basis given
 * vocabulary.
 *
 * <p>If the original embedding is larger than the target dimension, I will implement a new
 * compression method. This method will divide the embedding array into dimension number of chunks
 * and then calculate the average of the values in each chunk. The result will be a new, compressed
 * array of the correct dimension.
 */
public class ZeroEmbeddingService implements EmbeddingService {

  private static final AtomicReference<Set<String>> STATIC_VOCABULARY =
      new AtomicReference<>(Collections.emptySet());

  static {
    try (InputStream inputStream =
        ZeroEmbeddingService.class.getClassLoader().getResourceAsStream("vocabulary.txt")) {
      if (inputStream != null) {
        String text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        Set<String> vocabulary = Arrays.stream(text.split("\\s+")).collect(Collectors.toSet());
        updateVocabulary(vocabulary);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private final int dimension;

  public ZeroEmbeddingService(int dimension) {
    this.dimension = dimension;
  }

  public static void updateVocabulary(Set<String> newVocabulary) {
    STATIC_VOCABULARY.set(new HashSet<>(newVocabulary));
  }

  @Override
  public Single<double[]> generateEmbedding(String text) {
    return Single.fromCallable(
        () -> {
          // Tokenize the text
          List<String> tokens = Arrays.asList(text.toLowerCase().split("\\W+"));

          // Create a vocabulary
          Set<String> vocabulary = STATIC_VOCABULARY.get();
          if (vocabulary.isEmpty()) {
            vocabulary = new HashSet<>(tokens);
          }

          double[] embedding;
          if (tokens.size() == 1) {
            // If the text is a single word, create a one-hot encoded vector
            embedding = new double[vocabulary.size()];
            int i = 0;
            for (String word : vocabulary) {
              if (word.equals(tokens.get(0))) {
                embedding[i] = 1.0;
                break;
              }
              i++;
            }
          } else {
            // If the text is a sentence, create a frequency map
            Map<String, Integer> frequencyMap = new HashMap<>();
            for (String token : tokens) {
              if (vocabulary.contains(token)) {
                frequencyMap.put(token, frequencyMap.getOrDefault(token, 0) + 1);
              }
            }

            // Create the embedding vector
            embedding = new double[vocabulary.size()];
            int i = 0;
            for (String word : vocabulary) {
              embedding[i++] = frequencyMap.getOrDefault(word, 0);
            }

            // L2 normalization
            double norm = 0.0;
            for (double value : embedding) {
              norm += value * value;
            }
            norm = Math.sqrt(norm);

            if (norm > 0.0) {
              for (int j = 0; j < embedding.length; j++) {
                embedding[j] /= norm;
              }
            }
          }

          // Resize the embedding to the desired dimension
          if (embedding.length > dimension) {
            // Compress the embedding using averaging/pooling
            double[] compressedEmbedding = new double[dimension];
            int chunkSize = (int) Math.ceil((double) embedding.length / dimension);
            for (int i = 0; i < dimension; i++) {
              int start = i * chunkSize;
              int end = Math.min(start + chunkSize, embedding.length);
              double sum = 0.0;
              for (int j = start; j < end; j++) {
                sum += embedding[j];
              }
              compressedEmbedding[i] = sum / (end - start);
            }
            return compressedEmbedding;
          } else {
            // Pad the embedding with zeros
            double[] resizedEmbedding = new double[dimension];
            System.arraycopy(embedding, 0, resizedEmbedding, 0, embedding.length);
            return resizedEmbedding;
          }
        });
  }
}
