package com.google.adk.plugins.bigquery;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class FakeAnalyticsWriter implements AnalyticsWriter {
  boolean ready = false;
  List<ImmutableMap<String, Object>> batch = new ArrayList<>();

  @Override
  public void start() throws IOException {
    batch.clear();
    ready = true;
  }

  @Override
  public void writeBatch(List<ImmutableMap<String, Object>> inputBatch) {
    batch.addAll(inputBatch);
  }

  @Override
  public boolean isReady() {
    return ready;
  }

  @Override
  public void close() {
    ready = false;
  }
}
