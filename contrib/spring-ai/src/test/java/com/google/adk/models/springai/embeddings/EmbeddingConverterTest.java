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
package com.google.adk.models.springai.embeddings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.adk.models.springai.EmbeddingConverter;
import com.google.genai.types.ContentEmbedding;
import com.google.genai.types.EmbedContentResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class EmbeddingConverterTest {

  @Test
  void testCreateRequestSingleText() {
    String text = "test text";
    EmbeddingRequest request = EmbeddingConverter.createRequest(text);

    assertThat(request.getInstructions()).containsExactly(text);
    assertThat(request.getOptions()).isNull();
  }

  @Test
  void testCreateRequestMultipleTexts() {
    List<String> texts = Arrays.asList("text1", "text2", "text3");
    EmbeddingRequest request = EmbeddingConverter.createRequest(texts);

    assertThat(request.getInstructions()).containsExactlyElementsOf(texts);
    assertThat(request.getOptions()).isNull();
  }

  @Test
  void testExtractEmbeddings() {
    float[] vec1 = {0.1f, 0.2f};
    float[] vec2 = {0.3f, 0.4f};
    EmbeddingResponse response = EmbeddingConverter.toEmbeddingResponse(List.of(vec1, vec2));

    List<float[]> extracted = EmbeddingConverter.extractEmbeddings(response);

    assertThat(extracted).hasSize(2);
    assertThat(extracted.get(0)).containsExactly(0.1f, 0.2f);
    assertThat(extracted.get(1)).containsExactly(0.3f, 0.4f);
  }

  @Test
  void testExtractFirstEmbedding() {
    float[] vec1 = {0.5f, 0.6f, 0.7f};
    EmbeddingResponse response = EmbeddingConverter.toEmbeddingResponse(vec1);

    float[] first = EmbeddingConverter.extractFirstEmbedding(response);

    assertThat(first).containsExactly(0.5f, 0.6f, 0.7f);
  }

  @Test
  void testExtractFirstEmbeddingEmptyResponse() {
    EmbeddingResponse emptyResponse = new EmbeddingResponse(List.of());

    float[] first = EmbeddingConverter.extractFirstEmbedding(emptyResponse);

    assertThat(first).isNull();
  }

  @Test
  void testCosineSimilarityIdenticalVectors() {
    float[] vector1 = {1.0f, 0.0f, 0.0f};
    float[] vector2 = {1.0f, 0.0f, 0.0f};

    double similarity = EmbeddingConverter.cosineSimilarity(vector1, vector2);

    assertThat(similarity).isCloseTo(1.0, within(0.0001));
  }

  @Test
  void testCosineSimilarityOrthogonalVectors() {
    float[] vector1 = {1.0f, 0.0f, 0.0f};
    float[] vector2 = {0.0f, 1.0f, 0.0f};

    double similarity = EmbeddingConverter.cosineSimilarity(vector1, vector2);

    assertThat(similarity).isCloseTo(0.0, within(0.0001));
  }

  @Test
  void testCosineSimilarityOppositeVectors() {
    float[] vector1 = {1.0f, 0.0f, 0.0f};
    float[] vector2 = {-1.0f, 0.0f, 0.0f};

    double similarity = EmbeddingConverter.cosineSimilarity(vector1, vector2);

    assertThat(similarity).isCloseTo(-1.0, within(0.0001));
  }

  @Test
  void testCosineSimilarityDifferentDimensions() {
    float[] vector1 = {1.0f, 0.0f};
    float[] vector2 = {1.0f, 0.0f, 0.0f};

    assertThrows(
        IllegalArgumentException.class,
        () -> EmbeddingConverter.cosineSimilarity(vector1, vector2));
  }

  @Test
  void testCosineSimilarityZeroVectors() {
    float[] vector1 = {0.0f, 0.0f, 0.0f};
    float[] vector2 = {1.0f, 2.0f, 3.0f};

    double similarity = EmbeddingConverter.cosineSimilarity(vector1, vector2);

    assertThat(similarity).isCloseTo(0.0, within(0.0001));
  }

  @Test
  void testEuclideanDistance() {
    float[] vector1 = {1.0f, 2.0f, 3.0f};
    float[] vector2 = {4.0f, 5.0f, 6.0f};

    double distance = EmbeddingConverter.euclideanDistance(vector1, vector2);

    // Distance should be sqrt((4-1)^2 + (5-2)^2 + (6-3)^2) = sqrt(9+9+9) = sqrt(27) ≈ 5.196
    assertThat(distance).isCloseTo(5.196, within(0.01));
  }

  @Test
  void testEuclideanDistanceIdenticalVectors() {
    float[] vector1 = {1.0f, 2.0f, 3.0f};
    float[] vector2 = {1.0f, 2.0f, 3.0f};

    double distance = EmbeddingConverter.euclideanDistance(vector1, vector2);

    assertThat(distance).isCloseTo(0.0, within(0.0001));
  }

  @Test
  void testEuclideanDistanceDifferentDimensions() {
    float[] vector1 = {1.0f, 2.0f};
    float[] vector2 = {1.0f, 2.0f, 3.0f};

    assertThrows(
        IllegalArgumentException.class,
        () -> EmbeddingConverter.euclideanDistance(vector1, vector2));
  }

  @Test
  void testNormalize() {
    float[] vector = {3.0f, 4.0f, 0.0f}; // Magnitude = 5

    float[] normalized = EmbeddingConverter.normalize(vector);

    assertThat(normalized[0]).isCloseTo(0.6f, within(0.0001f));
    assertThat(normalized[1]).isCloseTo(0.8f, within(0.0001f));
    assertThat(normalized[2]).isCloseTo(0.0f, within(0.0001f));

    // Check that the normalized vector has unit length
    double magnitude =
        Math.sqrt(
            normalized[0] * normalized[0]
                + normalized[1] * normalized[1]
                + normalized[2] * normalized[2]);
    assertThat(magnitude).isCloseTo(1.0, within(0.0001));
  }

  @Test
  void testNormalizeZeroVector() {
    float[] vector = {0.0f, 0.0f, 0.0f};

    float[] normalized = EmbeddingConverter.normalize(vector);

    assertThat(normalized).isEqualTo(vector); // Should return copy of zero vector
    assertThat(normalized).isNotSameAs(vector); // Should be a copy, not the same instance
  }

  @Test
  void testFindMostSimilar() {
    float[] query = {1.0f, 0.0f, 0.0f};
    List<float[]> candidates =
        Arrays.asList(
            new float[] {0.0f, 1.0f, 0.0f}, // Orthogonal - similarity 0
            new float[] {1.0f, 0.0f, 0.0f}, // Identical - similarity 1
            new float[] {0.5f, 0.5f, 0.0f}); // Some similarity

    int mostSimilarIndex = EmbeddingConverter.findMostSimilar(query, candidates);

    assertThat(mostSimilarIndex).isEqualTo(1); // Second candidate is identical
  }

  @Test
  void testFindMostSimilarEmptyCandidates() {
    float[] query = {1.0f, 0.0f, 0.0f};
    List<float[]> candidates = Collections.emptyList();

    int mostSimilarIndex = EmbeddingConverter.findMostSimilar(query, candidates);

    assertThat(mostSimilarIndex).isEqualTo(-1);
  }

  @Test
  void testCalculateSimilarities() {
    float[] query = {1.0f, 0.0f, 0.0f};
    List<float[]> candidates =
        Arrays.asList(
            new float[] {0.0f, 1.0f, 0.0f}, // Orthogonal - similarity 0
            new float[] {1.0f, 0.0f, 0.0f}, // Identical - similarity 1
            new float[] {-1.0f, 0.0f, 0.0f}); // Opposite - similarity -1

    List<Double> similarities = EmbeddingConverter.calculateSimilarities(query, candidates);

    assertThat(similarities).hasSize(3);
    assertThat(similarities.get(0)).isCloseTo(0.0, within(0.0001));
    assertThat(similarities.get(1)).isCloseTo(1.0, within(0.0001));
    assertThat(similarities.get(2)).isCloseTo(-1.0, within(0.0001));
  }

  @Test
  void testToDoubleArray() {
    float[] floatArray = {1.0f, 2.5f, 3.7f};

    double[] doubleArray = EmbeddingConverter.toDoubleArray(floatArray);

    assertThat(doubleArray).hasSize(3);
    assertThat(doubleArray[0]).isCloseTo(1.0, within(0.0001));
    assertThat(doubleArray[1]).isCloseTo(2.5, within(0.0001));
    assertThat(doubleArray[2]).isCloseTo(3.7, within(0.0001));
  }

  @Test
  void testToFloatArray() {
    double[] doubleArray = {1.0, 2.5, 3.7};

    float[] floatArray = EmbeddingConverter.toFloatArray(doubleArray);

    assertThat(floatArray).hasSize(3);
    assertThat(floatArray[0]).isCloseTo(1.0f, within(0.0001f));
    assertThat(floatArray[1]).isCloseTo(2.5f, within(0.0001f));
    assertThat(floatArray[2]).isCloseTo(3.7f, within(0.0001f));
  }

  @Test
  void extractTexts_fromEmbeddingRequest_returnsInputInstructions() {
    EmbeddingRequest request = EmbeddingConverter.createRequest(List.of("alpha", "beta"));

    List<String> extracted = EmbeddingConverter.extractTexts(request);

    assertThat(extracted).containsExactly("alpha", "beta");
  }

  @Test
  void toEmbedding_copiesInputVectorAndPreservesIndex() {
    float[] original = {0.25f, 0.75f};

    Embedding embedding = EmbeddingConverter.toEmbedding(original, 3);
    original[0] = 99.0f;

    assertThat(embedding.getIndex()).isEqualTo(3);
    assertThat(embedding.getOutput()).containsExactly(0.25f, 0.75f);
  }

  @Test
  void contentEmbeddingConversion_roundTrip_preservesValues() {
    float[] vector = {0.1f, -0.2f, 0.3f};

    ContentEmbedding contentEmbedding = EmbeddingConverter.toContentEmbedding(vector);
    float[] roundTrippedVector = EmbeddingConverter.extractEmbedding(contentEmbedding);
    Embedding springEmbedding = EmbeddingConverter.toEmbedding(contentEmbedding, 2);
    ContentEmbedding fromSpringEmbedding = EmbeddingConverter.toContentEmbedding(springEmbedding);

    assertThat(contentEmbedding.values()).contains(List.of(0.1f, -0.2f, 0.3f));
    assertThat(roundTrippedVector).containsExactly(0.1f, -0.2f, 0.3f);
    assertThat(springEmbedding.getIndex()).isEqualTo(2);
    assertThat(springEmbedding.getOutput()).containsExactly(0.1f, -0.2f, 0.3f);
    assertThat(fromSpringEmbedding).isEqualTo(contentEmbedding);
  }

  @Test
  void embedContentResponseConversion_roundTripAndOutOfOrderIndices_preservesBatchOrder() {
    float[] vec0 = {1.0f, 2.0f};
    float[] vec1 = {3.0f, 4.0f};
    EmbeddingResponse outOfOrderSpringResponse =
        new EmbeddingResponse(List.of(new Embedding(vec1, 1), new Embedding(vec0, 0)));

    EmbedContentResponse adkResponse =
        EmbeddingConverter.toEmbedContentResponse(outOfOrderSpringResponse);
    EmbeddingResponse roundTrippedSpring = EmbeddingConverter.fromEmbedContentResponse(adkResponse);
    List<float[]> roundTrippedVectors = EmbeddingConverter.extractEmbeddings(roundTrippedSpring);

    assertThat(adkResponse.embeddings()).isPresent();
    assertThat(adkResponse.embeddings().get()).hasSize(2);
    assertThat(roundTrippedSpring.getResults()).hasSize(2);
    assertThat(roundTrippedSpring.getResults().get(0).getIndex()).isEqualTo(0);
    assertThat(roundTrippedSpring.getResults().get(1).getIndex()).isEqualTo(1);
    assertThat(roundTrippedVectors.get(0)).containsExactly(1.0f, 2.0f);
    assertThat(roundTrippedVectors.get(1)).containsExactly(3.0f, 4.0f);
  }

  @Test
  void embeddingConversions_emptyOrAbsentFields_returnEmptyOutputs() {
    EmbeddingRequest nullInstructionsRequest = new EmbeddingRequest(null, null);
    ContentEmbedding emptyContentEmbedding = ContentEmbedding.builder().build();
    EmbedContentResponse emptyAdkResponse = EmbedContentResponse.builder().build();

    List<String> extractedTexts = EmbeddingConverter.extractTexts(nullInstructionsRequest);
    float[] extractedVector = EmbeddingConverter.extractEmbedding(emptyContentEmbedding);
    EmbeddingResponse convertedResponse =
        EmbeddingConverter.fromEmbedContentResponse(emptyAdkResponse);

    assertThat(extractedTexts).isEmpty();
    assertThat(extractedVector).isEmpty();
    assertThat(convertedResponse.getResults()).isEmpty();
  }
}
