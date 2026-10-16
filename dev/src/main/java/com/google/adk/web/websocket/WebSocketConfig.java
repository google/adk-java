/*
 * Copyright 2025 Google LLC
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

import com.google.adk.web.config.AdkWebCorsProperties;
import com.google.adk.web.config.ForwardedSameOrigin;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.OriginHandshakeInterceptor;

/** Configuration class for WebSocket handling. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final LiveWebSocketHandler liveWebSocketHandler;
  private final AdkWebCorsProperties corsProperties;

  @Autowired
  public WebSocketConfig(
      LiveWebSocketHandler liveWebSocketHandler, AdkWebCorsProperties corsProperties) {
    this.liveWebSocketHandler = liveWebSocketHandler;
    this.corsProperties = corsProperties;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(liveWebSocketHandler, "/run_live")
        .addInterceptors(new ForwardedAwareOriginInterceptor(corsProperties.origins()))
        // The interceptor checks origins; the registry's own check ignores forwarded headers.
        .setAllowedOrigins("*");
  }

  /** Origin check for the handshake that also accepts the URL a reverse proxy reports. */
  private static final class ForwardedAwareOriginInterceptor extends OriginHandshakeInterceptor {

    ForwardedAwareOriginInterceptor(List<String> allowedOrigins) {
      super(allowedOrigins);
    }

    @Override
    public boolean beforeHandshake(
        ServerHttpRequest request,
        ServerHttpResponse response,
        WebSocketHandler wsHandler,
        Map<String, Object> attributes)
        throws Exception {
      return ForwardedSameOrigin.matches(request)
          || super.beforeHandshake(request, response, wsHandler, attributes);
    }
  }
}
