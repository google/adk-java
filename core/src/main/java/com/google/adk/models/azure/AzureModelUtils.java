package com.google.adk.models.azure;

/** Model-name helpers shared by Azure transports (no dependency on {@code AzureBaseLM}). */
public final class AzureModelUtils {

  private AzureModelUtils() {}

  /** Returns true if the given model name is GPT Realtime Translate. */
  public static boolean isTranslateModel(String modelName) {
    if (modelName == null) {
      return false;
    }
    return modelName.toLowerCase().contains("realtime-translate");
  }

  /** Returns true if the given model name indicates an Azure Realtime voice-agent model. */
  public static boolean isRealtimeModel(String modelName) {
    if (modelName == null) {
      return false;
    }
    return modelName.toLowerCase().contains("realtime") && !isTranslateModel(modelName);
  }
}
