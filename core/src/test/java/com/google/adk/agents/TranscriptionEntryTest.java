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

package com.google.adk.agents;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class TranscriptionEntryTest {

  private static final Blob AUDIO =
      Blob.builder().mimeType("audio/pcm").data(new byte[] {1, 2, 3}).build();
  private static final Content TEXT = Content.builder().parts(Part.fromText("hello")).build();

  @Test
  public void build_withAudio_leavesContentEmpty() {
    TranscriptionEntry entry = TranscriptionEntry.builder().role("user").blob(AUDIO).build();

    assertThat(entry.role()).hasValue("user");
    assertThat(entry.blob()).hasValue(AUDIO);
    assertThat(entry.content()).isEmpty();
  }

  @Test
  public void build_withText_leavesBlobEmpty() {
    TranscriptionEntry entry = TranscriptionEntry.builder().role("model").content(TEXT).build();

    assertThat(entry.content()).hasValue(TEXT);
    assertThat(entry.blob()).isEmpty();
  }

  @Test
  public void build_withoutRole_leavesRoleEmpty() {
    TranscriptionEntry entry = TranscriptionEntry.builder().blob(AUDIO).build();

    assertThat(entry.role()).isEmpty();
  }

  @Test
  public void build_withNeitherBlobNorContent_throws() {
    TranscriptionEntry.Builder builder = TranscriptionEntry.builder().role("user");

    IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
    assertThat(e).hasMessageThat().contains("Exactly one of blob or content must be set");
  }

  @Test
  public void build_withBothBlobAndContent_throws() {
    TranscriptionEntry.Builder builder = TranscriptionEntry.builder().blob(AUDIO).content(TEXT);

    IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
    assertThat(e).hasMessageThat().contains("Exactly one of blob or content must be set");
  }

  @Test
  public void toBuilder_clearingBlobAndSettingContent_swapsTheData() {
    TranscriptionEntry audioEntry = TranscriptionEntry.builder().role("user").blob(AUDIO).build();

    TranscriptionEntry textEntry = audioEntry.toBuilder().blob(null).content(TEXT).build();

    assertThat(textEntry.role()).hasValue("user");
    assertThat(textEntry.blob()).isEmpty();
    assertThat(textEntry.content()).hasValue(TEXT);
  }

  @Test
  public void toBuilder_createsBuilderWithSameValues() {
    TranscriptionEntry entry = TranscriptionEntry.builder().role("user").blob(AUDIO).build();

    assertThat(entry.toBuilder().build()).isEqualTo(entry);
  }
}
