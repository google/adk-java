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

package com.google.adk.web.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.web.config.AdkWebCorsProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

class WebSocketConfigTest {

  @Test
  void defaultWebSocketConfigurationDoesNotAllowArbitraryOrigins() {
    LiveWebSocketHandler handler = mock(LiveWebSocketHandler.class);
    WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
    WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
    when(registry.addHandler(handler, "/run_live")).thenReturn(registration);

    new WebSocketConfig(handler, new AdkWebCorsProperties(null, null, null, null, false, 0))
        .registerWebSocketHandlers(registry);

    verify(registration, never()).setAllowedOrigins("*");
  }

  @Test
  void explicitCorsOriginsAreAppliedToWebSocketEndpoint() {
    LiveWebSocketHandler handler = mock(LiveWebSocketHandler.class);
    WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
    WebSocketHandlerRegistration registration = mock(WebSocketHandlerRegistration.class);
    when(registry.addHandler(handler, "/run_live")).thenReturn(registration);

    new WebSocketConfig(
            handler,
            new AdkWebCorsProperties(null, List.of("http://localhost:3000"), null, null, false, 0))
        .registerWebSocketHandlers(registry);

    verify(registration).setAllowedOrigins("http://localhost:3000");
  }
}
