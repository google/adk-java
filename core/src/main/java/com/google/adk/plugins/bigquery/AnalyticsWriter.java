package com.google.adk.plugins.bigquery;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.List;

/** Agent analytics writer interface. */
public interface AnalyticsWriter {
  void start() throws IOException;

  boolean isReady();

  void writeBatch(List<ImmutableMap<String, Object>> batch);

  void close();
}
