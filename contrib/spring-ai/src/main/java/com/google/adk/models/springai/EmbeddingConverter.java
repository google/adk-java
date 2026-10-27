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
package com.google.adk.models.springai;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Floats;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Static utilities for converting between Spring AI and ADK/GenAI embedding formats and performing
 * vector operations.
 */
public final class EmbeddingConverter {

  /**
   * Create an EmbeddingRequest for a single text input.
   *
   * @param text The text to embed
   * @return EmbeddingRequest for the text
   */
  public static EmbeddingRequest createRequest(String text) {
    Objects.requireNonNull(text, "text cannot be null");
    return new EmbeddingRequest(List.of(text), null);
  }

  /**
   * Create an EmbeddingRequest for multiple text inputs.
   *
   * @param texts The texts to embed
   * @return EmbeddingRequest for the texts
   */
  public static EmbeddingRequest createRequest(List<String> texts) {
    Objects.requireNonNull(texts, "texts cannot be null");
    return new EmbeddingRequest(texts, null);
  }

  /** Extracts the input texts from a Spring AI {@link EmbeddingRequest}. */
  public static ImmutableList<String> extractTexts(EmbeddingRequest request) {
    Objects.requireNonNull(request, "request cannot be null");
    if (request.getInstructions() == null) {
      return ImmutableList.of();
    }
    return ImmutableList.copyOf(request.getInstructions());
  }

  /**
   * Extract embedding vectors from an EmbeddingResponse.
   *
   * @param response The embedding response
   * @return List of embedding vectors as float arrays
   */
  public static List<float[]> extractEmbeddings(EmbeddingResponse response) {
    Objects.requireNonNull(response, "response cannot be null");
    List<float[]> embeddings = new ArrayList<>();
    for (Embedding embedding : response.getResults()) {
      embeddings.add(embedding.getOutput());
    }
    return embeddings;
  }

  /**
   * Extract the first embedding vector from an EmbeddingResponse.
   *
   * @param response The embedding response
   * @return The first embedding vector, or null if no embeddings
   */
  public static float @Nullable [] extractFirstEmbedding(EmbeddingResponse response) {
    Objects.requireNonNull(response, "response cannot be null");
    if (response.getResults().isEmpty()) {
      return null;
    }
    return response.getResults().get(0).getOutput();
  }

  /** Creates a Spring AI {@link Embedding} from a float vector and batch index. */
  public static Embedding toEmbedding(float[] vector, int index) {
    Objects.requireNonNull(vector, "vector cannot be null");
    return new Embedding(Arrays.copyOf(vector, vector.length), index);
  }

  /** Converts a GenAI {@link ContentEmbedding} to a Spring AI {@link Embedding}. */
  public static Embedding toEmbedding(ContentEmbedding contentEmbedding, int index) {
    return new Embedding(extractEmbedding(contentEmbedding), index);
  }

  /** Creates a Spring AI {@link EmbeddingResponse} from a single embedding vector. */
  public static EmbeddingResponse toEmbeddingResponse(float[] vector) {
    Objects.requireNonNull(vector, "vector cannot be null");
    return toEmbeddingResponse(List.of(vector));
  }

  /** Creates a Spring AI {@link EmbeddingResponse} from a list of embedding vectors. */
  public static EmbeddingResponse toEmbeddingResponse(List<float[]> vectors) {
    Objects.requireNonNull(vectors, "vectors cannot be null");
    List<Embedding> embeddings = new ArrayList<>(vectors.size());
    for (int i = 0; i < vectors.size(); i++) {
      embeddings.add(toEmbedding(vectors.get(i), i));
    }
    return new EmbeddingResponse(embeddings);
  }

  /** Converts a float array to a GenAI {@link ContentEmbedding}. */
  public static ContentEmbedding toContentEmbedding(float[] vector) {
    Objects.requireNonNull(vector, "vector cannot be null");
    return ContentEmbedding.builder().values(ImmutableList.copyOf(Floats.asList(vector))).build();
  }

  /** Converts a Spring AI {@link Embedding} to a GenAI {@link ContentEmbedding}. */
  public static ContentEmbedding toContentEmbedding(Embedding embedding) {
    Objects.requireNonNull(embedding, "embedding cannot be null");
    float[] output = embedding.getOutput();
    return toContentEmbedding(output != null ? output : new float[0]);
  }

  /** Extracts a float array from a GenAI {@link ContentEmbedding}. */
  public static float[] extractEmbedding(ContentEmbedding contentEmbedding) {
    Objects.requireNonNull(contentEmbedding, "contentEmbedding cannot be null");
    List<Float> values = contentEmbedding.values().orElse(List.of());
    float[] vector = new float[values.size()];
    for (int i = 0; i < values.size(); i++) {
      Float val = values.get(i);
      vector[i] = val != null ? val : 0.0f;
    }
    return vector;
  }

  /**
   * Converts a Spring AI {@link EmbeddingResponse} to a GenAI {@link EmbedContentResponse},
   * ordering batch embeddings by {@link Embedding#getIndex()} when present.
   */
  public static EmbedContentResponse toEmbedContentResponse(EmbeddingResponse response) {
    Objects.requireNonNull(response, "response cannot be null");
    List<Embedding> results = new ArrayList<>(response.getResults());
    if (results.stream().allMatch(e -> e != null && e.getIndex() != null)) {
      results.sort(Comparator.comparingInt(Embedding::getIndex));
    }
    ImmutableList.Builder<ContentEmbedding> embeddings =
        ImmutableList.builderWithExpectedSize(results.size());
    for (Embedding embedding : results) {
      embeddings.add(toContentEmbedding(embedding));
    }
    return EmbedContentResponse.builder().embeddings(embeddings.build()).build();
  }

