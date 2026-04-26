package com.google.adk.agents.base;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.and;
import static com.google.common.reflect.ClassPath.from;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.adk.agents.base.serialize.YamlSerializer;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ResourceInfo;
import jakarta.inject.Provider;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentConfigsProvider implements Provider<ImmutableMap<String, AgentConfig>> {

  private static final String CONFIG = "agent-configs";
  private static final String EXT = ".yaml";

  private static final Logger log = LoggerFactory.getLogger(AgentConfigsProvider.class);

  public AgentConfigsProvider() {
    this(new YamlSerializer());
  }

  public AgentConfigsProvider(YamlSerializer yaml) {
    this.yaml = checkNotNull(yaml, "yaml");
  }

  private final YamlSerializer yaml;

  @Override
  public ImmutableMap<String, AgentConfig> get() {
    try {
      Map<String, ByteSource> resources = classpathResources(CONFIG);
      ImmutableMap.Builder<String, AgentConfig> builder = ImmutableMap.builder();
      for (Map.Entry<String, ByteSource> entry : resources.entrySet()) {
        AgentConfig config = yaml.read(entry.getValue(), AgentConfig.class);
        builder.put(normalize(entry.getKey(), CONFIG), config);
      }
      ImmutableMap<String, AgentConfig> map = builder.build();
      String msg = "Loaded agent configurations [total = %s] from classpath directory -> %s";
      log.info(String.format(msg, map.size(), CONFIG));
      return map;
    } catch (JsonProcessingException e) { // Specific for YAML parsing errors
      throw new IllegalStateException("Failed to parse agent configuration YAML", e);
    } catch (IOException e) { // For other I/O errors
      throw new IllegalStateException("Failed to load agent configurations from classpath", e);
    } catch (Exception e) { // Catch broader exceptions, but not Throwable
      String msg = "An unexpected error occurred while loading agent configurations";
      throw new IllegalStateException(msg, e);
    }
  }

  private ImmutableMap<String, ByteSource> classpathResources(String dir) throws IOException {
    ClassPath classPath = from(getClass().getClassLoader());
    ImmutableMap.Builder<String, ByteSource> builder = ImmutableMap.builder();
    Predicate<ResourceInfo> filter = pathPredicate(dir);
    for (ResourceInfo info : classPath.getResources()) {
      if (filter.apply(info)) {
        builder.put(info.getResourceName(), info.asByteSource());
      }
    }
    return builder.build();
  }

  private Predicate<ResourceInfo> pathPredicate(String dir) {
    String prefix = dir.endsWith("/") ? dir : dir + "/";
    Predicate<ResourceInfo> startsWith = p -> p.getResourceName().startsWith(prefix);
    Predicate<ResourceInfo> endsWith = p -> p.getResourceName().endsWith(EXT);
    return and(startsWith, endsWith);
  }

  private String normalize(String s, String dir) {
    return s.replaceFirst("^" + Pattern.quote(dir + "/"), "")
        .replaceFirst(Pattern.quote(EXT) + "$", "")
        .replace("-", ".")
        .replace("_", ".")
        .replace("/", ".");
  }
}
