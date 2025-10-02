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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;

/** Test to understand the Spring AI EmbeddingModel API. */
class EmbeddingApiTest {

  @Test
  void testEmbeddingModelApiMethods() {
    EmbeddingModel mockModel = mock(EmbeddingModel.class);

    // Test the simple embed methods
    when(mockModel.embed("test")).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
    when(mockModel.embed(any(List.class))).thenReturn(List.of(new float[] {0.1f, 0.2f, 0.3f}));

    // Test dimensions
    when(mockModel.dimensions()).thenReturn(384);

    // Skip EmbeddingResponse mocking due to final class limitations

    // Test the methods
    float[] result1 = mockModel.embed("test");
    List<float[]> result2 = mockModel.embed(List.of("test1", "test2"));
    int dims = mockModel.dimensions();

    assertThat(result1).hasSize(3);
    assertThat(result1).containsExactly(0.1f, 0.2f, 0.3f);
    assertThat(result2).hasSize(1);
    assertThat(dims).isEqualTo(384);

    // Test request creation
    EmbeddingRequest request = new EmbeddingRequest(List.of("test"), null);
    assertThat(request).isNotNull();
    assertThat(request.getInstructions()).containsExactly("test");
  }
}
