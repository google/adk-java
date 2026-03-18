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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LocalSkillLoaderTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void testLoadSkillFromDir() throws IOException {
    Path skillDir = tempFolder.getRoot().toPath().resolve("my-skill");
    Files.createDirectory(skillDir);
    Path skillMd = skillDir.resolve("SKILL.md");
    String content =
        """
        ---
        name: my-skill
        description: This is a test skill
        ---
        Some Markdown Body
        """;
    Files.writeString(skillMd, content);

    SkillLoader loader = new LocalSkillLoader(skillDir.getParent());
    Skill skill = loader.loadSkill("my-skill");

    assertThat(skill.name()).isEqualTo("my-skill");
    assertThat(skill.description()).isEqualTo("This is a test skill");
    assertThat(skill.instructions()).isEqualTo("Some Markdown Body");
  }

  @Test
  public void testLoadSkillFromDir_invalidYaml() throws IOException {
    Path skillDir = tempFolder.getRoot().toPath().resolve("invalid-yaml");
    Files.createDirectory(skillDir);
    Path skillMd = skillDir.resolve("SKILL.md");
    // Invalid yaml
    String content =
        """
        ---
        name: invalid-yaml
        description: [
        ---
        Body
        """;
    Files.writeString(skillMd, content);

    SkillLoader loader = new LocalSkillLoader(skillDir.getParent());
    var unused =
        assertThrows(IllegalArgumentException.class, () -> loader.loadSkill("invalid-yaml"));
  }

  @Test
  public void testLoadSkillFromDir_mismatchedName() throws IOException {
    Path skillDir = tempFolder.getRoot().toPath().resolve("test-skill");
    Files.createDirectory(skillDir);
    Path skillMd = skillDir.resolve("SKILL.md");
    String content =
        """
        ---
        name: other-skill
        description: Test
        ---
        Body
        """;
    Files.writeString(skillMd, content);

    SkillLoader loader = new LocalSkillLoader(skillDir.getParent());
    IllegalArgumentException exception =
        assertThrows(IllegalArgumentException.class, () -> loader.loadSkill("test-skill"));
    assertThat(exception).hasMessageThat().contains("Skill name 'other-skill' does not match");
  }

  @Test
  public void testListSkillsInDir() throws IOException {
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

    SkillLoader loader = new LocalSkillLoader(skillsBase);
    ImmutableMap<String, Frontmatter> skills = loader.listSkills();

    assertThat(skills).hasSize(2);
    assertThat(skills).containsKey("skill-1");
    assertThat(skills).containsKey("skill-2");
    assertThat(skills.get("skill-1").description()).isEqualTo("test1");
  }
}