  /** Converts a GenAI {@link EmbedContentResponse} to a Spring AI {@link EmbeddingResponse}. */
  public static EmbeddingResponse fromEmbedContentResponse(EmbedContentResponse response) {
    Objects.requireNonNull(response, "response cannot be null");
    List<ContentEmbedding> contentEmbeddings = response.embeddings().orElse(List.of());
    List<Embedding> embeddings = new ArrayList<>(contentEmbeddings.size());
    for (int i = 0; i < contentEmbeddings.size(); i++) {
      embeddings.add(toEmbedding(contentEmbeddings.get(i), i));
    }
    return new EmbeddingResponse(embeddings);
  }

  /**
   * Calculate cosine similarity between two embedding vectors.
   *
   * @param embedding1 First embedding vector
   * @param embedding2 Second embedding vector
   * @return Cosine similarity score between -1 and 1
   */
  public static double cosineSimilarity(float[] embedding1, float[] embedding2) {
    if (embedding1.length != embedding2.length) {
      throw new IllegalArgumentException(
          "Embedding vectors must have the same dimensions: "
              + embedding1.length
              + " vs "
              + embedding2.length);
    }

    double dotProduct = 0.0;
    double norm1 = 0.0;
    double norm2 = 0.0;

    for (int i = 0; i < embedding1.length; i++) {
      dotProduct += embedding1[i] * embedding2[i];
      norm1 += embedding1[i] * embedding1[i];
      norm2 += embedding2[i] * embedding2[i];
    }

    if (norm1 == 0.0 || norm2 == 0.0) {
      return 0.0; // Handle zero vectors
    }

    return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
  }

  /**
   * Calculate Euclidean distance between two embedding vectors.
   *
   * @param embedding1 First embedding vector
   * @param embedding2 Second embedding vector
   * @return Euclidean distance
   */
  public static double euclideanDistance(float[] embedding1, float[] embedding2) {
    if (embedding1.length != embedding2.length) {
      throw new IllegalArgumentException(
          "Embedding vectors must have the same dimensions: "
              + embedding1.length
              + " vs "
              + embedding2.length);
    }

    double sum = 0.0;
    for (int i = 0; i < embedding1.length; i++) {
      double diff = embedding1[i] - embedding2[i];
      sum += diff * diff;
    }

    return Math.sqrt(sum);
  }

  /**
   * Normalize an embedding vector to unit length.
   *
   * @param embedding The embedding vector to normalize
   * @return Normalized embedding vector
   */
  public static float[] normalize(float[] embedding) {
    double norm = 0.0;
    for (float value : embedding) {
      norm += value * value;
    }
    norm = Math.sqrt(norm);

    if (norm == 0.0) {
      return Arrays.copyOf(embedding, embedding.length); // Return copy of zero vector
    }

    float[] normalized = new float[embedding.length];
    for (int i = 0; i < embedding.length; i++) {
      normalized[i] = (float) (embedding[i] / norm);
    }

    return normalized;
  }

  /**
   * Find the most similar embedding from a list of candidates.
   *
   * @param query The query embedding
   * @param candidates List of candidate embeddings
   * @return Index of the most similar embedding, or -1 if no candidates
   */
  public static int findMostSimilar(float[] query, List<float[]> candidates) {
    if (candidates.isEmpty()) {
      return -1;
    }

    int bestIndex = 0;
    double bestSimilarity = cosineSimilarity(query, candidates.get(0));

    for (int i = 1; i < candidates.size(); i++) {
      double similarity = cosineSimilarity(query, candidates.get(i));
      if (similarity > bestSimilarity) {
        bestSimilarity = similarity;
        bestIndex = i;
      }
    }

    return bestIndex;
  }

  /**
   * Calculate similarity scores between a query and all candidates.
   *
   * @param query The query embedding
   * @param candidates List of candidate embeddings
   * @return List of similarity scores
   */
  public static List<Double> calculateSimilarities(float[] query, List<float[]> candidates) {
    List<Double> similarities = new ArrayList<>();
    for (float[] candidate : candidates) {
      similarities.add(cosineSimilarity(query, candidate));
    }
    return similarities;
  }

  /**
   * Convert float array to double array.
   *
   * @param floatArray The float array
   * @return Equivalent double array
   */
  public static double[] toDoubleArray(float[] floatArray) {
    double[] doubleArray = new double[floatArray.length];
    for (int i = 0; i < floatArray.length; i++) {
      doubleArray[i] = floatArray[i];
    }
    return doubleArray;
  }

  /**
   * Convert double array to float array.
   *
   * @param doubleArray The double array
   * @return Equivalent float array
   */
  public static float[] toFloatArray(double[] doubleArray) {
    float[] floatArray = new float[doubleArray.length];
    for (int i = 0; i < doubleArray.length; i++) {
      floatArray[i] = (float) doubleArray[i];
    }
    return floatArray;
  }

  private EmbeddingConverter() {}
}
