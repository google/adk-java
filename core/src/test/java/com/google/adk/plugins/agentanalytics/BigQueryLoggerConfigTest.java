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

package com.google.adk.plugins.agentanalytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BigQueryLoggerConfigTest {

  private static BigQueryLoggerConfig.Builder validBuilder() {
    return BigQueryLoggerConfig.builder().projectId("test-project");
  }

  @Test
  public void build_validConfig_succeeds() {
    BigQueryLoggerConfig config = validBuilder().build();
    assertEquals("test-project", config.projectId());
    assertEquals(1, config.batchSize());
    assertEquals(10000, config.queueMaxSize());
  }

  @Test
  public void build_nonPositiveBatchSize_throws() {
    BigQueryLoggerConfig.Builder builder = validBuilder().batchSize(0);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_nonPositiveQueueMaxSize_throws() {
    BigQueryLoggerConfig.Builder builder = validBuilder().queueMaxSize(0);
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void build_nonPositiveMaxContentLength_throws() {
    BigQueryLoggerConfig.Builder builder = validBuilder().maxContentLength(-1);
    assertThrows(IllegalArgumentException.class, builder::build);
  }
}
