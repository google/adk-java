package com.google.adk.samples.stale.config;

public final class StaleBotSettings {

  private StaleBotSettings() {
    throw new UnsupportedOperationException("Configuration class cannot be instantiated");
  }

  // --- GitHub API Configuration ---

  public static final String GITHUB_BASE_URL = "https://api.github.com";

  // Critical: Fail fast if token is missing
  public static final String GITHUB_TOKEN = getEnv("GITHUB_TOKEN");

  public static final String OWNER = getEnv("OWNER");
  public static final String REPO = getEnv("REPO");
  public static final String LLM_MODEL_NAME = getEnv("LLM_MODEL_NAME");

  public static final String STALE_LABEL_NAME = "stale";
  public static final String REQUEST_CLARIFICATION_LABEL = "waiting on reporter";

  // --- THRESHOLDS IN HOURS ---

  public static final double STALE_HOURS_THRESHOLD = getDoubleEnv("STALE_HOURS_THRESHOLD");

  public static final double CLOSE_HOURS_AFTER_STALE_THRESHOLD =
      getDoubleEnv("CLOSE_HOURS_AFTER_STALE_THRESHOLD");

  // --- Performance Configuration ---

  public static final int CONCURRENCY_LIMIT = getIntEnv("CONCURRENCY_LIMIT");

  // --- GraphQL Query Limits ---

  public static final int GRAPHQL_COMMENT_LIMIT = getIntEnv("GRAPHQL_COMMENT_LIMIT");

  public static final int GRAPHQL_EDIT_LIMIT = getIntEnv("GRAPHQL_EDIT_LIMIT");

  public static final int GRAPHQL_TIMELINE_LIMIT = getIntEnv("GRAPHQL_TIMELINE_LIMIT");

  // --- Rate Limiting ---

  public static final double SLEEP_BETWEEN_CHUNKS = getDoubleEnv("SLEEP_BETWEEN_CHUNKS");

  private static String getEnv(String key) {
    return System.getenv(key);
  }

  private static int getIntEnv(String key) {
    return Integer.parseInt(getEnv(key));
  }

  private static double getDoubleEnv(String key) {
    return Double.parseDouble(getEnv(key));
  }
}
