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

import static com.google.adk.skills.SkillSourceException.SKILL_FORMAT_ERROR;
import static com.google.adk.skills.SkillSourceException.SKILL_LOAD_ERROR;
import static java.nio.channels.Channels.newReader;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

/**
 * Abstract base class for SkillSource implementations that load skills from path like object.
 *
 * @param <PathT> the type of path object
 */
public abstract class AbstractSkillSource<PathT> implements SkillSource {

  private static final String THREE_DASHES = "---";
  private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  /** A container class that holds a skill's name and the path to its SKILL.md file. */
  public static final class SkillMdPath<PathT> {

    private final String name;
    private final PathT mdPath;

    /**
     * Constructs a {@code SkillMdPath}.
     *
     * @param name the name of the skill
     * @param mdPath the path to the SKILL.md file
     */
    public SkillMdPath(String name, PathT mdPath) {
      this.name = name;
      this.mdPath = mdPath;
    }
  }

  @Override
  public Single<ImmutableMap<String, Frontmatter>> listFrontmatters() {
    return listSkills()
        .map(skillMdPath -> loadFrontmatter(skillMdPath.name, skillMdPath.mdPath))
        .collectInto(
            ImmutableMap.<String, Frontmatter>builder(),
            (builder, frontmatter) -> builder.put(frontmatter.name(), frontmatter))
        .map(ImmutableMap.Builder::buildOrThrow);
  }

  @Override
  public Single<Frontmatter> loadFrontmatter(String skillName) {
    return findSkillMdPath(skillName).map(path -> loadFrontmatter(skillName, path));
  }

  private Frontmatter loadFrontmatter(String skillName, PathT skillMdPath)
      throws SkillSourceException {
    try (BufferedReader reader = openReader(skillMdPath)) {
      String yaml = readFrontmatterYaml(reader);
      Frontmatter frontmatter = yamlMapper.readValue(yaml, Frontmatter.class);
      if (!frontmatter.name().equals(skillName)) {
        throw new SkillSourceException(
            "Skill name in the frontmatter '%s' does not match skill name '%s'."
                .formatted(frontmatter.name(), skillName),
            SKILL_LOAD_ERROR);
      }
      return frontmatter;
    } catch (IOException e) {
      throw new SkillSourceException(
          "Cannot load frontmatter for skill '" + skillName + "'", SKILL_LOAD_ERROR, e);
    }
  }

  @Override
  public Single<String> loadInstructions(String skillName) {
    return findSkillMdPath(skillName)
        .map(
            skillMdPath -> {
              try (BufferedReader reader = openReader(skillMdPath)) {
                return readInstructions(reader);
              } catch (IOException e) {
                throw new SkillSourceException(
                    "Failed to load instruction for skill '" + skillName + "'",
                    SKILL_LOAD_ERROR,
                    e);
              }
            });
  }

  @Override
  public Single<ByteSource> loadResource(String skillName, String resourcePath) {
    return findResourcePath(skillName, resourcePath)
        .map(
            path ->
                new ByteSource() {
                  @Override
                  public InputStream openStream() throws IOException {
                    return Channels.newInputStream(AbstractSkillSource.this.openChannel(path));
                  }
                });
  }

  /**
   * Returns a {@link Flowable} of skills as a pair of skill name and the path to the SKILL.md file.
   */
  protected abstract Flowable<SkillMdPath<PathT>> listSkills();

  /** Returns the path to the SKILL.md file for the given skill. */
  protected abstract Single<PathT> findSkillMdPath(String skillName);

  /** Returns the path to the resource for the given skill. */
  protected abstract Single<PathT> findResourcePath(String skillName, String resourcePath);

  /** Opens a {@link InputStream} for reading the content of the given path. */
  protected abstract ReadableByteChannel openChannel(PathT path) throws IOException;

  private BufferedReader openReader(PathT path) throws IOException {
    return new BufferedReader(newReader(openChannel(path), UTF_8));
  }

  private String readFrontmatterYaml(BufferedReader reader)
      throws IOException, SkillSourceException {
    String line = reader.readLine();
    if (line == null || !line.trim().equals(THREE_DASHES)) {
      throw new SkillSourceException(
          "Skill file must start with " + THREE_DASHES, SKILL_FORMAT_ERROR);
    }

    StringBuilder sb = new StringBuilder();
    while ((line = reader.readLine()) != null) {
      if (line.trim().equals(THREE_DASHES)) {
        return sb.toString();
      }
      sb.append(line).append("\n");
    }
    throw new SkillSourceException(
        "Skill file frontmatter not properly closed with " + THREE_DASHES, SKILL_FORMAT_ERROR);
  }

  private String readInstructions(BufferedReader reader) throws IOException, SkillSourceException {
    // Skip the frontmatter block
    String line = reader.readLine();
    if (line == null || !line.trim().equals(THREE_DASHES)) {
      throw new SkillSourceException(
          "Skill file must start with " + THREE_DASHES, SKILL_FORMAT_ERROR);
    }
    boolean dashClosed = false;
    while ((line = reader.readLine()) != null) {
      if (line.trim().equals(THREE_DASHES)) {
        dashClosed = true;
        break;
      }
    }
    if (!dashClosed) {
      throw new SkillSourceException(
          "Skill file frontmatter not properly closed with " + THREE_DASHES, SKILL_FORMAT_ERROR);
    }
    // Read the instructions till the end of the file
    StringBuilder sb = new StringBuilder();
    while ((line = reader.readLine()) != null) {
      sb.append(line).append("\n");
    }
    return sb.toString().trim();
  }
}
