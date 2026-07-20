package com.google.adk.models.azure;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AzureModelUtilsTest {

  @Test
  public void transportSelectionOrder_prefersTranslateOverRealtime() {
    assertThat(AzureModelUtils.isTranslateModel("gpt-realtime-translate")).isTrue();
    assertThat(AzureModelUtils.isRealtimeModel("gpt-realtime-translate")).isFalse();
    assertThat(AzureModelUtils.isRealtimeModel("gpt-4o-realtime-preview")).isTrue();
    assertThat(AzureModelUtils.isRealtimeModel("gpt-4o")).isFalse();
  }

  @Test
  public void transportRegistry_selectsExpectedTransport() {
    assertThat(AzureTransportRegistry.select("gpt-realtime-translate"))
        .isInstanceOf(AzureRealtimeTranslateTransport.class);
    assertThat(AzureTransportRegistry.select("gpt-4o-realtime-preview"))
        .isInstanceOf(AzureRealtimeTransport.class);
    assertThat(AzureTransportRegistry.select("gpt-4o")).isInstanceOf(AzureRestTransport.class);
  }
}
