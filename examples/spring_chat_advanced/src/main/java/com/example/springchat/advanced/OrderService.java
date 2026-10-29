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

import com.google.adk.tools.Annotations.Schema;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * A Spring service whose methods {@link AgentConfig} exposes to the agent as tools. A real app
 * would back it with a database or another service.
 */
@Service
public class OrderService {

  private record Order(String status, String item, String deliveryEstimate) {}

  private static final ImmutableMap<String, Order> ORDERS =
      ImmutableMap.of(
          "1001", new Order("shipped", "mechanical keyboard", "2 days"),
          "1002", new Order("processing", "USB-C hub", "5 days"),
          "1003", new Order("delivered", "monitor stand", "already delivered"));

  /**
   * Returns the status and item of the order, or an {@code error} entry if there is no such order.
   */
  @Schema(description = "Looks up the status and item of an order.")
  public Map<String, Object> getOrderStatus(
      @Schema(name = "orderId", description = "The order number, for example 1001.")
          String orderId) {
    Order order = ORDERS.get(orderId);
    if (order == null) {
      return ImmutableMap.of("error", "No order with this number.");
    }
    return ImmutableMap.of("orderId", orderId, "status", order.status(), "item", order.item());
  }

  /**
   * Returns the delivery estimate of the order, or an {@code error} entry if there is no such
   * order.
   */
  @Schema(description = "Returns the delivery estimate of an order.")
  public Map<String, Object> getDeliveryEstimate(
      @Schema(name = "orderId", description = "The order number, for example 1001.")
          String orderId) {
    Order order = ORDERS.get(orderId);
    if (order == null) {
      return ImmutableMap.of("error", "No order with this number.");
    }
    return ImmutableMap.of("orderId", orderId, "deliveryEstimate", order.deliveryEstimate());
  }
}
