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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ClassPathSkillSourceTest {

  private static final String BASE_PATH = "skills/";

  // =========================================================================
  // Constructor & Base Path Normalization
  // =========================================================================

  @Test
  public void testConstructor_normalizesPathWithoutTrailingSlash() {
    SkillSource source = new ClassPathSkillSource("skills");
    Frontmatter fm = source.loadFrontmatter("normal-skill").blockingGet();
    assertThat(fm.name()).isEqualTo("normal-skill");
  }

  @Test
  public void testConstructor_emptyBasePath() {
    SkillSource emptySource = new ClassPathSkillSource("");
    ImmutableMap<String, Frontmatter> skills = emptySource.listFrontmatters().blockingGet();
    assertThat(skills).containsKey("root-skill");
  }

  // =========================================================================
  // listFrontmatters
  // =========================================================================

  @Test
  public void testListFrontmatters() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableMap<String, Frontmatter> skills = source.listFrontmatters().blockingGet();

    assertThat(skills).hasSize(2);
    assertThat(skills).containsKey("normal-skill");
    assertThat(skills).containsKey("underscore-skill");

    assertThat(skills.get("normal-skill").description()).isEqualTo("A normal skill with a hyphen");
    assertThat(skills.get("underscore-skill").description())
        .isEqualTo("A skill with an underscore");
  }

  // =========================================================================
  // loadFrontmatter
  // =========================================================================

  @Test
  public void testLoadFrontmatter() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    Frontmatter fm = source.loadFrontmatter("normal-skill").blockingGet();

    assertThat(fm.name()).isEqualTo("normal-skill");
    assertThat(fm.description()).isEqualTo("A normal skill with a hyphen");
  }

  @Test
  public void testLoadFrontmatter_underscoreMapping() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    Frontmatter fm = source.loadFrontmatter("underscore-skill").blockingGet();

    assertThat(fm.name()).isEqualTo("underscore-skill");
    assertThat(fm.description()).isEqualTo("A skill with an underscore");
  }

  @Test
  public void testLoadFrontmatter_skillNotFound() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    var single = source.loadFrontmatter("non-existent");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.SKILL_NOT_FOUND);
  }

  // =========================================================================
  // loadInstructions
  // =========================================================================

  @Test
  public void testLoadInstructions() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    String instructions = source.loadInstructions("normal-skill").blockingGet();

    assertThat(instructions).isEqualTo("body 1");
  }

  // =========================================================================
  // listResources
  // =========================================================================

  @Test
  public void testListResources_skillNotFound() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    var single = source.listResources("non-existent", "assets");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.SKILL_NOT_FOUND);
  }

  @Test
  public void testListResources_resourceDirectoryNotFound() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    var single = source.listResources("normal-skill", "non-existent-dir");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.RESOURCE_NOT_FOUND);
  }

  @Test
  public void testListResources() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableList<String> resources = source.listResources("normal-skill", "assets").blockingGet();
    assertThat(resources).containsExactly("assets/spec/spec.txt");
  }

  @Test
  public void testListResources_excludesSkillMd() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableList<String> resources = source.listResources("normal-skill", "").blockingGet();
    assertThat(resources).containsExactly("assets/spec/spec.txt", "resource/extra.txt");
  }

  @Test
  public void testListResources_underscoreMapping() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableList<String> resources =
        source.listResources("underscore-skill", "resource").blockingGet();
    assertThat(resources).containsExactly("resource/dummy.txt");
  }

  // =========================================================================
  // loadResource
  // =========================================================================

  @Test
  public void testLoadResource() throws Exception {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ByteSource byteSource =
        source.loadResource("normal-skill", "assets/spec/spec.txt").blockingGet();
    assertThat(byteSource.asCharSource(UTF_8).read().trim()).isEqualTo("A spec file");
  }

  @Test
  public void testLoadResource_underscoreMapping() throws Exception {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ByteSource byteSource =
        source.loadResource("underscore-skill", "resource/dummy.txt").blockingGet();
    assertThat(byteSource.asCharSource(UTF_8).read().trim()).isEqualTo("dummy content");
  }

  @Test
  public void testLoadResource_notFound() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    var single = source.loadResource("normal-skill", "non-existent.txt");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.RESOURCE_NOT_FOUND);
  }

  // =========================================================================
  // Error Handling & Conflicts
  // =========================================================================

  @Test
  public void testConflictingSkillMds() {
    SkillSource source = new ClassPathSkillSource("skills_conflict/");
    var single = source.listFrontmatters();
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.SKILL_LOAD_ERROR);
    assertThat(cause).hasMessageThat().contains("Conflicting SKILL.md files found for skill 'a-b'");
  }
}
