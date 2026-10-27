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

package com.google.adk.sessions;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.Base64;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SessionUtilsTest {

  private static final byte[] payload = "payload".getBytes(UTF_8);

  private static Content contentWithInlineData(byte[] data) {
    return Content.builder()
        .role("user")
        .parts(
            Part.builder()
                .inlineData(
                    Blob.builder().mimeType("image/png").displayName("cat.png").data(data).build())
                .build())
        .build();
  }

  @Test
  public void encodeContent_inlineData_encodesDataAndKeepsMetadata() {
    Content encoded = SessionUtils.encodeContent(contentWithInlineData(payload));

    Blob blob = encoded.parts().get().get(0).inlineData().get();
    assertThat(Base64.getDecoder().decode(blob.data().get())).isEqualTo(payload);
    assertThat(blob.mimeType()).hasValue("image/png");
    assertThat(blob.displayName()).hasValue("cat.png");
  }

  @Test
  public void decodeContent_base64InlineData_decodesDataAndKeepsMetadata() {
    Content decoded =
        SessionUtils.decodeContent(contentWithInlineData(Base64.getEncoder().encode(payload)));

    Blob blob = decoded.parts().get().get(0).inlineData().get();
    assertThat(blob.data().get()).isEqualTo(payload);
    assertThat(blob.mimeType()).hasValue("image/png");
    assertThat(blob.displayName()).hasValue("cat.png");
  }

  @Test
  public void decodeContent_encodeContent_roundTripsInlineData() {
    Content roundTripped =
        SessionUtils.decodeContent(SessionUtils.encodeContent(contentWithInlineData(payload)));

    Blob blob = roundTripped.parts().get().get(0).inlineData().get();
    assertThat(blob.data().get()).isEqualTo(payload);
    assertThat(blob.mimeType()).hasValue("image/png");
    assertThat(blob.displayName()).hasValue("cat.png");
  }
}
