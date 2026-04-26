package com.google.adk.agents.base;

import static com.google.api.client.util.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableMap.copyOf;
import static com.google.common.collect.Maps.fromProperties;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

import com.google.adk.plugins.PluginManager;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.GenerateContentConfig;
import jakarta.inject.Provider;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;

public class AppConfigProvider implements Provider<AppConfig> {

  private static final String SRC = "application.properties";

  private static final Logger LOGGER = getLogger(AppConfigProvider.class);

  public AppConfigProvider(
      PluginManager plugins,
      GenerateContentConfig contentConfig,
      Map<String, AgentConfig> agentConfigs) {
    this.plugins = checkNotNull(plugins, "plugins");
    this.contentConfig = checkNotNull(contentConfig, "contentConfig");
    this.agentConfigs = copyOf(agentConfigs);
  }

  private final PluginManager plugins;
  private final GenerateContentConfig contentConfig;
  private final ImmutableMap<String, AgentConfig> agentConfigs;

  @Override
  public AppConfig get() {
    Properties props = loadProperties(SRC);
    AppConfig.Builder builder = AppConfig.builder();
    builder.withContentConfig(contentConfig);
    builder.withPlugins(plugins);
    builder.withAgentConfigs(agentConfigs);
    builder.withProperties(fromProperties(props));
    return builder.build();
  }

  private Properties loadProperties(String resource) {
    Properties props = new Properties();
    try (InputStream in = AppConfigProvider.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        LOGGER.info(format("%s not found on classpath", resource));
        return new Properties();
      }
      props.load(in);
      return props;
    } catch (Throwable e) {
      e.printStackTrace();
      throw new IllegalStateException(e);
    }
  }
}
