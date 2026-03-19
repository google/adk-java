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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LocalSkillSourceTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void testListFrontmatters() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skill1 = skillsBase.resolve("skill-1");
    Files.createDirectory(skill1);
    Files.writeString(
        skill1.resolve("SKILL.md"),
        """
        ---
        name: skill-1
        description: test1
        ---
        body
        """);

    Path skill2 = skillsBase.resolve("skill-2");
    Files.createDirectory(skill2);
    Files.writeString(
        skill2.resolve("SKILL.md"),
        """
        ---
        name: skill-2
        description: test2
        ---
        body
        """);

    LocalSkillSource source = new LocalSkillSource(skillsBase);
    ImmutableMap<String, Frontmatter> skills = source.listFrontmatters();

    assertThat(skills).hasSize(2);
    assertThat(skills).containsKey("skill-1");
    assertThat(skills).containsKey("skill-2");
    assertThat(skills.get("skill-1").description()).isEqualTo("test1");
  }

  @Test
  public void testListResources() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Path assetsDir = skillDir.resolve("assets");
    Files.createDirectory(assetsDir);

    Files.createFile(assetsDir.resolve("file1.txt"));
    Path subDir = assetsDir.resolve("subdir");
    Files.createDirectory(subDir);
    Files.createFile(subDir.resolve("file2.txt"));

    LocalSkillSource source = new LocalSkillSource(skillsBase);
    ImmutableList<String> resources = source.listResources("my-skill", "assets");

    assertThat(resources).containsExactly("assets/file1.txt", "assets/subdir/file2.txt");
  }

  @Test
  public void testListResources_notDirectory() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    // No assets directory created

    LocalSkillSource source = new LocalSkillSource(skillsBase);
    ImmutableList<String> resources = source.listResources("my-skill", "assets");

    assertThat(resources).isEmpty();
  }

  @Test
  public void testLoadFrontmatter() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        """
        ---
        name: my-skill
        description: This is a test skill
        ---
        body
        """);

    LocalSkillSource source = new LocalSkillSource(skillsBase);
    Frontmatter fm = source.loadFrontmatter("my-skill");

    assertThat(fm.name()).isEqualTo("my-skill");
    assertThat(fm.description()).isEqualTo("This is a test skill");
  }

  @Test
  public void testLoadInstructions() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        """
        ---
        name: my-skill
        description: Test
        ---
        Some Markdown Body
        """);

    LocalSkillSource source = new LocalSkillSource(skillsBase);
    String instructions = source.loadInstructions("my-skill");

    assertThat(instructions).isEqualTo("Some Markdown Body");
  }

  @Test
  public void testLoadInstructions_unclosedFrontmatter() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        """
        ---
        name: my-skill
        description: Test
        Some Markdown Body without closing dashes
        """);

    LocalSkillSource source = new LocalSkillSource(skillsBase);
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> source.loadInstructions("my-skill"));
    assertThat(exception)
        .hasMessageThat()
        .contains("Skill file frontmatter not properly closed with ---");
  }

  @Test
  public void testLoadResource() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Path assetsDir = skillDir.resolve("assets");
    Files.createDirectory(assetsDir);
    Path file = assetsDir.resolve("file1.txt");
    Files.writeString(file, "hello content");

    LocalSkillSource source = new LocalSkillSource(skillsBase);
    ByteSource resource = source.loadResource("my-skill", "assets/file1.txt");

    assertThat(new String(resource.read(), UTF_8)).isEqualTo("hello content");
  }

  @Test
  public void testLoadResource_notFound() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);

    LocalSkillSource source = new LocalSkillSource(skillsBase);
    assertThrows(
        ResourceNotFoundException.class, () -> source.loadResource("my-skill", "non-existent.txt"));
  }

  @Test
  public void testLoadFrontmatter_skillNotFound() {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");

    LocalSkillSource source = new LocalSkillSource(skillsBase);
    assertThrows(SkillNotFoundException.class, () -> source.loadFrontmatter("non-existent"));
  }

  @Test
  public void testListSkillMdPaths_ioException() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    LocalSkillSource source = new LocalSkillSource(skillsBase);

    // Delete the directory to trigger IOException on Files.list
    Files.delete(skillsBase);

    ImmutableMap<String, Frontmatter> skills = source.listFrontmatters();

    assertThat(skills).isEmpty();
  }
}
