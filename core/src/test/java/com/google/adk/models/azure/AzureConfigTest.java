package com.google.adk.models.azure;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AzureConfigTest {

  @Test
  public void normalizeTranslateWebSocketUrl_buildsGaFormat() {
    String normalized =
        AzureConfig.normalizeTranslateWebSocketUrl(
            "https://my-resource.openai.azure.com/openai/realtime/translations?api-version=2024-10-01",
            "gpt-realtime-translate");

    assertThat(normalized)
        .isEqualTo(
            "wss://my-resource.openai.azure.com/openai/v1/realtime/translations?model="
                + "gpt-realtime-translate");
  }

  @Test
  public void normalizeTranslateWebSocketUrl_rejectsMissingHost() {
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> AzureConfig.normalizeTranslateWebSocketUrl("not-a-url", "model"));

    assertThat(error.getMessage()).contains("Invalid translate endpoint");
  }

  @Test
  public void maskWebSocketUrl_redactsQueryParams() {
    String masked =
        AzureConfig.maskWebSocketUrl(
            "wss://my-resource.openai.azure.com/openai/v1/realtime?model=secret-deployment");

    assertThat(masked).isEqualTo("my-resource.openai.azure.com/openai/v1/realtime");
    assertThat(masked).doesNotContain("secret-deployment");
  }
}
