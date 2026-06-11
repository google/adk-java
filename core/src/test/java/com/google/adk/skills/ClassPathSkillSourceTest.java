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

  private static final String BASE_PATH = "com/google/adk/skills/testdata/skills/";

  @Test
  public void testListFrontmatters() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableMap<String, Frontmatter> skills = source.listFrontmatters().blockingGet();

    assertThat(skills).hasSize(3);
    assertThat(skills).containsKey("skill-1");
    assertThat(skills).containsKey("skill-2");
    assertThat(skills).containsKey("skill-3");
    assertThat(skills.get("skill-1").description()).isEqualTo("test classpath skill 1");
    assertThat(skills.get("skill-2").description()).isEqualTo("test classpath skill 2");
    assertThat(skills.get("skill-3").description())
        .isEqualTo("test classpath skill 3 with underscores");
  }

  @Test
  public void testLoadFrontmatter() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    Frontmatter fm = source.loadFrontmatter("skill-1").blockingGet();

    assertThat(fm.name()).isEqualTo("skill-1");
    assertThat(fm.description()).isEqualTo("test classpath skill 1");
  }

  @Test
  public void testLoadFrontmatter_underscoreMapping() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    Frontmatter fm = source.loadFrontmatter("skill-3").blockingGet();

    assertThat(fm.name()).isEqualTo("skill-3");
    assertThat(fm.description()).isEqualTo("test classpath skill 3 with underscores");
  }

  @Test
  public void testLoadInstructions() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    String instructions = source.loadInstructions("skill-1").blockingGet();

    assertThat(instructions).isEqualTo("body 1");
  }

  @Test
  public void testListResources() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableList<String> resources = source.listResources("skill-2", "assets").blockingGet();
    assertThat(resources).containsExactly("assets/spec/spec.txt");
  }

  @Test
  public void testListResources_excludesSkillMd() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ImmutableList<String> resources = source.listResources("skill-1", "").blockingGet();
    assertThat(resources).containsExactly("resource/extra.txt");
  }

  @Test
  public void testLoadResource() throws Exception {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ByteSource byteSource = source.loadResource("skill-2", "assets/spec/spec.txt").blockingGet();
    assertThat(byteSource.asCharSource(UTF_8).read().trim()).isEqualTo("A spec file");
  }

  @Test
  public void testLoadResource_underscoreMapping() throws Exception {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    ByteSource byteSource = source.loadResource("skill-3", "resource/dummy.txt").blockingGet();
    assertThat(byteSource.asCharSource(UTF_8).read().trim()).isEqualTo("dummy content");
  }

  @Test
  public void testLoadResource_notFound() {
    SkillSource source = new ClassPathSkillSource(BASE_PATH);
    var single = source.loadResource("skill-1", "non-existent.txt");
    RuntimeException exception = assertThrows(RuntimeException.class, single::blockingGet);
    assertThat(exception).hasCauseThat().isInstanceOf(SkillSourceException.class);
    SkillSourceException cause = (SkillSourceException) exception.getCause();
    assertThat(cause.getErrorCode()).isEqualTo(SkillSourceException.RESOURCE_NOT_FOUND);
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
}
