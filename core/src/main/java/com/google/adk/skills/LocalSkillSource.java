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
    Path skillDir = skillsBasePath.resolve(skillName).normalize();
    if (!isDirectory(skillDir)) {
      return Single.error(
          new SkillSourceException("Skill not found: " + skillName, SKILL_NOT_FOUND));
    }
    Path resourceDir = skillDir.resolve(resourceDirectory).normalize();
    // Prevent path traversal: the resolved resource directory must remain inside
    // the skill's own directory. A raw string prefix check on the input is not
    // sufficient because "references/../../../../etc" bypasses it.
    if (!resourceDir.startsWith(skillDir)) {
      return Single.error(
          new SkillSourceException(
              "Path traversal detected in resource directory: " + resourceDirectory,
              RESOURCE_NOT_FOUND));
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
    Path base = skillsBasePath.resolve(skillName).normalize();
    Path file = base.resolve(resourcePath).normalize();
    // Enforce boundary: the resolved path must remain inside the skill's base
    // directory. Without this check, a payload like
    // "references/../../../../etc/passwd" passes the startsWith("references/")
    // prefix check in LoadSkillResourceTool but resolves outside skillsBasePath.
    if (!file.startsWith(base)) {
      return Single.error(
          new SkillSourceException("Path traversal detected: " + resourcePath, RESOURCE_NOT_FOUND));
    }
    if (!Files.exists(file)) {
      return Single.error(
          new SkillSourceException("Resource not found: " + file, RESOURCE_NOT_FOUND));
    }
    return Single.just(file);
  }

  @Override
  protected Single<Path> findSkillMdPath(String skillName) {
    Path skillDir = skillsBasePath.resolve(skillName);
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

  private Optional<Path> findSkillMd(Path dir) {
    return Optional.of(dir.resolve("SKILL.md"))
        .filter(Files::exists)
        .or(() -> Optional.of(dir.resolve("skill.md")))
        .filter(Files::exists);
  }
}
