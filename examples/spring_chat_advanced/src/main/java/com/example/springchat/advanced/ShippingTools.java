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

package com.example.springchat.advanced;

import com.google.adk.models.springai.ToolConverter;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** Spring AI {@code @Tool} methods that {@link AgentConfig} gives the agent as ADK tools. */
@Component
class ShippingTools {

  /** Session state key that {@code getTracking} sets and the agent's instruction reads. */
  static final String LAST_TRACKED_ORDER = "lastTrackedOrder";

  private static final ImmutableMap<String, String> TRACKING_NUMBERS =
      ImmutableMap.of("1001", "DHL JD014600001001", "1003", "UPS 1Z999AA10000001003");

  /**
   * Returns the tracking number of a shipped order and records the order in the ADK session. The
   * ADK tool context reaches Spring AI tools under {@link ToolConverter#ADK_TOOL_CONTEXT_KEY}.
   */
  @Tool(description = "Returns the carrier and tracking number of a shipped order.")
  Map<String, Object> getTracking(
      @ToolParam(description = "The order number, for example 1001.") String orderId,
      ToolContext toolContext) {
    String trackingNumber = TRACKING_NUMBERS.get(orderId);
    if (trackingNumber == null) {
      return ImmutableMap.of("error", "This order has no tracking number yet.");
    }
    if (toolContext.getContext().get(ToolConverter.ADK_TOOL_CONTEXT_KEY)
        instanceof com.google.adk.tools.ToolContext adkToolContext) {
      adkToolContext.state().put(LAST_TRACKED_ORDER, orderId);
    }
    return ImmutableMap.of("orderId", orderId, "tracking", trackingNumber);
  }

  /** With {@code returnDirect}, ADK shows this result to the user without another model call. */
  @Tool(
      description = "Hands the conversation to a human support agent when the user asks for one.",
      returnDirect = true)
  String talkToHuman() {
    return "A human support agent will join this chat within 5 minutes.";
  }
}
