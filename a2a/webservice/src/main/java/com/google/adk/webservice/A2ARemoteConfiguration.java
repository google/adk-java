package com.google.adk.webservice;

import com.google.adk.a2a.A2ASendMessageExecutor;
import com.google.adk.agents.BaseAgent;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the transport-only A2A webservice stack.
 *
 * <p>Importers must supply a {@link BaseAgent} bean. The agent remains opaque to this module so the
 * transport can be reused across applications.
 *
 * <p>TODO:
 *
 * <ul>
 *   <li>Expose discovery endpoints (agent card / extended card) so clients can fetch metadata
 *       directly.
 *   <li>Add optional remote-proxy wiring for cases where no local agent bean is available.
 * </ul>
 */
@Configuration
@ComponentScan(basePackages = "com.google.adk.webservice")
public class A2ARemoteConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(A2ARemoteConfiguration.class);
  private static final String DEFAULT_APP_NAME = "a2a-remote-service";
  private static final long DEFAULT_TIMEOUT_SECONDS = 15L;

  @Bean
  public A2ASendMessageExecutor a2aSendMessageExecutor(
      BaseAgent agent,
      @Value("${a2a.remote.appName:" + DEFAULT_APP_NAME + "}") String appName,
      @Value("${a2a.remote.timeoutSeconds:" + DEFAULT_TIMEOUT_SECONDS + "}") long timeoutSeconds) {
    logger.info(
        "Initializing A2A send message executor for appName {} with timeout {}s",
        appName,
        timeoutSeconds);
    return new A2ASendMessageExecutor(agent, appName, Duration.ofSeconds(timeoutSeconds));
  }
}
