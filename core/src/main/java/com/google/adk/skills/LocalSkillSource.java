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

import static com.google.adk.skills.SkillSourceException.RESOURCE_LOAD_ERROR;
import static com.google.adk.skills.SkillSourceException.RESOURCE_NOT_FOUND;
import static com.google.adk.skills.SkillSourceException.SKILL_LOAD_ERROR;
import static com.google.adk.skills.SkillSourceException.SKILL_NOT_FOUND;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.file.Files.isDirectory;

import com.google.common.collect.ImmutableList;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/** Loads skills from the local file system. */
public final class LocalSkillSource extends AbstractSkillSource<Path> {

  private final Path skillsBasePath;

  public LocalSkillSource(Path skillsBasePath) {
    this.skillsBasePath = skillsBasePath;
  }

  @Override
  public Single<ImmutableList<String>> listResources(String skillName, String resourceDirectory) {
    Path skillDir;
    Path resourceDir;
    try {
      skillDir = validatePathWithinBase(skillsBasePath, skillName, SKILL_NOT_FOUND);
      resourceDir = validatePathWithinBase(skillDir, resourceDirectory, RESOURCE_NOT_FOUND);
    } catch (SkillSourceException e) {
      return Single.error(e);
    }
    if (!isDirectory(skillDir)) {
      return Single.error(
          new SkillSourceException("Skill not found: " + skillName, SKILL_NOT_FOUND));
    }
    if (!isDirectory(resourceDir)) {
      return Single.error(
          new SkillSourceException(
              "Resource directory '%s' not found for skill '%s'"
                  .formatted(resourceDirectory, skillName),
              RESOURCE_NOT_FOUND));
    }

    return Single.fromCallable(
            () -> {
              try (Stream<Path> paths = Files.walk(resourceDir)) {
                return paths
                    .filter(Files::isRegularFile)
                    .map(skillDir::relativize)
                    .map(Path::toString)
                    .collect(toImmutableList());
              }
            })
        .onErrorResumeNext(
            t ->
                Single.error(
                    new SkillSourceException(
                        "Failed to traverse resource directory: " + resourceDirectory,
                        RESOURCE_LOAD_ERROR,
                        t)));
  }

  @Override
  @SuppressWarnings("StreamResourceLeak")
  protected Flowable<SkillMdPath<Path>> listSkills() {
    return Flowable.using(() -> Files.list(skillsBasePath), Flowable::fromStream, Stream::close)
        .onErrorResumeNext(
            t ->
                Flowable.error(
                    new SkillSourceException(
                        "Failed to list skills in directory: " + skillsBasePath,
                        SKILL_LOAD_ERROR,
                        t)))
        .filter(Files::isDirectory)
        .mapOptional(this::findSkillMd)
        .map(skillMd -> new SkillMdPath<>(skillMd.getParent().getFileName().toString(), skillMd));
  }

  @Override
  protected Single<Path> findResourcePath(String skillName, String resourcePath) {
    Path file;
    try {
      Path skillDir = validatePathWithinBase(skillsBasePath, skillName, SKILL_NOT_FOUND);
      file = validatePathWithinBase(skillDir, resourcePath, RESOURCE_NOT_FOUND);
    } catch (SkillSourceException e) {
      return Single.error(e);
    }
    if (!Files.exists(file)) {
      return Single.error(
          new SkillSourceException("Resource not found: " + file, RESOURCE_NOT_FOUND));
    }
    return Single.just(file);
  }

  @Override
  protected Single<Path> findSkillMdPath(String skillName) {
    Path skillDir;
    try {
      skillDir = validatePathWithinBase(skillsBasePath, skillName, SKILL_NOT_FOUND);
    } catch (SkillSourceException e) {
      return Single.error(e);
    }
    if (!isDirectory(skillDir)) {
      return Single.error(
          new SkillSourceException("Skill directory not found: " + skillName, SKILL_NOT_FOUND));
    }
    return Maybe.fromOptional(findSkillMd(skillDir))
        .switchIfEmpty(
            Single.error(
                new SkillSourceException("SKILL.md not found in " + skillName, SKILL_NOT_FOUND)));
  }

  @Override
  protected ReadableByteChannel openChannel(Path path) throws IOException {
    return Files.newByteChannel(path);
  }

  private static Path validatePathWithinBase(Path base, String component, String errorCode)
      throws SkillSourceException {
    // Parse the component against the base's own filesystem: LocalSkillSource accepts an arbitrary
    // skillsBasePath, which may come from a non-default provider, so the parse must match the
    // filesystem base.resolve below uses.
    if (base.getFileSystem().getPath(component).isAbsolute()) {
      throw new SkillSourceException("Absolute paths are not allowed: " + component, errorCode);
    }
    Path normalizedBase = base.normalize().toAbsolutePath();
    Path resolved = base.resolve(component).normalize().toAbsolutePath();
    if (!resolved.startsWith(normalizedBase)) {
      throw new SkillSourceException(
          "Path traversal detected; component must not escape its base directory: " + component,
          errorCode);
    }
    return resolved;
  }

  private Optional<Path> findSkillMd(Path dir) {
    return Optional.of(dir.resolve("SKILL.md"))
        .filter(Files::exists)
        .or(() -> Optional.of(dir.resolve("skill.md")))
        .filter(Files::exists);
  }
}
