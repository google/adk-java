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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class InMemorySkillSourceTest {

  @Test
  public void testListFrontmatters() {
    Frontmatter fm1 = Frontmatter.builder().name("skill-1").description("desc1").build();
    Frontmatter fm2 = Frontmatter.builder().name("skill-2").description("desc2").build();

    InMemorySkillSource source =
        InMemorySkillSource.builder()
            .skill("skill-1")
            .frontmatter(fm1)
            .instructions("body1")
            .skill("skill-2")
            .frontmatter(fm2)
            .instructions("body2")
            .build();

    ImmutableMap<String, Frontmatter> frontmatters = source.listFrontmatters();

    assertThat(frontmatters).hasSize(2);
    assertThat(frontmatters.get("skill-1")).isEqualTo(fm1);
    assertThat(frontmatters.get("skill-2")).isEqualTo(fm2);
  }

  @Test
  public void testListResources() {
    Frontmatter fm = Frontmatter.builder().name("my-skill").description("desc").build();

    InMemorySkillSource source =
        InMemorySkillSource.builder()
            .skill("my-skill")
            .frontmatter(fm)
            .instructions("body")
            .addResource("assets/file1.txt", "content1")
            .addResource("assets/subdir/file2.txt", "content2")
            .addResource("other/file3.txt", "content3")
            .build();

    ImmutableList<String> resources = source.listResources("my-skill", "assets");

    assertThat(resources).containsExactly("assets/file1.txt", "assets/subdir/file2.txt");
  }

  @Test
  public void testListResources_skillNotFound() {
    InMemorySkillSource source = InMemorySkillSource.builder().build();

    ImmutableList<String> resources = source.listResources("non-existent", "assets");

    assertThat(resources).isEmpty();
  }

  @Test
  public void testLoadFrontmatter() {
    Frontmatter fm = Frontmatter.builder().name("my-skill").description("desc").build();

    InMemorySkillSource source =
        InMemorySkillSource.builder()
            .skill("my-skill")
            .frontmatter(fm)
            .instructions("body")
            .build();

    assertThat(source.loadFrontmatter("my-skill")).isEqualTo(fm);
  }

  @Test
  public void testLoadInstructions() {
    Frontmatter fm = Frontmatter.builder().name("my-skill").description("desc").build();

    InMemorySkillSource source =
        InMemorySkillSource.builder()
            .skill("my-skill")
            .frontmatter(fm)
            .instructions("my instructions")
            .build();

    assertThat(source.loadInstructions("my-skill")).isEqualTo("my instructions");
  }

  @Test
  public void testLoadResource() throws IOException {
    Frontmatter fm = Frontmatter.builder().name("my-skill").description("desc").build();

    InMemorySkillSource source =
        InMemorySkillSource.builder()
            .skill("my-skill")
            .frontmatter(fm)
            .instructions("body")
            .addResource("assets/file1.txt", "hello content")
            .build();

    ByteSource resource = source.loadResource("my-skill", "assets/file1.txt");

    assertThat(new String(resource.read(), UTF_8)).isEqualTo("hello content");
  }

  @Test
  public void testLoadResource_notFound() {
    Frontmatter fm = Frontmatter.builder().name("my-skill").description("desc").build();

    InMemorySkillSource source =
        InMemorySkillSource.builder()
            .skill("my-skill")
            .frontmatter(fm)
            .instructions("body")
            .build();

    assertThrows(
        ResourceNotFoundException.class, () -> source.loadResource("my-skill", "non-existent.txt"));
  }

  @Test
  public void testLoadFrontmatter_skillNotFound() {
    InMemorySkillSource source = InMemorySkillSource.builder().build();

    assertThrows(SkillNotFoundException.class, () -> source.loadFrontmatter("non-existent"));
  }

  @Test
  public void testBuilder_missingFrontmatter() {
    InMemorySkillSource.Builder builder = InMemorySkillSource.builder();
    builder.skill("my-skill").addResource("path", "content");

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void testBuilder_missingInstructions() {
    InMemorySkillSource.Builder builder = InMemorySkillSource.builder();
    Frontmatter fm = Frontmatter.builder().name("my-skill").description("desc").build();

    builder.skill("my-skill").frontmatter(fm);

    assertThrows(IllegalStateException.class, builder::build);
  }
}
