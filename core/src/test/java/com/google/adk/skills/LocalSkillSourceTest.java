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

    SkillSource source = new LocalSkillSource(skillsBase);
    ImmutableMap<String, Frontmatter> skills = source.listFrontmatters().blockingGet();

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

    SkillSource source = new LocalSkillSource(skillsBase);
    ImmutableList<String> resources = source.listResources("my-skill", "assets").blockingGet();

    assertThat(resources).containsExactly("assets/file1.txt", "assets/subdir/file2.txt");
  }

  @Test
  public void testListResources_notDirectory() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    // No assets directory created

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.listResources("my-skill", "assets");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
  }

  @Test
  public void testListResources_skillNotFound() {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.listResources("non-existent", "assets");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
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

    SkillSource source = new LocalSkillSource(skillsBase);
    Frontmatter fm = source.loadFrontmatter("my-skill").blockingGet();

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

    SkillSource source = new LocalSkillSource(skillsBase);
    String instructions = source.loadInstructions("my-skill").blockingGet();

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

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.loadInstructions("my-skill");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    assertThat(exception)
        .hasCauseThat()
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

    SkillSource source = new LocalSkillSource(skillsBase);
    ByteSource resource = source.loadResource("my-skill", "assets/file1.txt").blockingGet();

    assertThat(new String(resource.read(), UTF_8)).isEqualTo("hello content");
  }

  @Test
  public void testLoadResource_notFound() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.loadResource("my-skill", "non-existent.txt");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.RESOURCE_NOT_FOUND);
  }

  @Test
  public void testLoadFrontmatter_skillNotFound() {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.loadFrontmatter("non-existent");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.SKILL_NOT_FOUND);
  }

  @Test
  public void testListSkillMdPaths_skillSourceException() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    SkillSource source = new LocalSkillSource(skillsBase);

    // Delete the directory to trigger IOException on Files.list
    Files.delete(skillsBase);

    var single = source.listFrontmatters();
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.SKILL_LOAD_ERROR);
  }

  @Test
  public void testLoadFrontmatter_missingStartDashes() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        """
        name: my-skill
        description: This is a test skill
        ---
        body
        """);

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.loadFrontmatter("my-skill");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    assertThat(exception)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Skill file must start with ---");
  }

  @Test
  public void testLoadInstructions_missingStartDashes() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        """
        name: my-skill
        description: Test
        ---
        Some Markdown Body
        """);

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.loadInstructions("my-skill");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    assertThat(exception)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Skill file must start with ---");
  }

  @Test
  public void testLoadFrontmatter_nameMismatch() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Files.writeString(
        skillDir.resolve("SKILL.md"),
        """
        ---
        name: other-skill
        description: This is a test skill
        ---
        body
        """);

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.loadFrontmatter("my-skill");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    assertThat(exception)
        .hasCauseThat()
        .hasMessageThat()
        .contains(
            "Skill name in the frontmatter 'other-skill' does not match skill name 'my-skill'.");
  }

  @Test
  public void testLoadFrontmatter_emptyFile() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), "");

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.loadFrontmatter("my-skill");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    assertThat(exception)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Skill file must start with ---");
  }

  @Test
  public void testLoadInstructions_emptyFile() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    Path skillDir = skillsBase.resolve("my-skill");
    Files.createDirectory(skillDir);
    Files.writeString(skillDir.resolve("SKILL.md"), "");

    SkillSource source = new LocalSkillSource(skillsBase);
    var single = source.loadInstructions("my-skill");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    assertThat(exception)
        .hasCauseThat()
        .hasMessageThat()
        .contains("Skill file must start with ---");
  }

  @Test
  public void testLoadResource_pathTraversalRejected() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);
    // A secret file outside the skills base directory.
    Files.writeString(tempFolder.getRoot().toPath().resolve("secret.txt"), "top-secret");

    SkillSource source = new LocalSkillSource(skillsBase);

    // A skill name that escapes the skills base via "..".
    var single = source.loadResource("..", "secret.txt");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
  }

  @Test
  public void testListResources_pathTraversalRejected() throws IOException {
    Path skillsBase = tempFolder.getRoot().toPath().resolve("skills");
    Files.createDirectory(skillsBase);

    SkillSource source = new LocalSkillSource(skillsBase);

    var single = source.listResources("../../etc", "");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
  }
}
