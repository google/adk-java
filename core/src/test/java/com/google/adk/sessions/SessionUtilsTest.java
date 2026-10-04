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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class SessionUtilsTest {

  private static final ObjectMapper objectMapper = JsonBaseModel.getMapper();
  private static final byte[] PAYLOAD = "payload".getBytes(StandardCharsets.UTF_8);

  private static Content contentWithInlineData() {
    return Content.builder()
        .role("user")
        .parts(
            Part.builder()
                .inlineData(Blob.builder().mimeType("image/png").data(PAYLOAD).build())
                .build())
        .build();
  }

  @Test
  public void encodeContent_inlineData_preservesMimeType() {
    Content encoded = SessionUtils.encodeContent(contentWithInlineData());

    Blob encodedBlob = encoded.parts().get().get(0).inlineData().get();
    assertThat(encodedBlob.mimeType()).hasValue("image/png");
  }

  @Test
  public void decodeContent_inlineData_preservesMimeType() {
    Content decoded = SessionUtils.decodeContent(contentWithInlineData());

    Blob decodedBlob = decoded.parts().get().get(0).inlineData().get();
    assertThat(decodedBlob.mimeType()).hasValue("image/png");
  }

  @Test
  public void decodeContent_encodeContent_roundTripsInlineData() {
    Content roundTripped =
        SessionUtils.decodeContent(SessionUtils.encodeContent(contentWithInlineData()));

    Blob blob = roundTripped.parts().get().get(0).inlineData().get();
    assertThat(blob.mimeType()).hasValue("image/png");
    assertThat(blob.data().get()).isEqualTo(PAYLOAD);
  }

  /** The MIME type has to survive the JSON the Vertex AI Sessions API stores the event in. */
  @Test
  public void convertEventToJson_inlineData_persistsMimeType() throws Exception {
    Event event =
        Event.builder()
            .author("user")
            .invocationId("inv-123")
            .timestamp(Instant.parse("2023-01-01T00:00:00Z").toEpochMilli())
            .content(contentWithInlineData())
            .build();

    JsonNode jsonNode = objectMapper.readTree(SessionJsonConverter.convertEventToJson(event));

    JsonNode inlineData = jsonNode.get("content").get("parts").get(0).get("inlineData");
    assertThat(inlineData.has("mimeType")).isTrue();
    assertThat(inlineData.get("mimeType").asText()).isEqualTo("image/png");
  }
}
