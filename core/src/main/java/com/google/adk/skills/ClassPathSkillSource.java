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
import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ResourceInfo;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Loads skills from the classpath. */
public final class ClassPathSkillSource extends AbstractSkillSource<ResourceInfo> {

  private static final Splitter PATH_SPLITTER = Splitter.on('/');

  private final String baseResourcePath;
  private final ClassLoader classLoader;
  private final Single<ImmutableMap<String, ResourceInfo>> skillMdsSingle;
  private final Single<ImmutableList<ResourceInfo>> allResourcesSingle;

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
    this.baseResourcePath = normalizePath(baseResourcePath);
    this.classLoader = classLoader;

    // Scan classpath once (lazily)
    Single<ImmutableList<ResourceInfo>> scanned = Single.fromCallable(this::scanClassPath).cache();
    this.allResourcesSingle = scanned;
    this.skillMdsSingle = scanned.map(this::extractSkillMds).cache();
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

  private ImmutableList<ResourceInfo> scanClassPath() throws SkillSourceException {
    try {
      ClassPath classPath = ClassPath.from(classLoader);
      return classPath.getResources().stream()
          .filter(info -> info.getResourceName().startsWith(baseResourcePath))
          .collect(toImmutableList());
    } catch (IOException e) {
      throw new SkillSourceException(
          "Failed to scan classpath under " + baseResourcePath, SKILL_LOAD_ERROR, e);
    }
  }

  private ImmutableMap<String, ResourceInfo> extractSkillMds(ImmutableList<ResourceInfo> resources)
      throws SkillSourceException {
    Map<String, ResourceInfo> skillMdMap = new HashMap<>();
    for (ResourceInfo info : resources) {
      String relPath = info.getResourceName().substring(baseResourcePath.length());
      List<String> parts = PATH_SPLITTER.splitToList(relPath);
      // Check if the path format matches exactly {skillName}/SKILL.md (or skill.md
      // case-insensitively).
      if (parts.size() == 2 && Ascii.equalsIgnoreCase(parts.get(1), "SKILL.md")) {
        String skillName = parts.get(0);
        String logicalName = skillName.replace('_', '-');
        if (skillMdMap.containsKey(logicalName)) {
          ResourceInfo existing = skillMdMap.get(logicalName);
          throw new SkillSourceException(
              "Conflicting SKILL.md files found for skill '"
                  + logicalName
                  + "': "
                  + existing.getResourceName()
                  + " and "
                  + info.getResourceName(),
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

    return allResourcesSingle.map(
        resources ->
            resources.stream()
                .map(info -> info.getResourceName().substring(baseResourcePath.length()))
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
                .collect(toImmutableList()));
  }

  @Override
  protected Flowable<SkillMdPath<ResourceInfo>> listSkills() {
    return skillMdsSingle
        .flattenAsFlowable(ImmutableMap::entrySet)
        .map(entry -> new SkillMdPath<>(entry.getKey(), entry.getValue()));
  }

  @Override
  protected Single<ResourceInfo> findSkillMdPath(String skillName) {
    String logicalSkillName = skillName.replace('_', '-');
    return skillMdsSingle
        .map(map -> Optional.ofNullable(map.get(logicalSkillName)))
        .flatMap(
            opt ->
                opt.isPresent()
                    ? Single.just(opt.get())
                    : Single.error(
                        new SkillSourceException(
                            "SKILL.md not found for skill: " + logicalSkillName, SKILL_NOT_FOUND)));
  }

  @Override
  protected Single<ResourceInfo> findResourcePath(String skillName, String resourcePath) {
    String logicalSkillName = skillName.replace('_', '-');
    // Support both standard ADK hyphenated directories and legacy underscore directories.
    String hyphenatedDir = logicalSkillName;
    String underscoredDir = logicalSkillName.replace('-', '_');

    String hyphenatedPath = baseResourcePath + hyphenatedDir + "/" + resourcePath;
    String underscoredPath = baseResourcePath + underscoredDir + "/" + resourcePath;

    return allResourcesSingle
        .map(
            resources ->
                resources.stream()
                    .filter(
                        info ->
                            info.getResourceName().equals(hyphenatedPath)
                                || info.getResourceName().equals(underscoredPath))
                    .findFirst())
        .flatMap(
            opt ->
                opt.isPresent()
                    ? Single.just(opt.get())
                    : Single.error(
                        new SkillSourceException(
                            "Resource not found: "
                                + resourcePath
                                + " for skill: "
                                + logicalSkillName,
                            RESOURCE_NOT_FOUND)));
  }

  @Override
  protected ReadableByteChannel openChannel(ResourceInfo path) throws IOException {
    return Channels.newChannel(path.asByteSource().openStream());
  }
}
