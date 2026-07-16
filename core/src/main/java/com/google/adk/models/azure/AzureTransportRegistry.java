package com.google.adk.models.azure;

import java.util.List;

/** Ordered registry of Azure transports; first {@link AzureTransport#supports} match wins. */
public final class AzureTransportRegistry {

  private static final List<AzureTransport> TRANSPORTS =
      List.of(
          new AzureRealtimeTranslateTransport(),
          new AzureRealtimeTransport(),
          new AzureRestTransport());

  private AzureTransportRegistry() {}

  public static AzureTransport select(String modelName) {
    for (AzureTransport transport : TRANSPORTS) {
      if (transport.supports(modelName)) {
        return transport;
      }
    }
    return new AzureRestTransport();
  }
}
