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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class GcsSkillLoaderTest {

  @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();
  @Mock private Storage mockStorage;
  @Mock private Bucket mockBucket;
  @Mock private Page<Blob> mockPage;
  private GcsSkillLoader loader;

  @Before
  public void setUp() {
    when(mockStorage.get("my-bucket")).thenReturn(mockBucket);
    loader = new GcsSkillLoader(mockStorage, "my-bucket", "skills");
  }

  @Test
  public void testLoadSkill() {
    String skillMdContent =
        """
        ---
        name: test-skill
        description: test desc
        ---
        Skill Body
        """;

    Blob manifestBlob = mock(Blob.class);
    when(manifestBlob.exists()).thenReturn(true);
    when(manifestBlob.getContent()).thenReturn(skillMdContent.getBytes(UTF_8));

    when(mockBucket.get("skills/test-skill/SKILL.md")).thenReturn(manifestBlob);

    // Mock an empty references/assets/scripts page
    when(mockPage.streamAll()).thenAnswer(invocation -> Stream.empty());

    when(mockBucket.list(eq(Storage.BlobListOption.prefix("skills/test-skill/references/"))))
        .thenReturn(mockPage);
    when(mockBucket.list(eq(Storage.BlobListOption.prefix("skills/test-skill/assets/"))))
        .thenReturn(mockPage);
    when(mockBucket.list(eq(Storage.BlobListOption.prefix("skills/test-skill/scripts/"))))
        .thenReturn(mockPage);

    Skill skill = loader.loadSkill("test-skill");

    assertThat(skill.name()).isEqualTo("test-skill");
    assertThat(skill.description()).isEqualTo("test desc");
    assertThat(skill.instructions()).isEqualTo("Skill Body");
    assertThat(skill.resources().references()).isEmpty();
  }

  @Test
  public void testListSkills() {
    Blob dirBlob = mock(Blob.class);
    when(dirBlob.isDirectory()).thenReturn(true);
    when(dirBlob.getName()).thenReturn("skills/test-skill/");

    when(mockPage.iterateAll()).thenReturn(ImmutableList.of(dirBlob));

    when(mockBucket.list(any(), any())).thenReturn(mockPage);

    String skillMdContent =
        """
        ---
        name: test-skill
        description: A list test
        ---
        Body
        """;
    Blob manifestBlob = mock(Blob.class);
    when(manifestBlob.exists()).thenReturn(true);
    when(manifestBlob.getContent()).thenReturn(skillMdContent.getBytes(UTF_8));
    when(mockBucket.get("skills/test-skill/SKILL.md")).thenReturn(manifestBlob);

    ImmutableMap<String, Frontmatter> skills = loader.listSkills();

    assertThat(skills).hasSize(1);
    assertThat(skills).containsKey("test-skill");
    assertThat(skills.get("test-skill").description()).isEqualTo("A list test");
  }

  @Test
  public void testLoadSkill_notFound() {
    when(mockBucket.get("skills/missing-skill/SKILL.md")).thenReturn(null);

    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> loader.loadSkill("missing-skill"));
    assertThat(e).hasMessageThat().contains("SKILL.md not found at");
  }
}
