/*
 * Copyright 2025 Google LLC
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
package com.google.adk.models;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FileData;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class GeminiUtilTest {

  private static final Content CONTINUE_CONTENT =
      Content.fromParts(Part.fromText(GeminiUtil.CONTINUE_OUTPUT_MESSAGE));

  @Test
  public void stripThoughts_emptyList_returnsEmptyList() {
    assertThat(GeminiUtil.stripThoughts(ImmutableList.of())).isEmpty();
  }

  @Test
  public void stripThoughts_contentWithNoParts_returnsContentWithNoParts() {
    Content content = Content.builder().build();
    Content expected = toContent();

    List<Content> result = GeminiUtil.stripThoughts(ImmutableList.of(content));

    assertThat(result).containsExactly(expected);
  }

  @Test
  public void stripThoughts_partsWithoutThought_returnsAllParts() {
    Part part1 = createTextPart("Hello");
    Part part2 = createTextPart("World");
    Content content = toContent(part1, part2);

    List<Content> result = GeminiUtil.stripThoughts(ImmutableList.of(content));

    assertThat(result.get(0).parts().get()).containsExactly(part1, part2).inOrder();
  }

  @Test
  public void stripThoughts_partsWithThoughtFalse_returnsAllParts() {
    Part part1 = createThoughtPart("Regular text", false);
    Part part2 = createTextPart("Another text");
    Content content = toContent(part1, part2);

    List<Content> result = GeminiUtil.stripThoughts(ImmutableList.of(content));

    assertThat(result.get(0).parts().get()).containsExactly(part1, part2).inOrder();
  }

  @Test
  public void stripThoughts_partsWithThoughtTrue_stripsThoughtParts() {
    Part part1 = createTextPart("Visible text");
    Part part2 = createThoughtPart("Internal thought", true);
    Part part3 = createTextPart("More visible text");
    Content content = toContent(part1, part2, part3);

    List<Content> result = GeminiUtil.stripThoughts(ImmutableList.of(content));

    assertThat(result.get(0).parts().get()).containsExactly(part1, part3).inOrder();
  }

  @Test
  public void stripThoughts_mixedParts_stripsOnlyThoughtTrue() {
    Part part1 = createTextPart("Text 1");
    Part part2 = createThoughtPart("Thought 1", true);
    Part part3 = createTextPart("Text 2");
    Part part4 = createThoughtPart("Not a thought", false);
    Part part5 = createThoughtPart("Thought 2", true);
    Content content = toContent(part1, part2, part3, part4, part5);

    List<Content> result = GeminiUtil.stripThoughts(ImmutableList.of(content));

    assertThat(result.get(0).parts().get()).containsExactly(part1, part3, part4).inOrder();
  }

  @Test
  public void stripThoughts_multipleContents_stripsThoughtsFromEach() {
    Part partA1 = createTextPart("A1");
    Part partA2 = createThoughtPart("A2 Thought", true);
    Content contentA = toContent(partA1, partA2);

    Part partB1 = createThoughtPart("B1 Thought", true);
    Part partB2 = createTextPart("B2");
    Part partB3 = createThoughtPart("B3 Not Thought", false);
    Content contentB = toContent(partB1, partB2, partB3);

    List<Content> result = GeminiUtil.stripThoughts(ImmutableList.of(contentA, contentB));

    assertThat(result).hasSize(2);
    assertThat(result.get(0).parts().get()).containsExactly(partA1);
    assertThat(result.get(1).parts().get()).containsExactly(partB2, partB3).inOrder();
  }

  @Test
  public void getTextFromLlmResponse_noContent_returnsEmptyString() {
    LlmResponse llmResponse = LlmResponse.builder().build();

    assertThat(GeminiUtil.getTextFromLlmResponse(llmResponse)).isEmpty();
  }

  @Test
  public void getTextFromLlmResponse_contentWithNoParts_returnsEmptyString() {
    LlmResponse llmResponse = toResponse(Content.builder().build());

    assertThat(GeminiUtil.getTextFromLlmResponse(llmResponse)).isEmpty();
  }

  @Test
  public void getTextFromLlmResponse_firstPartHasNoText_returnsEmptyString() {
    Part part1 = Part.builder().inlineData(Blob.builder().mimeType("image/png").build()).build();
    LlmResponse llmResponse = toResponse(part1);

    assertThat(GeminiUtil.getTextFromLlmResponse(llmResponse)).isEmpty();
  }

  @Test
  public void getTextFromLlmResponse_firstPartHasText_returnsText() {
    String expectedText = "The quick brown fox.";
    Part part1 = createTextPart(expectedText);
    LlmResponse llmResponse = toResponse(part1);

    assertThat(GeminiUtil.getTextFromLlmResponse(llmResponse)).isEqualTo(expectedText);
  }

  @Test
  public void getTextFromLlmResponse_multipleParts_returnsTextFromFirstPartOnly() {
    String expectedText = "First part text.";
    Part part1 = createTextPart(expectedText);
    Part part2 = createTextPart("Second part text.");
    LlmResponse llmResponse = toResponse(part1, part2);

    assertThat(GeminiUtil.getTextFromLlmResponse(llmResponse)).isEqualTo(expectedText);
  }

  @Test
  public void shouldEmitAccumulatedText_noContent_returnsTrue() {
    LlmResponse llmResponse = LlmResponse.builder().build();

    assertThat(GeminiUtil.shouldEmitAccumulatedText(llmResponse)).isTrue();
  }

  @Test
  public void shouldEmitAccumulatedText_contentWithNoParts_returnsTrue() {
    LlmResponse llmResponse = toResponse(Content.builder().build());

    assertThat(GeminiUtil.shouldEmitAccumulatedText(llmResponse)).isTrue();
  }

  @Test
  public void shouldEmitAccumulatedText_contentWithEmptyPartsList_returnsTrue() {
    LlmResponse llmResponse = toResponse(toContent());

    assertThat(GeminiUtil.shouldEmitAccumulatedText(llmResponse)).isTrue();
  }

  @Test
  public void shouldEmitAccumulatedText_firstPartHasInlineData_returnsFalse() {
    Part part =
        Part.builder()
            .inlineData(Blob.builder().mimeType("image/png").data("bytes".getBytes(UTF_8)).build())
            .build();
    LlmResponse llmResponse = toResponse(part);

    assertThat(GeminiUtil.shouldEmitAccumulatedText(llmResponse)).isFalse();
  }

  @Test
  public void shouldEmitAccumulatedText_firstPartHasText_returnsTrue() {
    Part part = createTextPart("Some text");
    LlmResponse llmResponse = toResponse(part);

    assertThat(GeminiUtil.shouldEmitAccumulatedText(llmResponse)).isTrue();
  }

  @Test
  public void shouldEmitAccumulatedText_firstPartHasFileData_returnsTrue() {
    Part part =
        Part.builder()
            .fileData(
                FileData.builder().mimeType("image/png").fileUri("gs://bucket/object").build())
            .build();
    LlmResponse llmResponse = toResponse(part);

    assertThat(GeminiUtil.shouldEmitAccumulatedText(llmResponse)).isTrue();
  }

  @Test
  public void sanitizeRequestForGeminiApi_noConfig_returnsSameRequest() {
    LlmRequest request = LlmRequest.builder().build();

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest).isEqualTo(request);
  }

  @Test
  public void sanitizeRequestForGeminiApi_configWithoutLabels_returnsSameRequest() {
    GenerateContentConfig config = GenerateContentConfig.builder().temperature(0.5f).build();
    LlmRequest request = LlmRequest.builder().config(config).build();

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest).isEqualTo(request);
  }

  @Test
  public void sanitizeRequestForGeminiApi_configWithLabels_removesLabels() {
    GenerateContentConfig config =
        GenerateContentConfig.builder().labels(ImmutableMap.of("key", "value")).build();
    LlmRequest request = LlmRequest.builder().config(config).build();

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest.config()).isPresent();
    assertThat(sanitizedRequest.config().get().labels().get()).isEmpty();
  }

  @Test
  public void sanitizeRequestForGeminiApi_emptyContentsList_returnsSameRequest() {
    LlmRequest request = LlmRequest.builder().contents(ImmutableList.of()).build();

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest).isEqualTo(request);
  }

  @Test
  public void sanitizeRequestForGeminiApi_noContents_returnsSameRequest() {
    LlmRequest request = LlmRequest.builder().build();

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest).isEqualTo(request);
  }

  @Test
  public void sanitizeRequestForGeminiApi_contentWithNoParts_returnsSameContent() {
    Content content = Content.builder().build();
    LlmRequest request = toRequest(content);

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest.contents()).containsExactly(content);
  }

  @Test
  public void sanitizeRequestForGeminiApi_inlineDataWithDisplayName_removesDisplayName() {
    Blob blobWithDisplayName =
        Blob.builder()
            .mimeType("image/png")
            .data("bytes".getBytes(UTF_8))
            .displayName("image1")
            .build();
    Part part = Part.builder().inlineData(blobWithDisplayName).build();
    LlmRequest request = toRequest(part);

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest.contents()).hasSize(1);
    assertThat(sanitizedRequest.contents().get(0).parts()).isPresent();
    assertThat(sanitizedRequest.contents().get(0).parts().get()).hasSize(1);
    Part sanitizedPart = sanitizedRequest.contents().get(0).parts().get().get(0);
    assertThat(sanitizedPart.inlineData()).isPresent();
    Blob sanitizedBlob = sanitizedPart.inlineData().get();
    assertThat(sanitizedBlob.displayName()).isEmpty();
    assertThat(sanitizedBlob.mimeType()).hasValue("image/png");
    assertThat(sanitizedBlob.data()).isPresent();
    assertThat(Arrays.equals(sanitizedBlob.data().get(), "bytes".getBytes(UTF_8))).isTrue();
  }

  @Test
  public void sanitizeRequestForGeminiApi_inlineDataWithoutDisplayName_returnsSamePart() {
    Blob blob = Blob.builder().mimeType("image/png").data("bytes".getBytes(UTF_8)).build();
    Part part = Part.builder().inlineData(blob).build();
    LlmRequest request = toRequest(part);

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest.contents().get(0).parts().get()).containsExactly(part);
  }

  @Test
  public void sanitizeRequestForGeminiApi_fileDataWithDisplayName_removesDisplayName() {
    FileData fileDataWithDisplayName =
        FileData.builder()
            .mimeType("image/png")
            .fileUri("gs://bucket/object")
            .displayName("file1")
            .build();
    Part part = Part.builder().fileData(fileDataWithDisplayName).build();
    LlmRequest request = toRequest(part);

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    FileData expectedFileData =
        FileData.builder().mimeType("image/png").fileUri("gs://bucket/object").build();
    Part expectedPart = Part.builder().fileData(expectedFileData).build();
    assertThat(sanitizedRequest.contents().get(0).parts().get()).containsExactly(expectedPart);
  }

  @Test
  public void sanitizeRequestForGeminiApi_fileDataWithoutDisplayName_returnsSamePart() {
    FileData fileData =
        FileData.builder().mimeType("image/png").fileUri("gs://bucket/object").build();
    Part part = Part.builder().fileData(fileData).build();

    LlmRequest request = toRequest(part);
    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest.contents().get(0).parts().get()).containsExactly(part);
  }

  @Test
  public void sanitizeRequestForGeminiApi_mixedParts_sanitizesOnlyAffectedParts() {
    Part textPart = createTextPart("Some text");
    Blob blobWithDisplayName =
        Blob.builder()
            .mimeType("image/png")
            .data("bytes".getBytes(UTF_8))
            .displayName("image1")
            .build();
    Part inlineDataPart = Part.builder().inlineData(blobWithDisplayName).build();
    FileData fileDataWithDisplayName =
        FileData.builder()
            .mimeType("image/png")
            .fileUri("gs://bucket/object")
            .displayName("file1")
            .build();
    Part fileDataPart = Part.builder().fileData(fileDataWithDisplayName).build();
    LlmRequest request = toRequest(textPart, inlineDataPart, fileDataPart);

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    // Expected inlineData part: displayName is removed, but the byte array content is the same.
    // We need to use the exact byte array instance from the original Blob for equals to work
    // in containsExactly, as byte[].equals compares references.
    byte[] originalBlobData = blobWithDisplayName.data().get();
    Blob expectedBlob = Blob.builder().mimeType("image/png").data(originalBlobData).build();
    Part expectedInlineDataPart = Part.builder().inlineData(expectedBlob).build();
    FileData expectedFileData =
        FileData.builder().mimeType("image/png").fileUri("gs://bucket/object").build();
    Part expectedFileDataPart = Part.builder().fileData(expectedFileData).build();
    List<Part> sanitizedParts = sanitizedRequest.contents().get(0).parts().get();
    assertThat(sanitizedParts)
        .containsExactly(textPart, expectedInlineDataPart, expectedFileDataPart)
        .inOrder();
  }

  @Test
  public void sanitizeRequestForGeminiApi_multipleContents_sanitizesAll() {
    // Content 1: InlineData with display name
    Blob blob1 =
        Blob.builder().mimeType("image/png").data("d1".getBytes(UTF_8)).displayName("img1").build();
    Content content1 = toContent(Part.builder().inlineData(blob1).build());
    // Content 2: FileData with display name
    FileData fd2 =
        FileData.builder().mimeType("video/mp4").fileUri("gs://b/o2").displayName("vid2").build();
    Content content2 = toContent(Part.builder().fileData(fd2).build());
    // Content 3: No display names
    Content content3 = toContent(createTextPart("C3"));
    LlmRequest request = toRequest(content1, content2, content3);

    LlmRequest sanitizedRequest = GeminiUtil.sanitizeRequestForGeminiApi(request);

    assertThat(sanitizedRequest.contents()).hasSize(3);
    // Verify Content 1: InlineData display name is removed.
    Content sanitizedContent1 = sanitizedRequest.contents().get(0);
    assertThat(sanitizedContent1.parts()).isPresent();
    Part sanitizedPart1 = Iterables.getOnlyElement(sanitizedContent1.parts().get());
    Blob expectedBlob1 = Blob.builder().mimeType("image/png").data(blob1.data().get()).build();
    assertThat(sanitizedPart1.inlineData()).hasValue(expectedBlob1);
    // Verify Content 2: FileData display name is removed.
    Content sanitizedContent2 = sanitizedRequest.contents().get(1);
    assertThat(sanitizedContent2.parts()).isPresent();
    Part sanitizedPart2 = Iterables.getOnlyElement(sanitizedContent2.parts().get());
    FileData expectedFd2 = FileData.builder().mimeType("video/mp4").fileUri("gs://b/o2").build();
    assertThat(sanitizedPart2.fileData()).hasValue(expectedFd2);
    // Verify Content 3: Should be unchanged.
    assertThat(sanitizedRequest.contents().get(2)).isEqualTo(content3);
  }

  @Test
  public void ensureModelResponse_emptyList_appendsContinueMessage() {
    ImmutableList<Content> contents = ImmutableList.of();

    List<Content> result = GeminiUtil.ensureModelResponse(contents);

    assertThat(result).containsExactly(CONTINUE_CONTENT);
  }

  @Test
  public void ensureModelResponse_userRoleIsLast_returnsSameList() {
    Content modelContent = Content.builder().role("model").build();
    Content userContent = Content.builder().role("user").build();
    ImmutableList<Content> contents = ImmutableList.of(modelContent, userContent);

    List<Content> result = GeminiUtil.ensureModelResponse(contents);

    assertThat(result).containsExactly(modelContent, userContent).inOrder();
  }

  @Test
  public void ensureModelResponse_lastContentIsNotUser_appendsContinueMessage() {
    Content modelContent1 = Content.builder().role("model").build();
    Content modelContent2 = Content.builder().role("model").build();
    Content modelContent3 = Content.builder().role("model").build();
    Content userContent1 = Content.builder().role("user").build();
    Content userContent2 = Content.builder().role("user").build();

    // Case 1: No user role, last is model
    ImmutableList<Content> contents1 = ImmutableList.of(modelContent1, modelContent2);
    assertThat(GeminiUtil.ensureModelResponse(contents1))
        .containsExactly(modelContent1, modelContent2, CONTINUE_CONTENT)
        .inOrder();

    // Case 2: User role is first, last is model
    ImmutableList<Content> contents2 = ImmutableList.of(userContent1, modelContent1);
    assertThat(GeminiUtil.ensureModelResponse(contents2))
        .containsExactly(userContent1, modelContent1, CONTINUE_CONTENT)
        .inOrder();

    // Case 3: User role in middle, last is model
    ImmutableList<Content> contents3 = ImmutableList.of(modelContent1, userContent1, modelContent2);
    assertThat(GeminiUtil.ensureModelResponse(contents3))
        .containsExactly(modelContent1, userContent1, modelContent2, CONTINUE_CONTENT)
        .inOrder();

    // Case 4: Multiple user roles, last is model
    ImmutableList<Content> contents4 =
        ImmutableList.of(modelContent1, userContent1, modelContent2, userContent2, modelContent3);
    assertThat(GeminiUtil.ensureModelResponse(contents4))
        .containsExactly(
            modelContent1,
            userContent1,
            modelContent2,
            userContent2,
            modelContent3,
            CONTINUE_CONTENT)
        .inOrder();
  }

  @Test
  public void prepareGenenerateContentRequest_emptyRequest_returnsRequestWithContinueContent() {
    LlmRequest request = LlmRequest.builder().build();

    LlmRequest result = GeminiUtil.prepareGenenerateContentRequest(request, true);

    assertThat(result.contents()).containsExactly(CONTINUE_CONTENT);
    assertThat(result.config()).isEmpty();
  }

  @Test
  public void
      prepareGenenerateContentRequest_withContentsAndConfig_appliesSanitizationAndEnsuresUserRole() {
    // Config with labels to be sanitized
    GenerateContentConfig config =
        GenerateContentConfig.builder().labels(ImmutableMap.of("key", "value")).build();
    // Contents: InlineData with display name (to be sanitized) and a model role last (needs
    // CONTINUE_CONTENT)
    Blob blobWithDisplayName =
        Blob.builder()
            .mimeType("image/png")
            .data("bytes".getBytes(UTF_8))
            .displayName("image1")
            .build();
    Part inlineDataPart = Part.builder().inlineData(blobWithDisplayName).build();
    Content content1 = toContent(inlineDataPart);
    // Content with role "model". sanitizeRequestForGeminiApi ensures that the parts list is
    // present,
    // even if empty, so we initialize it as such.
    Content content2 = Content.builder().role("model").parts(ImmutableList.of()).build();
    LlmRequest request =
        LlmRequest.builder().contents(ImmutableList.of(content1, content2)).config(config).build();

    LlmRequest result = GeminiUtil.prepareGenenerateContentRequest(request, /* sanitize= */ true);

    // Expected sanitized config: labels removed
    assertThat(result.config()).isPresent();
    assertThat(result.config().get().labels().get()).isEmpty();

    // Expected contents: inlineDataPart display name removed, and CONTINUE_CONTENT appended
    Blob expectedBlob =
        Blob.builder().mimeType("image/png").data(blobWithDisplayName.data().get()).build();
    Part expectedInlineDataPart = Part.builder().inlineData(expectedBlob).build();
    Content expectedContent1 = toContent(expectedInlineDataPart);
    assertThat(result.contents())
        .containsExactly(expectedContent1, content2, CONTINUE_CONTENT)
        .inOrder();
  }

  private static Content toContent(Part... parts) {
    return Content.builder().parts(ImmutableList.copyOf(parts)).build();
  }

  private static LlmRequest toRequest(Part... parts) {
    return toRequest(toContent(parts));
  }

  private static LlmRequest toRequest(Content... contents) {
    return toRequest(ImmutableList.copyOf(contents));
  }

  private static LlmRequest toRequest(List<Content> contents) {
    return LlmRequest.builder().contents(contents).build();
  }

  private static LlmResponse toResponse(Content content) {
    return LlmResponse.builder().content(content).build();
  }

  private static LlmResponse toResponse(Part... parts) {
    return toResponse(toContent(parts));
  }

  private static Part createTextPart(String text) {
    return Part.builder().text(text).build();
  }

  private static Part createThoughtPart(String text, boolean isThought) {
    return Part.builder().text(text).thought(isThought).build();
  }
}
