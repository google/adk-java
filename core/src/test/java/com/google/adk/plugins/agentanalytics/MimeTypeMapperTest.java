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

package com.google.adk.plugins.agentanalytics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class MimeTypeMapperTest {

  @Test
  public void getExtension_commonImages_returnsExtension() {
    assertEquals(".jpg", MimeTypeMapper.getExtension("image/jpeg"));
    assertEquals(".png", MimeTypeMapper.getExtension("image/png"));
    assertEquals(".gif", MimeTypeMapper.getExtension("image/gif"));
  }

  @Test
  public void getExtension_commonAudio_returnsExtension() {
    assertEquals(".mp3", MimeTypeMapper.getExtension("audio/mpeg"));
    assertEquals(".wav", MimeTypeMapper.getExtension("audio/wav"));
  }

  @Test
  public void getExtension_commonVideo_returnsExtension() {
    assertEquals(".mp4", MimeTypeMapper.getExtension("video/mp4"));
    assertEquals(".mov", MimeTypeMapper.getExtension("video/quicktime"));
  }

  @Test
  public void getExtension_unknownType_returnsEmptyString() {
    assertEquals("", MimeTypeMapper.getExtension("application/octet-stream"));
    assertEquals("", MimeTypeMapper.getExtension("text/plain"));
  }
}
