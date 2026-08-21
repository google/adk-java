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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class FrontmatterTest {

  private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

  @Test
  public void testValidFrontmatter() throws Exception {
    String yaml =
        """
        name: test-skill
        description: This is a test
        allowed-tools: "tool1 tool2"
        compatibility: "1.0"
        """;
    Frontmatter fm = yamlMapper.readValue(yaml, Frontmatter.class);

    assertThat(fm.name()).isEqualTo("test-skill");
    assertThat(fm.description()).isEqualTo("This is a test");
    assertThat(fm.allowedTools()).hasValue("tool1 tool2");
    assertThat(fm.compatibility()).hasValue("1.0");
  }

  @Test
  public void testFrontmatterWithMetadata() throws Exception {
    String yaml =
        """
        name: test-skill-metadata
        description: Test with metadata
        metadata:
          key1: value1
          key2: 123
        """;
    Frontmatter fm = yamlMapper.readValue(yaml, Frontmatter.class);

    assertThat(fm.name()).isEqualTo("test-skill-metadata");
    assertThat(fm.metadata()).containsEntry("key1", "value1");
    assertThat(fm.metadata()).containsEntry("key2", 123);
  }

  @Test
  public void testInvalidName() {
    Frontmatter.Builder builder = Frontmatter.builder().name("Invalid_Name").description("test");
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(ex).hasMessageThat().contains("lowercase kebab-case");
  }

  @Test
  public void testLongName() {
    String longName = "a".repeat(65);
    Frontmatter.Builder builder = Frontmatter.builder().name(longName).description("test");
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, builder::build);
    assertThat(ex).hasMessageThat().contains("must be at most 64 characters");
  }
}
