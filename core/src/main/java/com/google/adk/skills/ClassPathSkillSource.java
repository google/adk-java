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

package com.google.adk.skills;

import static com.google.adk.skills.SkillSourceException.RESOURCE_NOT_FOUND;
import static com.google.adk.skills.SkillSourceException.SKILL_LOAD_ERROR;
import static com.google.adk.skills.SkillSourceException.SKILL_NOT_FOUND;
import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ResourceInfo;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads skills from file-based classpaths and {@code jar:} roots exposed by URL class loaders.
 *
 * <p>The protected {@link ResourceInfo}-based methods retain the original Guava discovery behavior.
 * The public {@link SkillSource} methods additionally discover JAR roots, including executable JARs
 * whose URL handlers implement {@link JarURLConnection}.
 */
public final class ClassPathSkillSource extends AbstractSkillSource<ResourceInfo> {

  private static final Splitter PATH_SPLITTER = Splitter.on('/');

  private final ResourceSource<ResourceInfo> legacySource;
  private final ResourceSource<ResourceHandle> delegate;

  /**
   * Creates a new {@link ClassPathSkillSource} that loads skills from the given base resource path
   * using the current thread's context class loader.
   *
   * @param baseResourcePath the base classpath path to scan for skills (e.g., "skills/")
   */
  public ClassPathSkillSource(String baseResourcePath) {
    this(
        baseResourcePath,
        Objects.requireNonNullElse(
            Thread.currentThread().getContextClassLoader(),
            ClassPathSkillSource.class.getClassLoader()));
  }

  /**
   * Creates a new {@link ClassPathSkillSource} that loads skills from the given base resource path
   * using the specified {@link ClassLoader}.
   *
   * @param baseResourcePath the base classpath path to scan for skills
   * @param classLoader the class loader to use for scanning resources
   */
  public ClassPathSkillSource(String baseResourcePath, ClassLoader classLoader) {
    String normalizedPath = normalizePath(baseResourcePath);
    Single<ImmutableList<ResourceInfo>> nativeResources =
        Single.fromCallable(() -> scanNativeResources(normalizedPath, classLoader)).cache();
    this.legacySource =
        new ResourceSource<>(
            normalizedPath,
            nativeResources,
            ResourceInfo::getResourceName,
            ResourceInfo::asByteSource);
    this.delegate =
        new ResourceSource<>(
            normalizedPath,
            nativeResources
                .map(resources -> supplementJarResources(normalizedPath, classLoader, resources))
                .cache(),
            ResourceHandle::name,
            ResourceHandle::content);
  }

  @Override
  public Single<ImmutableMap<String, Frontmatter>> listFrontmatters() {
    return delegate.listFrontmatters();
  }

  @Override
  public Single<Frontmatter> loadFrontmatter(String skillName) {
    return delegate.loadFrontmatter(skillName);
  }

  @Override
  public Single<String> loadInstructions(String skillName) {
    return delegate.loadInstructions(skillName);
  }

  @Override
  public Single<ByteSource> loadResource(String skillName, String resourcePath) {
    return delegate.loadResource(skillName, resourcePath);
  }

  @Override
  public Single<ImmutableList<String>> listResources(String skillName, String resourceDirectory) {
    return delegate.listResources(skillName, resourceDirectory);
  }

  @Override
  protected Flowable<SkillMdPath<ResourceInfo>> listSkills() {
    return legacySource.listSkills();
  }

  @Override
  protected Single<ResourceInfo> findSkillMdPath(String skillName) {
    return legacySource.findSkillMdPath(skillName);
  }

  @Override
  protected Single<ResourceInfo> findResourcePath(String skillName, String resourcePath) {
    return legacySource.findResourcePath(skillName, resourcePath);
  }

  @Override
  protected ReadableByteChannel openChannel(ResourceInfo path) throws IOException {
    return legacySource.openChannel(path);
  }

  private static String normalizePath(String path) {
    if (path.isEmpty()) {
      return "";
    }
    if (path.endsWith("/")) {
      return path;
    }
    return path + "/";
  }

  private static ImmutableList<ResourceInfo> scanNativeResources(
      String baseResourcePath, ClassLoader classLoader) throws SkillSourceException {
    try {
      return ClassPath.from(classLoader).getResources().stream()
          .filter(info -> info.getResourceName().startsWith(baseResourcePath))
          .collect(toImmutableList());
    } catch (IOException e) {
      throw new SkillSourceException(
          "Failed to scan classpath under " + baseResourcePath, SKILL_LOAD_ERROR, e);
    }
  }

