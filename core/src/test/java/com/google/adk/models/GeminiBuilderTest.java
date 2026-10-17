/*
 * Copyright 2026 Google LLC
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
package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

/** Tests the public Gemini construction paths without contacting a provider. */
@RunWith(JUnit4.class)
public final class GeminiBuilderTest {

  private static final String MODEL = "gemini-test-model";
  private static final HttpRetryOptions RETRY_OPTIONS =
      HttpRetryOptions.builder().attempts(3).httpStatusCodes(429, 503).build();
  private static final VertexCredentials VERTEX_CREDENTIALS =
      VertexCredentials.builder().project("test-project").location("us-central1").build();

  private Client client;
  private Client.Builder clientBuilder;
  private MockedStatic<Client> clientFactory;

  @Before
  public void setUp() {
    client = mock(Client.class);
    clientBuilder = mock(Client.Builder.class, RETURNS_SELF);
    when(clientBuilder.build()).thenReturn(client);
    clientFactory = mockStatic(Client.class);
    clientFactory.when(Client::builder).thenReturn(clientBuilder);
  }

  @After
  public void tearDown() {
    clientFactory.close();
  }

  @Test
  public void builder_withApiKey_passesRetryOptionsAndPrefersApiKeyOverVertexCredentials() {
    Gemini gemini =
        Gemini.builder()
            .modelName(MODEL)
            .apiKey("test-key")
            .vertexCredentials(VERTEX_CREDENTIALS)
            .retryOptions(RETRY_OPTIONS)
            .build();

    assertThat(gemini.model()).isEqualTo(MODEL);
    verify(clientBuilder).apiKey("test-key");
    verify(clientBuilder, never()).project("test-project");
    verify(clientBuilder, never()).location("us-central1");
    assertThat(capturedHttpOptions().retryOptions()).hasValue(RETRY_OPTIONS);
  }

  @Test
  public void builder_withVertexCredentials_passesRetryOptionsAndCredentials() {
    Gemini gemini =
        Gemini.builder()
            .modelName(MODEL)
            .vertexCredentials(VERTEX_CREDENTIALS)
            .retryOptions(RETRY_OPTIONS)
            .build();

    assertThat(gemini.model()).isEqualTo(MODEL);
    verify(clientBuilder).project("test-project");
    verify(clientBuilder).location("us-central1");
    assertThat(capturedHttpOptions().retryOptions()).hasValue(RETRY_OPTIONS);
  }

  @Test
  public void builder_withDefaultClient_passesRetryOptions() {
    Gemini gemini = Gemini.builder().modelName(MODEL).retryOptions(RETRY_OPTIONS).build();

    assertThat(gemini.model()).isEqualTo(MODEL);
    assertThat(capturedHttpOptions().retryOptions()).hasValue(RETRY_OPTIONS);
  }

  @Test
  public void builder_withoutRetryOptions_leavesSdkRetryOptionsUnset() {
    Gemini.builder().modelName(MODEL).apiKey("test-key").build();

    HttpOptions httpOptions = capturedHttpOptions();
    assertThat(httpOptions.retryOptions()).isEmpty();
    assertThat(httpOptions.headers().get()).containsKey("x-goog-api-client");
    assertThat(httpOptions.headers().get()).containsKey("user-agent");
  }

  @Test
  public void builder_withExplicitClient_doesNotCreateOrReconfigureClient() {
    Gemini gemini =
        Gemini.builder()
            .modelName(MODEL)
            .apiClient(client)
            .apiKey("test-key")
            .vertexCredentials(VERTEX_CREDENTIALS)
            .retryOptions(RETRY_OPTIONS)
            .build();

    assertThat(gemini.model()).isEqualTo(MODEL);
    clientFactory.verifyNoInteractions();
    verifyNoInteractions(client);
  }

  @Test
  public void constructor_withApiKey_preservesClientConfiguration() {
    Gemini gemini = new Gemini(MODEL, "test-key");

    assertThat(gemini.model()).isEqualTo(MODEL);
    verify(clientBuilder).apiKey("test-key");
    assertThat(capturedHttpOptions().retryOptions()).isEmpty();
  }

  @Test
  public void constructor_withVertexCredentials_preservesClientConfiguration() {
    Gemini gemini = new Gemini(MODEL, VERTEX_CREDENTIALS);

    assertThat(gemini.model()).isEqualTo(MODEL);
    verify(clientBuilder).project("test-project");
    verify(clientBuilder).location("us-central1");
    assertThat(capturedHttpOptions().retryOptions()).isEmpty();
  }

  @Test
  public void constructor_withExplicitClient_doesNotCreateAnotherClient() {
    Gemini gemini = new Gemini(MODEL, client);

    assertThat(gemini.model()).isEqualTo(MODEL);
    clientFactory.verifyNoInteractions();
    verifyNoInteractions(client);
  }

  @Test
  public void constructors_withNullArguments_rejectInsteadOfUsingDefaultClient() {
    assertThrows(NullPointerException.class, () -> new Gemini(MODEL, (Client) null));
    assertThrows(NullPointerException.class, () -> new Gemini(MODEL, (String) null));
    assertThrows(NullPointerException.class, () -> new Gemini(MODEL, (VertexCredentials) null));
    clientFactory.verifyNoInteractions();
  }

  @Test
  public void builder_withoutModelName_rejectsBeforeCreatingClient() {
    assertThrows(NullPointerException.class, () -> Gemini.builder().apiKey("test-key").build());
    clientFactory.verifyNoInteractions();
  }

  private HttpOptions capturedHttpOptions() {
    ArgumentCaptor<HttpOptions> httpOptions = ArgumentCaptor.forClass(HttpOptions.class);
    verify(clientBuilder).httpOptions(httpOptions.capture());
    return httpOptions.getValue();
  }
}
