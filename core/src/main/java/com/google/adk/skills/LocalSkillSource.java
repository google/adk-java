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

import static java.nio.file.Files.isDirectory;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.MustBeClosed;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loads skills from the local file system. */
public final class LocalSkillSource extends AbstractSkillSource<Path> {
  private static final Logger logger = LoggerFactory.getLogger(LocalSkillSource.class);

  private final Path skillsBasePath;

  public LocalSkillSource(Path skillsBasePath) {
    this.skillsBasePath = skillsBasePath;
  }

  @Override
  public ImmutableList<String> listResources(String skillName, String resourceDirectory) {
    Path skillDir = skillsBasePath.resolve(skillName);
    Path resourceDir = skillDir.resolve(resourceDirectory);

    if (!isDirectory(resourceDir)) {
      return ImmutableList.of();
    }

    ImmutableList.Builder<String> builder = ImmutableList.builder();
    try (Stream<Path> paths = Files.walk(resourceDir)) {
      paths
          .filter(Files::isRegularFile)
          .forEach(path -> builder.add(skillDir.relativize(path).toString()));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to traverse directory: " + resourceDir, e);
    }
    return builder.build();
  }

  @Override
  protected Path findResourcePath(String skillName, String resourcePath) {
    Path file = skillsBasePath.resolve(skillName).resolve(resourcePath);
    if (!Files.exists(file)) {
      throw new ResourceNotFoundException("Resource not found: " + file);
    }
    return file;
  }

  @MustBeClosed
  @Override
  protected Stream<Path> listSkillMdPaths() {
    try {
      return Files.list(skillsBasePath)
          .filter(Files::isDirectory)
          .map(this::findSkillMd)
          .flatMap(Optional::stream);
    } catch (IOException e) {
      logger.warn("Failed to list skills in directory: {}", skillsBasePath, e);
      return Stream.empty();
    }
  }

  @Override
  protected Path findSkillMdPath(String skillName) {
    Path skillDir = skillsBasePath.resolve(skillName);
    if (!isDirectory(skillDir)) {
      throw new SkillNotFoundException("Skill directory not found: " + skillName);
    }
    return findSkillMd(skillDir)
        .orElseThrow(() -> new SkillNotFoundException("SKILL.md not found in " + skillName));
  }

  @Override
  protected String getSkillNameFromPath(Path skillMdPath) {
    return skillMdPath.getParent().getFileName().toString();
  }

  @Override
  protected ReadableByteChannel openChannel(Path path) throws IOException {
    return Files.newByteChannel(path);
  }

  private Optional<Path> findSkillMd(Path dir) {
    return Optional.of(dir.resolve("SKILL.md"))
        .filter(Files::exists)
        .or(() -> Optional.of(dir.resolve("skill.md")))
        .filter(Files::exists);
  }
}
