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

package com.google.adk.tools;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.adk.agents.InvocationContext;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.adk.models.LlmRequest;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LoadArtifactsToolTest {

  private LoadArtifactsTool loadArtifactsTool;
  private ToolContext mockToolContext;
  private InvocationContext mockInvocationContext;
  private BaseArtifactService mockArtifactService;
  private LlmRequest.Builder llmRequestBuilder;

  @Before
  public void setUp() {
    loadArtifactsTool = new LoadArtifactsTool();
    mockInvocationContext = mock(InvocationContext.class);
    mockArtifactService = mock(BaseArtifactService.class);
    when(mockInvocationContext.artifactService()).thenReturn(mockArtifactService);
    when(mockInvocationContext.session()).thenReturn(Session.builder("test-session").build());
    mockToolContext = ToolContext.builder(mockInvocationContext).build();
    llmRequestBuilder = LlmRequest.builder();
  }

  @Test
  public void declaration_returnsCorrectFunctionDeclaration() {
    Optional<FunctionDeclaration> declarationOpt = loadArtifactsTool.declaration();
    assertThat(declarationOpt).isPresent();
    FunctionDeclaration declaration = declarationOpt.get();

    assertThat(declaration.name().orElse("")).isEqualTo("load_artifacts");
    assertThat(declaration.description().orElse(""))
        .isEqualTo("Loads the artifacts and adds them to the session.");

    Schema expectedParameters =
        Schema.builder()
            .type("OBJECT")
            .properties(
                ImmutableMap.of(
                    "artifact_names",
                    Schema.builder()
                        .type("ARRAY")
                        .items(Schema.builder().type("STRING").build())
                        .build()))
            .build();
    assertThat(declaration.parameters().orElse(null)).isEqualTo(expectedParameters);
  }

  @Test
  public void run_withArtifactNames_returnsMapWithArtifactNames() {
    ImmutableMap<String, Object> args =
        ImmutableMap.of("artifact_names", ImmutableList.of("file1", "file2"));
    Map<String, Object> result = loadArtifactsTool.runAsync(args, mockToolContext).blockingGet();
    assertThat(result).containsExactly("artifact_names", ImmutableList.of("file1", "file2"));
  }

  @Test
  public void run_withoutArtifactNames_returnsMapWithEmptyList() {
    ImmutableMap<String, Object> args = ImmutableMap.of();
    Map<String, Object> result = loadArtifactsTool.runAsync(args, mockToolContext).blockingGet();
    assertThat(result).containsExactly("artifact_names", ImmutableList.of());
  }

  @Test
  public void processLlmRequest_noArtifactsInContext_completesWithoutLoading() {
    ListArtifactsResponse emptyResponse =
        ListArtifactsResponse.builder().filenames(ImmutableList.of()).build();
    when(mockArtifactService.listArtifactKeys(
            nullable(String.class), nullable(String.class), anyString()))
        .thenReturn(Single.just(emptyResponse));

    loadArtifactsTool.processLlmRequest(llmRequestBuilder, mockToolContext).blockingAwait();

    LlmRequest finalRequest = llmRequestBuilder.build();
    assertThat(finalRequest.config()).isPresent();
    assertThat(finalRequest.config().get().systemInstruction()).isEmpty();
    verify(mockArtifactService, never())
        .loadArtifact(anyString(), anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  public void processLlmRequest_artifactsInContext_noFunctionCall_appendsInstructions() {
    ImmutableList<String> artifactNamesList = ImmutableList.of("file1.txt", "file2.pdf");
    ListArtifactsResponse listResponse =
        ListArtifactsResponse.builder().filenames(artifactNamesList).build();
    when(mockArtifactService.listArtifactKeys(
            nullable(String.class), nullable(String.class), anyString()))
        .thenReturn(Single.just(listResponse));

    loadArtifactsTool.processLlmRequest(llmRequestBuilder, mockToolContext).blockingAwait();

    LlmRequest finalRequest = llmRequestBuilder.build();
    assertThat(finalRequest.config()).isPresent();
    assertThat(finalRequest.config().get().systemInstruction()).isPresent();
    assertThat(finalRequest.config().get().systemInstruction().get().parts()).isPresent();
    String appendedInstruction =
        finalRequest.config().get().systemInstruction().get().parts().get().get(0).text().get();
    assertThat(appendedInstruction).contains("You have a list of artifacts:");
    assertThat(appendedInstruction).contains("[\"file1.txt\",\"file2.pdf\"]");
    assertThat(appendedInstruction).contains("call the `load_artifacts` function");

    verify(mockArtifactService, never())
        .loadArtifact(anyString(), anyString(), anyString(), anyString(), anyInt());
  }

  @Test
  public void processLlmRequest_artifactsInContext_withLoadArtifactsFunctionCall_loadsAndAppends() {
    ImmutableList<String> availableArtifacts = ImmutableList.of("doc1.txt", "image.png");
    ImmutableList<String> artifactsToLoad = ImmutableList.of("doc1.txt");

    ListArtifactsResponse listResponse =
        ListArtifactsResponse.builder().filenames(availableArtifacts).build();
    when(mockArtifactService.listArtifactKeys(
            nullable(String.class), nullable(String.class), anyString()))
        .thenReturn(Single.just(listResponse));

    FunctionResponse functionResponse =
        FunctionResponse.builder()
            .name("load_artifacts")
            .response(ImmutableMap.of("artifact_names", artifactsToLoad))
            .build();
    Content functionCallContent =
        Content.builder()
            .role("model")
            .parts(
                ImmutableList.of(
                    Part.fromFunctionResponse(
                        functionResponse.name().get(), functionResponse.response().get())))
            .build();
    llmRequestBuilder.contents(ImmutableList.of(functionCallContent));

    Part loadedArtifactPart = Part.fromText("This is the content of doc1.txt");
    ToolContext spiedToolContext = spy(ToolContext.builder(mockInvocationContext).build());
    when(spiedToolContext.listArtifacts()).thenReturn(Single.just(availableArtifacts));
    when(spiedToolContext.loadArtifact("doc1.txt")).thenReturn(Maybe.just(loadedArtifactPart));

    loadArtifactsTool.processLlmRequest(llmRequestBuilder, spiedToolContext).blockingAwait();

    verify(spiedToolContext).loadArtifact("doc1.txt");

    LlmRequest finalRequest = llmRequestBuilder.build();
    List<Content> finalContents = finalRequest.contents();

    assertThat(finalContents).hasSize(2);

    Content appendedContent = finalContents.get(1);
    assertThat(appendedContent.role().orElse("")).isEqualTo("user");
    assertThat(appendedContent.parts().get()).hasSize(2);
    assertThat(appendedContent.parts().get().get(0).text()).hasValue("Artifact doc1.txt is:");
    assertThat(appendedContent.parts().get().get(1)).isEqualTo(loadedArtifactPart);
  }

  @Test
  public void processLlmRequest_artifactsInContext_withOtherFunctionCall_doesNotLoad() {
    ImmutableList<String> availableArtifacts = ImmutableList.of("doc1.txt");
    ListArtifactsResponse listResponse =
        ListArtifactsResponse.builder().filenames(availableArtifacts).build();
    when(mockArtifactService.listArtifactKeys(
            nullable(String.class), nullable(String.class), anyString()))
        .thenReturn(Single.just(listResponse));

    FunctionResponse functionResponse =
        FunctionResponse.builder()
            .name("other_function")
            .response(ImmutableMap.of("some_key", "some_value"))
            .build();
    Content functionCallContent =
        Content.builder()
            .role("model")
            .parts(
                ImmutableList.of(
                    Part.fromFunctionResponse(
                        functionResponse.name().get(), functionResponse.response().get())))
            .build();
    llmRequestBuilder.contents(ImmutableList.of(functionCallContent));

    loadArtifactsTool.processLlmRequest(llmRequestBuilder, mockToolContext).blockingAwait();

    LlmRequest finalRequest = llmRequestBuilder.build();
    assertThat(finalRequest.config()).isPresent();
    assertThat(finalRequest.config().get().systemInstruction()).isPresent();
    assertThat(finalRequest.config().get().systemInstruction().get().parts()).isPresent();
    assertThat(
            finalRequest.config().get().systemInstruction().get().parts().get().get(0).text().get())
        .contains("You have a list of artifacts:");

    verify(mockArtifactService, never())
        .loadArtifact(anyString(), anyString(), anyString(), anyString(), anyInt());
    assertThat(finalRequest.contents()).containsExactly(functionCallContent);
  }

  @Test
  public void processLlmRequest_unsupportedTextLikeMime_convertsToText() {
    String artifactName = "data.csv";
    String csvContent = "col1,col2\n1,2\n";
    Part artifactPart =
        processLoadArtifactRequest(
            artifactName,
            Part.fromBytes(
                csvContent.getBytes(StandardCharsets.UTF_8), "application/csv; charset=utf-8"));

    assertThat(artifactPart.inlineData()).isEmpty();
    assertThat(artifactPart.text()).hasValue(csvContent);
  }

  @Test
  public void processLlmRequest_supportedMime_keepsInlineData() {
    String artifactName = "file.pdf";
    byte[] pdfBytes = "%PDF-1.4".getBytes(StandardCharsets.UTF_8);
    Part artifactPart =
        processLoadArtifactRequest(artifactName, Part.fromBytes(pdfBytes, "application/pdf"));

    assertThat(artifactPart.inlineData()).isPresent();
    assertThat(artifactPart.inlineData().get().mimeType()).hasValue("application/pdf");
    assertThat(artifactPart.inlineData().get().data().get()).isEqualTo(pdfBytes);
  }

  @Test
  public void processLlmRequest_unsupportedBinaryMime_convertsToPlaceholder() {
    String artifactName = "slides.pptx";
    Part artifactPart =
        processLoadArtifactRequest(
            artifactName,
            Part.fromBytes(
                new byte[] {1, 2, 3},
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

    assertThat(artifactPart.inlineData()).isEmpty();
    assertThat(artifactPart.text())
        .hasValue(
            "[Binary artifact: slides.pptx, type:"
                + " application/vnd.openxmlformats-officedocument.presentationml.presentation,"
                + " size: 0.0 KB. Content cannot be displayed inline.]");
  }

  @Test
  public void processLlmRequest_unsupportedMimeWithoutInlineData_convertsToNoDataPlaceholder() {
    String artifactName = "empty.bin";
    Part artifactPart =
        processLoadArtifactRequest(
            artifactName,
            Part.builder()
                .inlineData(Blob.builder().mimeType("application/octet-stream").build())
                .build());

    assertThat(artifactPart.inlineData()).isEmpty();
    assertThat(artifactPart.text())
        .hasValue(
            "[Artifact: empty.bin, type: application/octet-stream."
                + " No inline data was provided.]");
  }

  @Test
  public void processLlmRequest_emptyMime_defaultsToOctetStream() {
    String artifactName = "unknown";
    Part artifactPart =
        processLoadArtifactRequest(
            artifactName,
            Part.fromBytes(new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}, ""));

    assertThat(artifactPart.inlineData()).isEmpty();
    assertThat(artifactPart.text())
        .hasValue(
            "[Binary artifact: unknown, type: application/octet-stream,"
                + " size: 0.0 KB. Content cannot be displayed inline.]");
  }

  @Test
  public void processLlmRequest_nullMime_defaultsToOctetStream() {
    String artifactName = "mystery";
    Part artifactPart =
        processLoadArtifactRequest(
            artifactName,
            Part.builder()
                .inlineData(
                    Blob.builder()
                        .data(new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF})
                        .build())
                .build());

    assertThat(artifactPart.inlineData()).isEmpty();
    assertThat(artifactPart.text())
        .hasValue(
            "[Binary artifact: mystery, type: application/octet-stream,"
                + " size: 0.0 KB. Content cannot be displayed inline.]");
  }

  private Part processLoadArtifactRequest(String artifactName, Part loadedArtifactPart) {
    ImmutableList<String> availableArtifacts = ImmutableList.of(artifactName);
    ImmutableList<String> artifactsToLoad = ImmutableList.of(artifactName);

    FunctionResponse functionResponse =
        FunctionResponse.builder()
            .name("load_artifacts")
            .response(ImmutableMap.of("artifact_names", artifactsToLoad))
            .build();
    Content functionCallContent =
        Content.builder()
            .role("model")
            .parts(
                ImmutableList.of(
                    Part.fromFunctionResponse(
                        functionResponse.name().get(), functionResponse.response().get())))
            .build();
    llmRequestBuilder.contents(ImmutableList.of(functionCallContent));

    ToolContext spiedToolContext = spy(ToolContext.builder(mockInvocationContext).build());
    doReturn(Single.just(availableArtifacts)).when(spiedToolContext).listArtifacts();
    doReturn(Maybe.just(loadedArtifactPart)).when(spiedToolContext).loadArtifact(artifactName);

    loadArtifactsTool.processLlmRequest(llmRequestBuilder, spiedToolContext).blockingAwait();
    verify(spiedToolContext).loadArtifact(artifactName);

    LlmRequest finalRequest = llmRequestBuilder.build();
    assertThat(finalRequest.contents()).hasSize(2);
    Content appendedContent = finalRequest.contents().get(1);
    assertThat(appendedContent.role()).hasValue("user");
    assertThat(appendedContent.parts()).isPresent();
    assertThat(appendedContent.parts().get()).hasSize(2);
    assertThat(appendedContent.parts().get().get(0).text())
        .hasValue("Artifact " + artifactName + " is:");
    return appendedContent.parts().get().get(1);
  }
}