  private static ImmutableList<ResourceHandle> supplementJarResources(
      String baseResourcePath, ClassLoader classLoader, ImmutableList<ResourceInfo> nativeResources)
      throws SkillSourceException {
    List<ResourceHandle> resources = new ArrayList<>();
    Set<String> nativeNames = new LinkedHashSet<>();
    for (ResourceInfo info : nativeResources) {
      nativeNames.add(info.getResourceName());
      // Preserve Guava's resource identity and lazy reads, including duplicate-name conflicts.
      resources.add(
          new ResourceHandle(
              info.getResourceName(),
              new ByteSource() {
                @Override
                public InputStream openStream() throws IOException {
                  return info.asByteSource().openStream();
                }
              }));
    }
    Set<String> jarNames = new LinkedHashSet<>();
    try {
      scanJarUrls(baseResourcePath, classLoader, jarNames);
    } catch (IOException e) {
      throw new SkillSourceException(
          "Failed to scan classpath under " + baseResourcePath, SKILL_LOAD_ERROR, e);
    }
    jarNames.removeAll(nativeNames);
    for (String name : jarNames) {
      // Match the supplied loader's resource lookup order; never retain an enumeration JarFile.
      resources.add(
          new ResourceHandle(
              name,
              new ByteSource() {
                @Override
                public InputStream openStream() throws IOException {
                  InputStream stream = classLoader.getResourceAsStream(name);
                  if (stream == null) {
                    throw new IOException("Classpath resource not found: " + name);
                  }
                  return stream;
                }
              }));
    }
    return ImmutableList.copyOf(resources);
  }

