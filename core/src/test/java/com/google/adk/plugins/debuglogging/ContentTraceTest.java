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

package com.google.adk.plugins.debuglogging;

import static com.google.common.truth.Truth.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FileData;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Language;
import com.google.genai.types.Outcome;
import com.google.genai.types.Part;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Asserts on the <em>serialized</em> trace rather than on an intermediate map, because the record
 * schema plus {@link DebugYamlWriter#configure} is what actually decides the document a reviewer
 * reads.
 */
@RunWith(JUnit4.class)
public class ContentTraceTest {

  private static final String USER_ROLE = "user";
  private static final String IMAGE_MIME_TYPE = "image/png";
  private static final byte[] IMAGE_BYTES = {1, 2, 3, 4, 5};

  private final ObjectMapper mapper = DebugYamlWriter.configure(new ObjectMapper());

  private String serialize(Content content) throws Exception {
    return mapper.writeValueAsString(ContentTrace.from(content));
  }

  private static Content contentOf(Part... parts) {
    return Content.builder().role(USER_ROLE).parts(ImmutableList.copyOf(parts)).build();
  }

  @Test
  public void serialize_textPart_usesTheExpectedKeys() throws Exception {
    String json = serialize(contentOf(Part.builder().text("hello").build()));

    assertThat(json).isEqualTo("{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}");
  }

  /** The headline guarantee: an image is described, never inlined. */
  @Test
  public void serialize_inlineData_neverEmitsTheBytes() throws Exception {
    Blob blob =
        Blob.builder()
            .mimeType(IMAGE_MIME_TYPE)
            .displayName("screenshot.png")
            .data(IMAGE_BYTES)
            .build();

    String json = serialize(contentOf(Part.builder().inlineData(blob).build()));

    assertThat(json).contains("\"mime_type\":\"image/png\"");
    assertThat(json).contains("\"display_name\":\"screenshot.png\"");
    assertThat(json).contains("\"_data_omitted\":true");
    assertThat(json).doesNotContain("\"data\"");
    assertThat(json).doesNotContain("AQIDBAU=");
  }

  /** adk-python's {@code if part.text:} is false for "", where a present Optional is not. */
  @Test
  public void serialize_emptyText_isDroppedLikeThePythonTruthinessCheck() throws Exception {
    String json = serialize(contentOf(Part.builder().text("").build()));

    assertThat(json).isEqualTo("{\"role\":\"user\",\"parts\":[]}");
  }

  @Test
  public void serialize_partCarryingNothing_isDropped() throws Exception {
    String json = serialize(contentOf(Part.builder().build()));

    assertThat(json).isEqualTo("{\"role\":\"user\",\"parts\":[]}");
  }

  @Test
  public void serialize_absentRole_omitsTheKeyRatherThanWritingNull() throws Exception {
    Content content = Content.builder().parts(ImmutableList.of()).build();

    assertThat(serialize(content)).isEqualTo("{\"parts\":[]}");
  }

  @Test
  public void serialize_functionCall_carriesIdNameAndArgs() throws Exception {
    FunctionCall call =
        FunctionCall.builder()
            .id("call-1")
            .name("lookup_order")
            .args(ImmutableMap.of("orderId", 7))
            .build();

    String json = serialize(contentOf(Part.builder().functionCall(call).build()));

    assertThat(json)
        .contains(
            "\"function_call\":{\"id\":\"call-1\",\"name\":\"lookup_order\","
                + "\"args\":{\"orderId\":7}}");
  }

  /**
   * The other half of a tool exchange, and the most common part kind in a real trace after text.
   * Its {@code response} goes through {@link SafeSerializer} for the same reason a call's arguments
   * do — a tool author chooses the shape.
   */
  @Test
  public void serialize_functionResponse_carriesIdNameAndResponse() throws Exception {
    FunctionResponse response =
        FunctionResponse.builder()
            .id("call-1")
            .name("lookup_order")
            .response(ImmutableMap.of("status", "shipped"))
            .build();

    String json = serialize(contentOf(Part.builder().functionResponse(response).build()));

    assertThat(json)
        .contains(
            "\"function_response\":{\"id\":\"call-1\",\"name\":\"lookup_order\","
                + "\"response\":{\"status\":\"shipped\"}}");
  }

  @Test
  public void serialize_fileData_carriesTheUriAndMimeType() throws Exception {
    FileData fileData =
        FileData.builder().fileUri("gs://bucket/report.pdf").mimeType("application/pdf").build();

    assertThat(serialize(contentOf(Part.builder().fileData(fileData).build())))
        .contains(
            "\"file_data\":{\"file_uri\":\"gs://bucket/report.pdf\","
                + "\"mime_type\":\"application/pdf\"}");
  }

  @Test
  public void serialize_codeExecutionResult_carriesOutcomeAndOutput() throws Exception {
    CodeExecutionResult result =
        CodeExecutionResult.builder().outcome(Outcome.Known.OUTCOME_OK).output("42").build();

    assertThat(serialize(contentOf(Part.builder().codeExecutionResult(result).build())))
        .contains("\"code_execution_result\":{\"outcome\":\"OUTCOME_OK\",\"output\":\"42\"}");
  }

  @Test
  public void serialize_executableCode_carriesLanguageAndCode() throws Exception {
    ExecutableCode code =
        ExecutableCode.builder().language(Language.Known.PYTHON).code("print(6 * 7)").build();

    assertThat(serialize(contentOf(Part.builder().executableCode(code).build())))
        .contains("\"executable_code\":{\"language\":\"PYTHON\",\"code\":\"print(6 * 7)\"}");
  }

  @Test
  public void from_keepsOnlyThePartsThatSurvive() {
    ContentTrace trace =
        ContentTrace.from(
            contentOf(
                Part.builder().text("kept").build(),
                Part.builder().text("").build(),
                Part.builder().build()));

    assertThat(trace.parts()).hasSize(1);
    assertThat(trace.parts().get(0).text()).hasValue("kept");
  }

  @Test
  public void from_returnsAnImmutableList() {
    ContentTrace trace = ContentTrace.from(contentOf(Part.builder().text("hi").build()));

    assertThat(trace.parts()).isInstanceOf(ImmutableList.class);
  }
}
