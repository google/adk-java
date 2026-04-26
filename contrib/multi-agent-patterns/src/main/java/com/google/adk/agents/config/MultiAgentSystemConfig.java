package com.google.adk.agents.config;

import com.google.adk.agents.base.AgentConfig;
import com.google.adk.agents.base.AgentConfigsProvider;
import com.google.adk.agents.base.AppConfig;
import com.google.adk.agents.base.AppConfigProvider;
import com.google.adk.agents.base.DefaultContentConfigProvider;
import com.google.adk.agents.base.serialize.YamlMapperProvider;
import com.google.adk.agents.base.serialize.YamlSerializer;
import com.google.adk.agents.code.CodeRootAgent;
import com.google.adk.agents.plugins.RateLimitPlugin;
import com.google.adk.agents.report.ReportRootAgent;
import com.google.adk.agents.traveler.TravelerRootAgent;
import com.google.adk.plugins.PluginManager;
import com.google.adk.web.AdkWebServer;
import com.google.adk.web.AgentLoader;
import com.google.adk.web.AgentStaticLoader;
import com.google.genai.types.GenerateContentConfig;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import(AdkWebServer.class)
public class MultiAgentSystemConfig {

  private static final Logger LOGGER = Logger.getLogger(MultiAgentSystemConfig.class.getName());

  @Value("${adk.plugins.rate.limit.requests.per.minute:5.0}")
  private double requestsPerMinute;

  @Value("${adk.retry.max.attempts:3}")
  private int maxAttempts;

  @Value("${adk.retry.base.delay:2}")
  private int baseDelay;

  @Value("${adk.content.config.temperature:0.1}")
  private double temperature;

  @Value("${adk.content.config.max.output.tokens:2000}")
  private int maxTokens;

  @Bean
  YamlSerializer yamlSerializer() {
    YamlMapperProvider mapper = new YamlMapperProvider();
    return new YamlSerializer(mapper.get());
  }

  @Bean
  Map<String, AgentConfig> agentConfigs() {
    return new AgentConfigsProvider(yamlSerializer()).get();
  }

  @Bean
  ContentConfigContext contentConfigContext() {
    return new ContentConfigContext(temperature, maxTokens, maxAttempts, baseDelay);
  }

  @Bean
  GenerateContentConfig defaultContentConfig() {
    return new DefaultContentConfigProvider(contentConfigContext()).get();
  }

  @Bean
  AppConfig appConfig() {
    return new AppConfigProvider(plugins(), defaultContentConfig(), agentConfigs()).get();
  }

  @Bean
  RateLimitPlugin rateLimitPlugin() {
    String msg = "ADK Config: Creating RateLimitPlugin with %s RPM";
    LOGGER.info(String.format(msg, this.requestsPerMinute));
    double requestsPerSecond = this.requestsPerMinute / 60.0;
    return new RateLimitPlugin(requestsPerSecond);
  }

  @Bean
  PluginManager plugins() {
    LOGGER.info("ADK Config: Wiring global plugins list [RateLimit]");
    return new PluginManager(List.of(rateLimitPlugin()));
  }

  @Bean
  AgentLoader agentLoader() {
    return new AgentStaticLoader( //
        new CodeRootAgent(appConfig()).get(), //
        new ReportRootAgent(appConfig()).get(), //
        new TravelerRootAgent(appConfig()).get());
  }

  public record ContentConfigContext(
      double temperature, int maxOutputTokens, int maxRetryAttempts, int baseDelay) {}
}
