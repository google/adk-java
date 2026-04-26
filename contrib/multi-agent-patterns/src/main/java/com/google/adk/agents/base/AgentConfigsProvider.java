package com.google.adk.agents.base;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.and;
import static com.google.common.collect.ImmutableMap.copyOf;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.toMap;
import static com.google.common.io.ByteSource.wrap;
import static com.google.common.io.ByteStreams.toByteArray;
import static com.google.common.io.MoreFiles.fileTraverser;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.Strings.CS;

import com.google.adk.agents.base.serialize.YamlSerializer;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import jakarta.inject.Provider;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AgentConfigsProvider implements Provider<ImmutableMap<String, AgentConfig>> {

  private static final String BASE = "src/main/resources";
  private static final String SRC = "/agent-configs";
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
      String root = BASE + SRC;
      Map<String, ByteSource> resources = resources(root);
      Map<String, AgentConfig> map = new HashMap<>();
      for (String key : resources.keySet()) {
        ByteSource bytes = resources.get(key);
        AgentConfig config = yaml.read(bytes, AgentConfig.class);
        map.put(normalize(key, SRC), config);
      }
      String msg = "load agent configurations [total = %s] from -> %s";
      log.info(String.format(msg, map.size(), root));
      return copyOf(map);
    } catch (Throwable e) {
      // e.printStackTrace();
      throw new IllegalStateException(e);
    }
  }

  private ImmutableMap<String, ByteSource> resources(String dir) {
    Path root = Paths.get(dir);
    // Create the traverser and perform a breadth-first walk
    List<Path> paths = newArrayList(fileTraverser().breadthFirst(root));
    Predicate<String> pp = pathPredicate(SRC);
    List<String> filtered = paths.stream().map(p -> normalize(p)).filter(pp).collect(toList());
    return copyOf(toMap(filtered, p -> bytes(p)));
  }

  private Predicate<String> pathPredicate(String dir) {
    Predicate<String> startsWith = p -> p.startsWith(dir);
    Predicate<String> endsWith = p -> p.endsWith(EXT);
    return and(startsWith, endsWith);
  }

  private ByteSource bytes(String location) {
    try (InputStream in = getClass().getResourceAsStream(location)) {
      return wrap(toByteArray(in));
    } catch (Throwable e) {
      // e.printStackTrace();
      throw new IllegalStateException(e);
    }
  }

  private String normalize(Path path) {
    String s = path.toString();
    s = s.replace("\\", "/");
    return CS.removeStart(s, BASE);
  }

  private String normalize(String s, String dir) {
    String normalized = CS.removeStart(s, dir + "/");
    normalized = CS.removeEnd(normalized, EXT);
    normalized = CS.replace(normalized, "-", ".");
    normalized = CS.replace(normalized, "_", ".");
    normalized = CS.replace(normalized, "/", ".");
    return normalized;
  }
}