  private static void scanJarUrls(
      String baseResourcePath, ClassLoader classLoader, Set<String> resources) throws IOException {
    // Guava only scans file: classpath entries. Executable JAR classloaders also expose jar:
    // roots, whose URL handlers provide JarURLConnection even for nested archives.
    for (ClassLoader loader = classLoader; loader != null; loader = loader.getParent()) {
      if (!(loader instanceof URLClassLoader urlLoader)) {
        continue;
      }
      for (URL url : urlLoader.getURLs()) {
        if (!url.getProtocol().equals("jar")) {
          continue;
        }
        if (!(url.openConnection() instanceof JarURLConnection connection)) {
          continue;
        }
        String root = Objects.requireNonNullElse(connection.getEntryName(), "");
        if (!root.isEmpty() && !root.endsWith("/")) {
          root += "/";
        }
        // Open the archive itself; the classpath root need not have a directory entry in it.
        connection =
            (JarURLConnection)
                new URL(url, "jar:" + connection.getJarFileURL() + "!/").openConnection();
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
          Enumeration<JarEntry> entries = jar.entries();
          while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith(root)) {
              String name = entry.getName().substring(root.length());
              if (name.startsWith(baseResourcePath)) {
                resources.add(name);
              }
            }
          }
        }
      }
    }
  }

  private record ResourceHandle(String name, ByteSource content) {}

  /** Shares lookup and parsing behavior between the legacy and supplemented resource catalogs. */
  private static final class ResourceSource<P> extends AbstractSkillSource<P> {
    private final String baseResourcePath;
    private final Single<ImmutableList<P>> allResourcesSingle;
    private final Single<ImmutableMap<String, P>> skillMdsSingle;
    private final Function<P, String> resourceName;
    private final Function<P, ByteSource> content;

    ResourceSource(
        String baseResourcePath,
        Single<ImmutableList<P>> resources,
        Function<P, String> resourceName,
        Function<P, ByteSource> content) {
      this.baseResourcePath = baseResourcePath;
      this.allResourcesSingle = resources;
      this.resourceName = resourceName;
      this.content = content;
      this.skillMdsSingle = resources.map(this::extractSkillMds).cache();
    }

    private ImmutableMap<String, P> extractSkillMds(ImmutableList<P> resources)
        throws SkillSourceException {
      Map<String, P> skillMdMap = new HashMap<>();
      for (P info : resources) {
        String relPath = resourceName.apply(info).substring(baseResourcePath.length());
        List<String> parts = PATH_SPLITTER.splitToList(relPath);
        // Check if the path format matches exactly {skillName}/SKILL.md (or skill.md
        // case-insensitively).
        if (parts.size() == 2 && Ascii.equalsIgnoreCase(parts.get(1), "SKILL.md")) {
          String skillName = parts.get(0);
          String logicalName = skillName.replace('_', '-');
          if (skillMdMap.containsKey(logicalName)) {
            P existing = skillMdMap.get(logicalName);
            throw new SkillSourceException(
                "Conflicting SKILL.md files found for skill '"
                    + logicalName
                    + "': "
                    + resourceName.apply(existing)
                    + " and "
                    + resourceName.apply(info),
                SKILL_LOAD_ERROR);
          }
          skillMdMap.put(logicalName, info);
        }
      }
      return ImmutableMap.copyOf(skillMdMap);
    }

    @Override
    public Single<ImmutableList<String>> listResources(String skillName, String resourceDirectory) {
      String logicalSkillName = skillName.replace('_', '-');
      String prefix =
          resourceDirectory.isEmpty()
              ? ""
              : (resourceDirectory.endsWith("/") ? resourceDirectory : resourceDirectory + "/");

      // Support both standard ADK hyphenated directories and legacy underscore directories.
      String hyphenatedDir = logicalSkillName;
      String underscoredDir = logicalSkillName.replace('-', '_');

      return findSkillMdPath(skillName)
          .flatMap(
              ignored ->
                  allResourcesSingle.map(
                      resources ->
                          resources.stream()
                              .map(
                                  info ->
                                      resourceName.apply(info).substring(baseResourcePath.length()))
                              .filter(
                                  relPath ->
                                      relPath.startsWith(hyphenatedDir + "/" + prefix)
                                          || relPath.startsWith(underscoredDir + "/" + prefix))
                              .map(
                                  relPath -> {
                                    if (relPath.startsWith(hyphenatedDir + "/")) {
                                      return relPath.substring(hyphenatedDir.length() + 1);
                                    } else {
                                      return relPath.substring(underscoredDir.length() + 1);
                                    }
                                  })
                              .filter(path -> !Ascii.equalsIgnoreCase(path, "SKILL.md"))
                              .collect(toImmutableList())))
          .flatMap(
              list ->
                  (!resourceDirectory.isEmpty() && list.isEmpty())
                      ? Single.error(
                          new SkillSourceException(
                              "Resource directory '"
                                  + resourceDirectory
                                  + "' not found for skill '"
                                  + logicalSkillName
                                  + "'",
                              RESOURCE_NOT_FOUND))
                      : Single.just(list));
    }

    @Override
    protected Flowable<SkillMdPath<P>> listSkills() {
      return skillMdsSingle
          .flattenAsFlowable(ImmutableMap::entrySet)
          .map(entry -> new SkillMdPath<>(entry.getKey(), entry.getValue()));
    }

    @Override
    protected Single<P> findSkillMdPath(String skillName) {
      String logicalSkillName = skillName.replace('_', '-');
      return skillMdsSingle
          .mapOptional(map -> Optional.ofNullable(map.get(logicalSkillName)))
          .switchIfEmpty(
              Single.error(
                  new SkillSourceException(
                      "SKILL.md not found for skill: " + logicalSkillName, SKILL_NOT_FOUND)));
    }

    @Override
    protected Single<P> findResourcePath(String skillName, String resourcePath) {
      String logicalSkillName = skillName.replace('_', '-');
      // Support both standard ADK hyphenated directories and legacy underscore directories.
      String hyphenatedDir = logicalSkillName;
      String underscoredDir = logicalSkillName.replace('-', '_');

      String hyphenatedPath = baseResourcePath + hyphenatedDir + "/" + resourcePath;
      String underscoredPath = baseResourcePath + underscoredDir + "/" + resourcePath;

      return allResourcesSingle
          .mapOptional(
              resources ->
                  resources.stream()
                      .filter(
                          info ->
                              resourceName.apply(info).equals(hyphenatedPath)
                                  || resourceName.apply(info).equals(underscoredPath))
                      .findFirst())
          .switchIfEmpty(
              Single.error(
                  new SkillSourceException(
                      "Resource not found: " + resourcePath + " for skill: " + logicalSkillName,
                      RESOURCE_NOT_FOUND)));
    }

    @Override
    protected ReadableByteChannel openChannel(P path) throws IOException {
      return Channels.newChannel(content.apply(path).openStream());
    }
  }
}
