package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public final class LlmRegistryTest {

  /**
   * Gemini is registered by default, but the registration fails when the default genai Client can't
   * find the necessary environment variables. This used to break LlmRegistry class loading.
   *
   * <p>At the very least, would should throw an exception at runtime and not at class loading time.
   */
  @Test
  public void geminiLlm_unsupportedModel_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> LlmRegistry.getLlm("gemini-1000-super-pro"));
  }

  /** Tests that a custom LLM can be registered and retrieved. */
  @Test
  public void customLlm_success() {
    var mockLlm = Mockito.mock(BaseLlm.class);
    Mockito.when(mockLlm.model()).thenReturn("custom-1.0");
    LlmRegistry.registerLlm("custom-.*", modelName -> mockLlm);

    try {
      BaseLlm llm = LlmRegistry.getLlm("custom-1.0");
      assertThat(llm.model()).isEqualTo("custom-1.0");
    } finally {
      LlmRegistry.registerTestLlm("custom-.*", null);
    }
  }
}
