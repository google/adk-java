/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.adk.tools.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

class McpServerLogConsumer implements Consumer<LoggingMessageNotification> {

  private static final Logger LOG = LoggerFactory.getLogger(McpServerLogConsumer.class);

  @Override
  public void accept(LoggingMessageNotification notif) {
    LOG.atLevel(convert(notif.level())).log("{}", notif.data());
  }

  private Level convert(McpSchema.LoggingLevel level) {
    return switch (level) {
      case DEBUG -> Level.DEBUG;
      case INFO, NOTICE -> Level.INFO;
      case WARNING -> Level.WARN;
      case ERROR, CRITICAL, ALERT, EMERGENCY -> Level.ERROR;
    };
  }
}
