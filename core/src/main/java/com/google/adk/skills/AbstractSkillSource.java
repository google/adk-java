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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.nio.channels.Channels.newReader;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Function.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.stream.Stream;

/**
 * Abstract base class for SkillSource implementations that load skills from path like object.
 *
 * @param <PathT> the type of path object
 */
public abstract class AbstractSkillSource<PathT> implements SkillSource {

  private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
  public static final String THREE_DASHES = "---";

  @Override
  public ImmutableMap<String, Frontmatter> listFrontmatters() {
    try (Stream<PathT> stream = listSkillMdPaths()) {
      return stream
          .map(this::loadFrontmatterFromSkill)
          .collect(toImmutableMap(Frontmatter::name, identity()));
    }
  }

  @Override
  public Frontmatter loadFrontmatter(String skillName) {
    PathT skillMdPath = findSkillMdPath(skillName);
    return loadFrontmatterFromSkill(skillMdPath);
  }

  @Override
  public String loadInstructions(String skillName) {
    PathT skillMdPath = findSkillMdPath(skillName);

    try (BufferedReader reader = openReader(skillMdPath)) {
      return readInstructions(reader);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public ByteSource loadResource(String skillName, String resourcePath) {
    PathT path = findResourcePath(skillName, resourcePath);
    return new ByteSource() {
      @Override
      public InputStream openStream() throws IOException {
        return Channels.newInputStream(AbstractSkillSource.this.openChannel(path));
      }
    };
  }

  /** Returns a {@link Stream} of path to all the supported SKILL.md files. */
  protected abstract Stream<PathT> listSkillMdPaths();

  /** Returns the path to the SKILL.md file for the given skill. */
  protected abstract PathT findSkillMdPath(String skillName);

  /** Returns the skill name based on the given SKILL.md path. */
  protected abstract String getSkillNameFromPath(PathT skillMdPath);

  /** Returns the path to the resource for the given skill. */
  protected abstract PathT findResourcePath(String skillName, String resourcePath);

  /** Opens a {@link InputStream} for reading the content of the given path. */
  protected abstract ReadableByteChannel openChannel(PathT path) throws IOException;

  private BufferedReader openReader(PathT path) throws IOException {
    return new BufferedReader(newReader(openChannel(path), UTF_8));
  }

  private Frontmatter loadFrontmatterFromSkill(PathT skillMdPath) {
    try (BufferedReader reader = openReader(skillMdPath)) {
      String yaml = readFrontmatterYaml(reader);
      Frontmatter frontmatter = parseFrontmatter(yaml);
      String skillName = getSkillNameFromPath(skillMdPath);
      checkArgument(
          frontmatter.name().equals(skillName),
          "Skill name '%s' does not match directory name '%s'.",
          frontmatter.name(),
          skillName);
      return frontmatter;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String readFrontmatterYaml(BufferedReader reader) throws IOException {
    String line = reader.readLine();
    checkArgument(
        line != null && line.trim().equals(THREE_DASHES),
        "Skill file must start with %s",
        THREE_DASHES);

    StringBuilder sb = new StringBuilder();
    while ((line = reader.readLine()) != null) {
      if (line.trim().equals(THREE_DASHES)) {
        return sb.toString();
      }
      sb.append(line).append("\n");
    }
    throw new IllegalArgumentException(
        "Skill file frontmatter not properly closed with " + THREE_DASHES);
  }

  private String readInstructions(BufferedReader reader) throws IOException {
    // Skip the frontmatter block
    String line = reader.readLine();
    checkArgument(
        line != null && line.trim().equals(THREE_DASHES),
        "Skill file must start with %s",
        THREE_DASHES);
    boolean dashClosed = false;
    while ((line = reader.readLine()) != null) {
      if (line.trim().equals(THREE_DASHES)) {
        dashClosed = true;
        break;
      }
    }
    checkArgument(dashClosed, "Skill file frontmatter not properly closed with %s", THREE_DASHES);

    // Read the instructions till the end of the file
    StringBuilder sb = new StringBuilder();
    while ((line = reader.readLine()) != null) {
      sb.append(line).append("\n");
    }
    return sb.toString().trim();
  }

  private Frontmatter parseFrontmatter(String yaml) {
    try {
      return yamlMapper.readValue(yaml, Frontmatter.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
